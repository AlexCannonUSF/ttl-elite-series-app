package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.repository.PlayerAliasRepository;
import com.ttl.tabletennis.repository.PlayerRepository;
import com.ttl.tabletennis.util.NameUtils;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class PlayerCanonicaliserBenchmarkIT {

    private static final String DATASET_PATH = "/players/canonical-pairs.csv";
    private static final double MIN_PRECISION = 0.99;
    private static final double MIN_RECALL = 0.95;

    private final PlayerCanonicaliser canonicaliser = new PlayerCanonicaliser(
            mock(PlayerRepository.class),
            mock(PlayerAliasRepository.class)
    );

    @Test
    void benchmarkMeetsPhase01PrecisionAndRecallGate() throws IOException {
        List<BenchmarkRow> rows = loadRows();
        assertEquals(500, rows.size(), "Expected the labelled canonical benchmark to contain 500 rows.");

        int truePositives = 0;
        int falsePositives = 0;
        int falseNegatives = 0;
        List<String> mismatches = new ArrayList<>();

        for (BenchmarkRow row : rows) {
            PlayerCanonicaliser.CanonicalisationResult result = canonicaliser.canonicalise(
                    new PlayerCanonicaliser.CanonicalisationRequest(row.rawName(), blankToNull(row.requestCountry())),
                    List.of(candidate(row))
            );

            boolean predictedMatch = result.resolved();
            if (row.expectedMatch()) {
                if (predictedMatch) {
                    truePositives++;
                } else {
                    falseNegatives++;
                    mismatches.add(describe(row, "expected match but canonicaliser rejected candidate"));
                }
            } else if (predictedMatch) {
                falsePositives++;
                mismatches.add(describe(row, "expected reject but canonicaliser accepted candidate"));
            }
        }

        double precision = truePositives / (double) Math.max(1, truePositives + falsePositives);
        double recall = truePositives / (double) Math.max(1, truePositives + falseNegatives);

        assertTrue(
                precision >= MIN_PRECISION,
                "Precision gate failed: %.4f < %.4f. Sample mismatches: %s".formatted(
                        precision,
                        MIN_PRECISION,
                        mismatchSample(mismatches)
                )
        );
        assertTrue(
                recall >= MIN_RECALL,
                "Recall gate failed: %.4f < %.4f. Sample mismatches: %s".formatted(
                        recall,
                        MIN_RECALL,
                        mismatchSample(mismatches)
                )
        );
    }

    private List<BenchmarkRow> loadRows() throws IOException {
        InputStream inputStream = getClass().getResourceAsStream(DATASET_PATH);
        if (inputStream == null) {
            throw new IOException("Missing benchmark dataset at " + DATASET_PATH);
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            List<BenchmarkRow> rows = new ArrayList<>();
            String line = reader.readLine(); // header
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split(",", -1);
                if (parts.length != 7) {
                    throw new IOException("Malformed benchmark row: " + line);
                }
                rows.add(new BenchmarkRow(
                        Integer.parseInt(parts[0].trim()),
                        parts[1].trim(),
                        parts[2].trim(),
                        parts[3].trim(),
                        parts[4].trim(),
                        parts[5].trim(),
                        Boolean.parseBoolean(parts[6].trim())
                ));
            }
            return rows;
        }
    }

    private PlayerCanonicaliser.PlayerCandidate candidate(BenchmarkRow row) {
        String[] split = NameUtils.splitFirstLast(row.candidateName());
        Player player = new Player(split[0], split[1]);
        player.setId((long) row.caseId());
        player.setNormalizedName(NameUtils.normalizeForLookup(row.candidateName()));

        return new PlayerCanonicaliser.PlayerCandidate(
                player,
                row.candidateName(),
                NameUtils.normalizeForLookup(row.candidateName()),
                blankToNull(row.candidateCountry()),
                Instant.parse(row.firstSeenAt()),
                PlayerCanonicaliser.CandidateSource.ALIAS
        );
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String describe(BenchmarkRow row, String reason) {
        return "#%d [%s] raw='%s' candidate='%s'".formatted(
                row.caseId(),
                reason,
                row.rawName(),
                row.candidateName()
        );
    }

    private String mismatchSample(List<String> mismatches) {
        if (mismatches.isEmpty()) {
            return "none";
        }
        return mismatches.stream()
                .limit(5)
                .reduce((left, right) -> left + " | " + right)
                .orElse("none");
    }

    private record BenchmarkRow(int caseId,
                                String rawName,
                                String requestCountry,
                                String candidateName,
                                String candidateCountry,
                                String firstSeenAt,
                                boolean expectedMatch) {
    }
}
