package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.domain.RatingSnapshot;
import com.ttl.tabletennis.dto.EloSyncResultDto;
import com.ttl.tabletennis.repository.PlayerRepository;
import com.ttl.tabletennis.repository.RatingSnapshotRepository;
import com.ttl.tabletennis.util.NameUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TtSeriesEloSyncService {

    private static final Logger log = LoggerFactory.getLogger(TtSeriesEloSyncService.class);
    private static final Pattern RATING_PATTERN = Pattern.compile("(-?\\d+(?:[\\.,]\\d+)?)");
    private static final String RATING_SYSTEM_ELO = "ELO";
    private static final double EPS = 1e-6;

    private final PlayerIdentityService playerIdentityService;
    private final PlayerRepository playerRepository;
    private final RatingSnapshotRepository ratingSnapshotRepository;

    @Value("${ttl.elo.sync.enabled:true}")
    private boolean eloSyncEnabled;

    @Value("${ttl.elo.sync.url:https://www.tt-series.com/ranking/}")
    private String rankingUrl;

    @Value("${ttl.elo.sync.timeoutMs:20000}")
    private int timeoutMs;

    @Value("${ttl.glicko2.defaultRating:1500.0}")
    private double defaultRating;

    public TtSeriesEloSyncService(PlayerIdentityService playerIdentityService,
                                  PlayerRepository playerRepository,
                                  RatingSnapshotRepository ratingSnapshotRepository) {
        this.playerIdentityService = playerIdentityService;
        this.playerRepository = playerRepository;
        this.ratingSnapshotRepository = ratingSnapshotRepository;
    }

    @Transactional
    public EloSyncResultDto syncFromRankingPage() {
        LocalDate snapshotDate = LocalDate.now();
        if (!eloSyncEnabled) {
            return new EloSyncResultDto(
                    true,
                    rankingUrl,
                    snapshotDate,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    List.of(),
                    "ELO sync disabled (ttl.elo.sync.enabled=false)",
                    LocalDateTime.now()
            );
        }

        try {
            int effectiveTimeoutMs = Math.max(2000, timeoutMs);
            Document doc = Jsoup.connect(rankingUrl)
                    .userAgent("Mozilla/5.0 (compatible; TTLEliteSeries/2.0)")
                    .timeout(effectiveTimeoutMs)
                    .get();
            List<RankingRow> rows = parseRankingRows(doc);
            EloSyncResultDto result = syncFromParsedRows(rows, snapshotDate, rankingUrl);
            int fallbackInserted = backfillMissingEloSnapshots(snapshotDate);
            log.info("[elo] ranking sync complete: rows={}, matched={}, inserted={}, updated={}, unchanged={}, unresolved={}",
                    result.rankingRows(),
                    result.matchedPlayers(),
                    result.snapshotsInserted(),
                    result.snapshotsUpdated(),
                    result.unchangedPlayers(),
                    result.unresolvedPlayers());
            if (fallbackInserted > 0) {
                log.info("[elo] filled {} missing ELO snapshots using latest Glicko/default fallback", fallbackInserted);
            }
            return result;
        } catch (IOException e) {
            log.warn("[elo] ranking sync failed: {}", e.getMessage(), e);
            return new EloSyncResultDto(
                    false,
                    rankingUrl,
                    snapshotDate,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    List.of(),
                    "Fetch failed: " + e.getMessage(),
                    LocalDateTime.now()
            );
        } catch (Exception e) {
            log.warn("[elo] ranking sync failed: {}", e.getMessage(), e);
            return new EloSyncResultDto(
                    false,
                    rankingUrl,
                    snapshotDate,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    List.of(),
                    "Sync failed: " + e.getMessage(),
                    LocalDateTime.now()
            );
        }
    }

    @Transactional
    EloSyncResultDto syncFromParsedRows(List<RankingRow> rows, LocalDate snapshotDate, String sourceUrl) {
        LocalDate asOfDate = snapshotDate == null ? LocalDate.now() : snapshotDate;
        List<RankingRow> safeRows = rows == null ? List.of() : rows;

        Map<Long, ResolvedRating> resolvedByPlayerId = new LinkedHashMap<>();
        LinkedHashSet<String> unresolvedNames = new LinkedHashSet<>();

        for (RankingRow row : safeRows) {
            if (row == null || !StringUtils.hasText(row.playerName())) {
                continue;
            }
            Optional<Player> resolved = resolvePlayer(row.playerName());
            if (resolved.isEmpty()) {
                unresolvedNames.add(row.playerName().trim());
                continue;
            }
            Player player = resolved.get();
            resolvedByPlayerId.putIfAbsent(player.getId(), new ResolvedRating(player, row.rating()));
        }

        int inserted = 0;
        int updated = 0;
        int unchanged = 0;

        for (ResolvedRating resolved : resolvedByPlayerId.values()) {
            Player player = resolved.player();
            double rating = resolved.rating();

            Optional<RatingSnapshot> sameDay = ratingSnapshotRepository
                    .findByPlayerIdAndSnapshotDateAndRatingSystem(player.getId(), asOfDate, RATING_SYSTEM_ELO);
            if (sameDay.isPresent()) {
                RatingSnapshot snapshot = sameDay.get();
                if (Math.abs(snapshot.getRating() - rating) <= EPS) {
                    unchanged++;
                } else {
                    snapshot.setRating(rating);
                    snapshot.setRatingDeviation(null);
                    snapshot.setVolatility(null);
                    ratingSnapshotRepository.save(snapshot);
                    updated++;
                }
                continue;
            }

            Optional<RatingSnapshot> latest = ratingSnapshotRepository
                    .findTopByPlayerIdAndRatingSystemAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(
                            player.getId(),
                            RATING_SYSTEM_ELO,
                            asOfDate
                    );
            if (latest.isPresent() && Math.abs(latest.get().getRating() - rating) <= EPS) {
                unchanged++;
                continue;
            }

            RatingSnapshot snapshot = new RatingSnapshot();
            snapshot.setPlayer(player);
            snapshot.setSnapshotDate(asOfDate);
            snapshot.setRating(rating);
            snapshot.setRatingDeviation(null);
            snapshot.setVolatility(null);
            snapshot.setRatingSystem(RATING_SYSTEM_ELO);
            ratingSnapshotRepository.save(snapshot);
            inserted++;
        }

        String message = String.format(
                Locale.ROOT,
                "Processed %d ranking rows; matched %d players, inserted %d, updated %d, unchanged %d, unresolved %d",
                safeRows.size(),
                resolvedByPlayerId.size(),
                inserted,
                updated,
                unchanged,
                unresolvedNames.size()
        );

        List<String> unresolvedSample = unresolvedNames.stream().limit(25).toList();
        return new EloSyncResultDto(
                true,
                sourceUrl,
                asOfDate,
                safeRows.size(),
                resolvedByPlayerId.size(),
                inserted,
                updated,
                unchanged,
                unresolvedNames.size(),
                unresolvedSample,
                message,
                LocalDateTime.now()
        );
    }

    @Transactional
    int backfillMissingEloSnapshots(LocalDate asOfDate) {
        LocalDate snapshotDate = asOfDate == null ? LocalDate.now() : asOfDate;
        int inserted = 0;
        List<Player> players = playerRepository.findAll();
        for (Player player : players) {
            if (player == null || player.getId() == null) {
                continue;
            }

            boolean hasElo = ratingSnapshotRepository
                    .findTopByPlayerIdAndRatingSystemAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(
                            player.getId(),
                            RATING_SYSTEM_ELO,
                            snapshotDate
                    )
                    .isPresent();
            if (hasElo) {
                continue;
            }

            double fallbackRating = ratingSnapshotRepository
                    .findTopByPlayerIdAndRatingSystemAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(
                            player.getId(),
                            "GLICKO2",
                            snapshotDate
                    )
                    .map(RatingSnapshot::getRating)
                    .orElse(defaultRating);

            RatingSnapshot snapshot = new RatingSnapshot();
            snapshot.setPlayer(player);
            snapshot.setSnapshotDate(snapshotDate);
            snapshot.setRating(fallbackRating);
            snapshot.setRatingDeviation(null);
            snapshot.setVolatility(null);
            snapshot.setRatingSystem(RATING_SYSTEM_ELO);
            ratingSnapshotRepository.save(snapshot);
            inserted++;
        }
        return inserted;
    }

    List<RankingRow> parseRankingRows(Document doc) {
        if (doc == null) {
            return List.of();
        }
        List<RankingRow> out = new ArrayList<>();
        Elements rows = doc.select("table tr");
        for (Element tr : rows) {
            Elements tds = tr.select("td");
            if (tds.size() < 3) {
                continue;
            }
            String playerName = tds.get(1).text();
            Double rating = parseRatingValue(tds.get(2).text());
            if (!StringUtils.hasText(playerName) || rating == null) {
                continue;
            }
            out.add(new RankingRow(playerName.trim(), rating));
        }
        return out;
    }

    private Optional<Player> resolvePlayer(String rawName) {
        Optional<Player> direct = playerIdentityService.findCanonicalPlayer(rawName);
        if (direct.isPresent()) {
            return direct;
        }

        String[] split = NameUtils.splitFirstLast(rawName);
        if (!StringUtils.hasText(split[0]) || !StringUtils.hasText(split[1])) {
            return Optional.empty();
        }
        String swapped = split[1] + " " + split[0];
        return playerIdentityService.findCanonicalPlayer(swapped);
    }

    private Double parseRatingValue(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String cleaned = raw.replace('\u00A0', ' ').trim();
        Matcher matcher = RATING_PATTERN.matcher(cleaned);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1).replace(',', '.');
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record RankingRow(String playerName, double rating) {
    }

    private record ResolvedRating(Player player, double rating) {
    }
}
