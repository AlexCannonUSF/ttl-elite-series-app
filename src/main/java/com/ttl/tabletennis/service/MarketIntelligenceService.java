package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.MarketBook;
import com.ttl.tabletennis.domain.OddsSnapshot;
import com.ttl.tabletennis.dto.MarketIntelligenceDto;
import com.ttl.tabletennis.repository.MarketBookRepository;
import com.ttl.tabletennis.repository.OddsSnapshotRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class MarketIntelligenceService {
    private static final String PRIMARY_SOURCE = "HR_MKT";
    private static final long STALE_SECONDS = 45L;
    private static final long CONSENSUS_MAX_AGE_SECONDS = 300L;

    private final OddsSnapshotRepository snapshotRepository;
    private final MarketBookRepository bookRepository;

    public MarketIntelligenceService(OddsSnapshotRepository snapshotRepository,
                                     MarketBookRepository bookRepository) {
        this.snapshotRepository = snapshotRepository;
        this.bookRepository = bookRepository;
    }

    @Transactional(readOnly = true)
    public MarketIntelligenceDto market(String identity, int historyLimit) {
        if (!StringUtils.hasText(identity)) throw new IllegalArgumentException("Event identity is required");
        int take = Math.max(20, Math.min(historyLimit, 1000));
        List<OddsSnapshot> rows = snapshotRepository.findMarketHistory(identity.trim(), PageRequest.of(0, take));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Map<String, MarketBook> metadata = new LinkedHashMap<>();
        bookRepository.findByEnabledTrueOrderByDisplayNameAsc()
                .forEach(book -> metadata.put(normalize(book.getSourceCode()), book));
        Map<String, SidePair> latest = new LinkedHashMap<>();
        for (OddsSnapshot row : rows) {
            String source = normalize(row.getSourceId());
            SidePair pair = latest.computeIfAbsent(source, ignored -> new SidePair());
            if ("P1".equalsIgnoreCase(row.getSide()) && pair.p1 == null) pair.p1 = row;
            if ("P2".equalsIgnoreCase(row.getSide()) && pair.p2 == null) pair.p2 = row;
        }

        List<MarketIntelligenceDto.BookLine> books = latest.entrySet().stream()
                .map(entry -> line(entry.getKey(), entry.getValue(), metadata.get(entry.getKey()), now))
                .sorted(Comparator.comparing((MarketIntelligenceDto.BookLine line) -> !line.executable())
                        .thenComparing(MarketIntelligenceDto.BookLine::displayName))
                .toList();
        List<WeightedProbability> consensus = books.stream()
                .filter(line -> !line.stale() && line.ageSeconds() <= CONSENSUS_MAX_AGE_SECONDS)
                .filter(line -> line.player1NoVigProbability() != null)
                .filter(line -> metadata.containsKey(normalize(line.sourceCode())))
                .map(line -> new WeightedProbability(line.player1NoVigProbability(),
                        Math.max(0.0, metadata.get(normalize(line.sourceCode())).getConsensusWeight())))
                .filter(item -> item.weight() > 0.0)
                .toList();
        Double p1Consensus = weightedMean(consensus);
        Double dispersion = weightedStdDev(consensus, p1Consensus);
        long freshestAge = books.stream().mapToLong(MarketIntelligenceDto.BookLine::ageSeconds).min().orElse(-1L);
        boolean execution = books.stream().anyMatch(line -> line.executable() && !line.stale()
                && "OPEN".equalsIgnoreCase(line.marketState()));
        List<String> warnings = new ArrayList<>();
        if (books.isEmpty()) warnings.add("No timestamped market snapshots match this event identity.");
        if (consensus.size() < 2) warnings.add("Consensus is single-source until another authorized reference feed supplies a fresh two-way line.");
        if (!execution && !books.isEmpty()) warnings.add("Hard Rock is unavailable, suspended, closed, or stale; reference lines are not executable prices.");
        return new MarketIntelligenceDto(now, identity.trim(), PRIMARY_SOURCE, execution, books.size(),
                consensus.size(), p1Consensus, p1Consensus == null ? null : 1.0 - p1Consensus,
                dispersion == null ? null : round4(dispersion * 100.0), freshestAge,
                books, history(rows), List.copyOf(warnings));
    }

    private static MarketIntelligenceDto.BookLine line(String source,
                                                       SidePair pair,
                                                       MarketBook book,
                                                       LocalDateTime now) {
        OddsSnapshot newest = newest(pair.p1, pair.p2);
        long age = newest == null || newest.getObservedAt() == null ? Long.MAX_VALUE
                : Math.max(0L, Duration.between(newest.getObservedAt(), now).getSeconds());
        String role = book == null ? "REFERENCE" : book.getMarketRole();
        boolean executable = "EXECUTABLE".equalsIgnoreCase(role);
        String displayName = book == null ? prettySource(source) : book.getDisplayName();
        String state = combinedState(pair.p1, pair.p2);
        Double p1NoVig = pair.p1 == null ? null : pair.p1.getNoVigProbability();
        Double p2NoVig = pair.p2 == null ? null : pair.p2.getNoVigProbability();
        Double overround = pair.p1 != null && pair.p1.getMarketOverround() != null
                ? pair.p1.getMarketOverround() * 100.0
                : pair.p2 != null && pair.p2.getMarketOverround() != null ? pair.p2.getMarketOverround() * 100.0 : null;
        return new MarketIntelligenceDto.BookLine(source, displayName, role, executable,
                book != null && book.isAuthorized(), state, newest == null ? null : newest.getObservedAt(),
                age == Long.MAX_VALUE ? -1L : age, age > STALE_SECONDS,
                decimal(pair.p1), decimal(pair.p2), american(decimal(pair.p1)), american(decimal(pair.p2)),
                p1NoVig, p2NoVig, overround == null ? null : round4(overround));
    }

    private static List<MarketIntelligenceDto.HistoryPoint> history(List<OddsSnapshot> rows) {
        Map<String, SidePair> grouped = new LinkedHashMap<>();
        rows.stream().sorted(Comparator.comparing(OddsSnapshot::getObservedAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(OddsSnapshot::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(row -> {
                    String key = normalize(row.getSourceId()) + "|" + row.getObservedAt();
                    SidePair pair = grouped.computeIfAbsent(key, ignored -> new SidePair());
                    if ("P1".equalsIgnoreCase(row.getSide())) pair.p1 = row;
                    if ("P2".equalsIgnoreCase(row.getSide())) pair.p2 = row;
                });
        return grouped.values().stream().filter(pair -> pair.p1 != null || pair.p2 != null)
                .map(pair -> {
                    OddsSnapshot newest = newest(pair.p1, pair.p2);
                    return new MarketIntelligenceDto.HistoryPoint(normalize(newest.getSourceId()), newest.getObservedAt(),
                            decimal(pair.p1), decimal(pair.p2), combinedState(pair.p1, pair.p2));
                }).toList();
    }

    private static OddsSnapshot newest(OddsSnapshot left, OddsSnapshot right) {
        if (left == null) return right; if (right == null) return left;
        return left.getObservedAt().isAfter(right.getObservedAt()) ? left : right;
    }
    private static String combinedState(OddsSnapshot p1, OddsSnapshot p2) {
        List<String> states = List.of(p1 == null ? "MISSING" : p1.getMarketState(), p2 == null ? "MISSING" : p2.getMarketState());
        if (states.stream().anyMatch("CLOSED"::equalsIgnoreCase)) return "CLOSED";
        if (states.stream().anyMatch("SUSPENDED"::equalsIgnoreCase)) return "SUSPENDED";
        if (states.stream().allMatch("OPEN"::equalsIgnoreCase)) return "OPEN";
        return "PARTIAL";
    }
    private static Double decimal(OddsSnapshot row) { return row == null ? null : row.getPriceDecimal(); }
    private static Integer american(Double decimal) { if (decimal == null || decimal <= 1.0) return null; return decimal >= 2.0 ? (int) Math.round((decimal - 1.0) * 100.0) : (int) Math.round(-100.0 / (decimal - 1.0)); }
    private static Double weightedMean(List<WeightedProbability> rows) { double weights = rows.stream().mapToDouble(WeightedProbability::weight).sum(); return weights <= 0 ? null : rows.stream().mapToDouble(row -> row.probability() * row.weight()).sum() / weights; }
    private static Double weightedStdDev(List<WeightedProbability> rows, Double mean) { if (mean == null || rows.size() < 2) return null; double weights = rows.stream().mapToDouble(WeightedProbability::weight).sum(); return Math.sqrt(rows.stream().mapToDouble(row -> row.weight() * Math.pow(row.probability() - mean, 2)).sum() / weights); }
    private static String normalize(String source) { return source == null ? "UNKNOWN" : source.trim().toUpperCase(Locale.ROOT); }
    private static String prettySource(String source) { return Arrays.stream(source.toLowerCase(Locale.ROOT).split("_" )).map(part -> part.isEmpty() ? part : Character.toUpperCase(part.charAt(0)) + part.substring(1)).reduce((a, b) -> a + " " + b).orElse(source); }
    private static double round4(double value) { return Math.round(value * 10_000.0) / 10_000.0; }
    private static final class SidePair { private OddsSnapshot p1; private OddsSnapshot p2; }
    private record WeightedProbability(double probability, double weight) { }
}
