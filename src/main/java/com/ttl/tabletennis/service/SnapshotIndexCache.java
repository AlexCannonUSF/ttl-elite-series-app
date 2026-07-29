package com.ttl.tabletennis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-local read-through cache for the three rating-snapshot tables.
 *
 * <p>{@link FeatureService#resolveSnapshotBundle} originally issued 4
 * "find latest snapshot <= asOfDate" queries per player per matchup. On a
 * 670 MB H2 file with a cold OS page cache, each lookup did several random
 * 4 KB page reads — fast individually, but multiplied by ~30 players and 4
 * rating systems on every live-board recompute, requests took 10+ minutes.
 *
 * <p>This cache bulk-loads every rating-snapshot table into memory once at
 * boot (via {@link ApplicationReadyEvent}), keyed by {@code playerId → list
 * of snapshots sorted ascending by date}. Lookups are a HashMap fetch + a
 * binary search — sub-microsecond. Memory footprint is ~10 MB for the
 * working data set (≈500 players × ≈30 dates × 4 systems × ≈50 bytes).
 *
 * <p>The cache is intentionally read-only once warmed. New snapshots
 * written by the rating-rebuild path will not be visible until the next
 * application restart. Live-board predictions only need same-day or
 * recent-day ratings, so the staleness window is acceptable in practice.
 * Tests that bypass startup events can force a refresh via
 * {@link #refresh()}.
 */
@Service
public class SnapshotIndexCache {

    private static final Logger log = LoggerFactory.getLogger(SnapshotIndexCache.class);

    /**
     * A rating_snapshot row stripped of JPA overhead — just the fields
     * {@link FeatureService} actually reads. Sorted lists of these are
     * what we keep per player.
     */
    public record RatingRow(LocalDate snapshotDate,
                            String ratingSystem,
                            double rating,
                            Double ratingDeviation,
                            Double volatility) { }

    public record Ts2Row(LocalDate snapshotDate, double mu, double sigma) { }

    public record WlRow(LocalDate snapshotDate, double rating, double uncertainty) { }

    private final JdbcTemplate jdbcTemplate;

    /** {@code playerId → snapshots sorted ascending by date}. */
    private final Map<Long, List<RatingRow>> ratingSnapshotsByPlayer = new ConcurrentHashMap<>();
    private final Map<Long, List<Ts2Row>> ts2ByPlayer = new ConcurrentHashMap<>();
    private final Map<Long, List<WlRow>> wlByPlayer = new ConcurrentHashMap<>();

    private final AtomicBoolean warmed = new AtomicBoolean(false);

    /**
     * Default ON: the loaders now fetch only the LATEST snapshot per
     * (player_id, rating_system) via a ROW_NUMBER() OVER PARTITION BY query,
     * so the in-memory footprint is bounded by the player count (~500 × 4
     * rating systems ≈ 2k rows), not by history depth. Without this cache
     * each live-board recompute issues ~50 random-IO queries per matchup
     * against the multi-GB MVStore and predictions never finish — exactly
     * the "model and edges blank" failure mode.
     * <p>An earlier version of this cache loaded full history and OOMed
     * the JVM at ~6.5M rows; that's why the flag exists. The latest-row
     * design is safe to leave enabled.
     */
    @Value("${ttl.snapshotIndex.enabled:true}")
    private boolean enabled;

    public SnapshotIndexCache(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Warm the cache once the application context is ready.
     *
     * <p>The warm itself runs on a single-thread background executor so it
     * does <strong>not</strong> block the main thread / Spring Boot startup.
     * On a multi-GB MVStore the {@code ROW_NUMBER() OVER (PARTITION BY ...)}
     * scans across {@code player_rating_ts2} + {@code player_rating_wl} can
     * take up to a minute even when each only returns ~500 rows, because H2
     * still has to walk every page. Blocking startup on that meant IntelliJ
     * runs sat at "Started..." for 60–90 seconds; making it async drops
     * apparent boot time back to ~10s.
     *
     * <p>It is safe to leave the cache cold for a short window: every reader
     * (currently only {@link FeatureService#resolveSnapshotBundle}) checks
     * {@link #isWarmed()} first and falls back to the JPA repository when it
     * returns false. Early predictions therefore pay the per-call JPA price;
     * once the warm finishes they all flip to the fast path.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmOnStartup() {
        if (!enabled) {
            log.info("[snapshot-index] disabled via ttl.snapshotIndex.enabled=false");
            return;
        }
        Thread warmer = new Thread(this::refreshQuietly, "snapshot-index-warmer");
        warmer.setDaemon(true);
        warmer.start();
    }

    private void refreshQuietly() {
        try {
            refresh();
        } catch (RuntimeException ex) {
            log.warn("[snapshot-index] background warm failed; lookups will fall through to JPA", ex);
        }
    }

    public synchronized void refresh() {
        long start = System.currentTimeMillis();
        ratingSnapshotsByPlayer.clear();
        ts2ByPlayer.clear();
        wlByPlayer.clear();

        long ratingRows = loadRatingSnapshots();
        long ts2Rows = loadTs2();
        long wlRows = loadWl();

        warmed.set(true);
        long elapsed = System.currentTimeMillis() - start;
        log.info("[snapshot-index] warmed in {} ms — rating_snapshot={} player_rating_ts2={} player_rating_wl={} unique_players(ratings)={} unique_players(ts2)={} unique_players(wl)={}",
                elapsed,
                ratingRows,
                ts2Rows,
                wlRows,
                ratingSnapshotsByPlayer.size(),
                ts2ByPlayer.size(),
                wlByPlayer.size());
    }

    public boolean isWarmed() {
        return warmed.get();
    }

    /**
     * Equivalent to
     * {@code RatingSnapshotRepository.findTopByPlayerIdAndRatingSystemAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc}
     * but served from memory.
     */
    public Optional<RatingRow> findTopRating(Long playerId, String ratingSystem, LocalDate asOf) {
        if (!warmed.get() || playerId == null || ratingSystem == null) {
            return Optional.empty();
        }
        List<RatingRow> rows = ratingSnapshotsByPlayer.get(playerId);
        if (rows == null || rows.isEmpty()) {
            return Optional.empty();
        }
        // Rows are sorted ascending by snapshotDate within the player. Walk
        // backwards from newest to find the most recent matching system <= asOf.
        for (int i = rows.size() - 1; i >= 0; i--) {
            RatingRow r = rows.get(i);
            if (!ratingSystem.equals(r.ratingSystem())) continue;
            if (asOf == null || !r.snapshotDate().isAfter(asOf)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    public Optional<Ts2Row> findTopTs2(Long playerId, LocalDate asOf) {
        return findTopByDate(ts2ByPlayer, playerId, asOf, Ts2Row::snapshotDate);
    }

    public Optional<WlRow> findTopWl(Long playerId, LocalDate asOf) {
        return findTopByDate(wlByPlayer, playerId, asOf, WlRow::snapshotDate);
    }

    private <T> Optional<T> findTopByDate(Map<Long, List<T>> byPlayer,
                                          Long playerId,
                                          LocalDate asOf,
                                          java.util.function.Function<T, LocalDate> getDate) {
        if (!warmed.get() || playerId == null) {
            return Optional.empty();
        }
        List<T> rows = byPlayer.get(playerId);
        if (rows == null || rows.isEmpty()) {
            return Optional.empty();
        }
        for (int i = rows.size() - 1; i >= 0; i--) {
            T r = rows.get(i);
            if (asOf == null || !getDate.apply(r).isAfter(asOf)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    private long loadRatingSnapshots() {
        Map<Long, List<RatingRow>> staging = new HashMap<>();
        // Load only the LATEST snapshot per (player_id, rating_system). On a
        // multi-GB MVStore loading every row hammers the OS page cache for
        // minutes; for the live-board use case we only ever need "most recent
        // <= today", so one row per group is sufficient. Historical asOf
        // lookups (asOf before the cached date) return Optional.empty() and
        // callers fall through to the JPA repository.
        String sql = "SELECT player_id, snapshot_date, rating_system, rating, rating_deviation, volatility FROM ("
                + "  SELECT player_id, snapshot_date, rating_system, rating, rating_deviation, volatility,"
                + "         ROW_NUMBER() OVER (PARTITION BY player_id, rating_system ORDER BY snapshot_date DESC) AS rn"
                + "    FROM rating_snapshot"
                + ") WHERE rn = 1";
        try {
            jdbcTemplate.query(
                    sql,
                    rs -> {
                        Long playerId = rs.getLong("player_id");
                        Date d = rs.getDate("snapshot_date");
                        LocalDate snapshotDate = d == null ? null : d.toLocalDate();
                        String system = rs.getString("rating_system");
                        double rating = rs.getDouble("rating");
                        Double rd = rs.getObject("rating_deviation", Double.class);
                        Double vol = rs.getObject("volatility", Double.class);
                        if (snapshotDate == null || system == null) return;
                        staging.computeIfAbsent(playerId, k -> new ArrayList<>())
                                .add(new RatingRow(snapshotDate, system, rating, rd, vol));
                    });
        } catch (Exception ex) {
            log.warn("[snapshot-index] failed to load rating_snapshot: {}", ex.toString());
            return 0;
        }
        long total = 0;
        for (Map.Entry<Long, List<RatingRow>> e : staging.entrySet()) {
            List<RatingRow> sorted = new ArrayList<>(e.getValue());
            sorted.sort(Comparator.comparing(RatingRow::snapshotDate));
            ratingSnapshotsByPlayer.put(e.getKey(), List.copyOf(sorted));
            total += sorted.size();
        }
        return total;
    }

    private long loadTs2() {
        Map<Long, List<Ts2Row>> staging = new HashMap<>();
        // Latest TS2 snapshot per player (see loadRatingSnapshots for rationale).
        String sql = "SELECT player_id, snapshot_date, mu, sigma FROM ("
                + "  SELECT player_id, snapshot_date, mu, sigma,"
                + "         ROW_NUMBER() OVER (PARTITION BY player_id ORDER BY snapshot_date DESC) AS rn"
                + "    FROM player_rating_ts2"
                + ") WHERE rn = 1";
        try {
            jdbcTemplate.query(
                    sql,
                    rs -> {
                        Long playerId = rs.getLong("player_id");
                        Date d = rs.getDate("snapshot_date");
                        LocalDate snapshotDate = d == null ? null : d.toLocalDate();
                        double mu = rs.getDouble("mu");
                        double sigma = rs.getDouble("sigma");
                        if (snapshotDate == null) return;
                        staging.computeIfAbsent(playerId, k -> new ArrayList<>())
                                .add(new Ts2Row(snapshotDate, mu, sigma));
                    });
        } catch (Exception ex) {
            log.warn("[snapshot-index] failed to load player_rating_ts2: {}", ex.toString());
            return 0;
        }
        long total = 0;
        for (Map.Entry<Long, List<Ts2Row>> e : staging.entrySet()) {
            List<Ts2Row> sorted = new ArrayList<>(e.getValue());
            sorted.sort(Comparator.comparing(Ts2Row::snapshotDate));
            ts2ByPlayer.put(e.getKey(), List.copyOf(sorted));
            total += sorted.size();
        }
        return total;
    }

    private long loadWl() {
        Map<Long, List<WlRow>> staging = new HashMap<>();
        // Latest WL snapshot per player (see loadRatingSnapshots for rationale).
        String sql = "SELECT player_id, snapshot_date, rating, uncertainty FROM ("
                + "  SELECT player_id, snapshot_date, rating, uncertainty,"
                + "         ROW_NUMBER() OVER (PARTITION BY player_id ORDER BY snapshot_date DESC) AS rn"
                + "    FROM player_rating_wl"
                + ") WHERE rn = 1";
        try {
            jdbcTemplate.query(
                    sql,
                    rs -> {
                        Long playerId = rs.getLong("player_id");
                        Date d = rs.getDate("snapshot_date");
                        LocalDate snapshotDate = d == null ? null : d.toLocalDate();
                        double rating = rs.getDouble("rating");
                        double uncertainty = rs.getDouble("uncertainty");
                        if (snapshotDate == null) return;
                        staging.computeIfAbsent(playerId, k -> new ArrayList<>())
                                .add(new WlRow(snapshotDate, rating, uncertainty));
                    });
        } catch (Exception ex) {
            log.warn("[snapshot-index] failed to load player_rating_wl: {}", ex.toString());
            return 0;
        }
        long total = 0;
        for (Map.Entry<Long, List<WlRow>> e : staging.entrySet()) {
            List<WlRow> sorted = new ArrayList<>(e.getValue());
            sorted.sort(Comparator.comparing(WlRow::snapshotDate));
            wlByPlayer.put(e.getKey(), List.copyOf(sorted));
            total += sorted.size();
        }
        return total;
    }

}
