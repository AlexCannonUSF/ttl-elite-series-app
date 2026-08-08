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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 * <p>This cache bulk-loads a bounded set of recent dates from every
 * rating-snapshot table at boot (via {@link ApplicationReadyEvent}), keyed by
 * {@code playerId → list of snapshots sorted ascending by date}. This avoids
 * both a full-table window sort and hundreds of cold random-index probes.
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
    private static final String SYSTEM_ELO = "ELO";
    private static final String SYSTEM_GLICKO2 = "GLICKO2";
    private static final int RECENT_DATE_BUCKETS = 20;

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
    private final CountDownLatch initialWarmFinished = new CountDownLatch(1);

    /**
     * Default ON: the loaders fetch the most recent bounded date buckets
     * through each table's date index, so the in-memory footprint is bounded
     * by player count and {@link #RECENT_DATE_BUCKETS}, not total history.
     * Without this cache
     * each live-board recompute issues ~50 random-IO queries per matchup
     * against the multi-GB MVStore and predictions never finish — exactly
     * the "model and edges blank" failure mode.
     * <p>An earlier version of this cache loaded full history and OOMed
     * the JVM at ~6.5M rows; that's why the flag exists. The bounded recent
     * history design is safe to leave enabled.
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
     * The loaders issue bounded recent-date reads through each table's date
     * index. They still run off the main thread so IntelliJ can expose startup
     * progress while the OS page cache is cold.
     *
     * <p>Readers wait for this one intentional warm before starting a prediction,
     * preventing concurrent fallback scans from exhausting the connection
     * pool. Once the warm finishes they all use the fast path.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmOnStartup() {
        if (!enabled) {
            log.info("[snapshot-index] disabled via ttl.snapshotIndex.enabled=false");
            initialWarmFinished.countDown();
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
        try {
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
        } finally {
            initialWarmFinished.countDown();
        }
    }

    public boolean isWarmed() {
        return warmed.get();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Wait without polling for the initial rating index to become usable.
     * Callers use this before starting a cold prediction so the application
     * never launches several multi-million-row fallback scans alongside the
     * one intentional cache warm.
     */
    public boolean awaitWarmed(long timeoutMillis) {
        if (warmed.get()) {
            return true;
        }
        if (!enabled) {
            return false;
        }
        try {
            initialWarmFinished.await(Math.max(1L, timeoutMillis), TimeUnit.MILLISECONDS);
            return warmed.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
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
        // Keep a bounded recent history rather than window-sorting the whole
        // table or issuing hundreds of cold random-index probes. H2 can satisfy
        // DISTINCT snapshot_date from its date index, after which this returns
        // only a few thousand rows and Java keeps them grouped by player.
        String sql = "SELECT player_id, snapshot_date, rating_system, rating, rating_deviation, volatility "
                + "FROM rating_snapshot WHERE rating_system = ? AND snapshot_date IN ("
                + " SELECT snapshot_date FROM (SELECT DISTINCT snapshot_date FROM rating_snapshot "
                + " WHERE rating_system = ? ORDER BY snapshot_date DESC LIMIT " + RECENT_DATE_BUCKETS + ")"
                + ") ORDER BY player_id, snapshot_date";
        try {
            for (String ratingSystem : List.of(SYSTEM_ELO, SYSTEM_GLICKO2)) {
                jdbcTemplate.query(sql, rs -> {
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
                    }, ratingSystem, ratingSystem);
            }
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
        String sql = "SELECT player_id, snapshot_date, mu, sigma FROM player_rating_ts2 "
                + "WHERE snapshot_date IN (SELECT snapshot_date FROM (SELECT DISTINCT snapshot_date "
                + "FROM player_rating_ts2 ORDER BY snapshot_date DESC LIMIT " + RECENT_DATE_BUCKETS + ")) "
                + "ORDER BY player_id, snapshot_date";
        try {
            jdbcTemplate.query(sql, rs -> {
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
        String sql = "SELECT player_id, snapshot_date, rating, uncertainty FROM player_rating_wl "
                + "WHERE snapshot_date IN (SELECT snapshot_date FROM (SELECT DISTINCT snapshot_date "
                + "FROM player_rating_wl ORDER BY snapshot_date DESC LIMIT " + RECENT_DATE_BUCKETS + ")) "
                + "ORDER BY player_id, snapshot_date";
        try {
            jdbcTemplate.query(sql, rs -> {
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
