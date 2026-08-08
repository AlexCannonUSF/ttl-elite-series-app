package com.ttl.tabletennis.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotIndexCacheTests {

    @Test
    void indexedWarmKeepsOnlyTheLatestRatingRows() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:snapshot-index-cache;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE players (id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE rating_snapshot (player_id BIGINT, snapshot_date DATE, rating_system VARCHAR(32), rating DOUBLE, rating_deviation DOUBLE, volatility DOUBLE)");
        jdbc.execute("CREATE INDEX idx_rating_test ON rating_snapshot(player_id, rating_system, snapshot_date)");
        jdbc.execute("CREATE TABLE player_rating_ts2 (player_id BIGINT, snapshot_date DATE, mu DOUBLE, sigma DOUBLE)");
        jdbc.execute("CREATE INDEX idx_ts2_test ON player_rating_ts2(player_id, snapshot_date)");
        jdbc.execute("CREATE TABLE player_rating_wl (player_id BIGINT, snapshot_date DATE, rating DOUBLE, uncertainty DOUBLE)");
        jdbc.execute("CREATE INDEX idx_wl_test ON player_rating_wl(player_id, snapshot_date)");

        jdbc.update("INSERT INTO players(id) VALUES (1), (2)");
        jdbc.update("INSERT INTO rating_snapshot VALUES (1, '2026-01-01', 'ELO', 1400, NULL, NULL), (1, '2026-02-01', 'ELO', 1510, NULL, NULL), (1, '2026-02-01', 'GLICKO2', 1530, 62, 0.05)");
        jdbc.update("INSERT INTO player_rating_ts2 VALUES (1, '2026-01-01', 22, 7), (1, '2026-02-01', 27, 5)");
        jdbc.update("INSERT INTO player_rating_wl VALUES (1, '2026-01-01', -0.2, 0.4), (1, '2026-02-01', 0.3, 0.2)");

        SnapshotIndexCache cache = new SnapshotIndexCache(jdbc);
        ReflectionTestUtils.setField(cache, "enabled", true);
        cache.refresh();

        assertTrue(cache.isWarmed());
        assertTrue(cache.awaitWarmed(10));
        assertEquals(1510.0, cache.findTopRating(1L, "ELO", null).orElseThrow().rating());
        assertEquals(1530.0, cache.findTopRating(1L, "GLICKO2", null).orElseThrow().rating());
        assertEquals(27.0, cache.findTopTs2(1L, null).orElseThrow().mu());
        assertEquals(0.3, cache.findTopWl(1L, null).orElseThrow().rating());
        assertTrue(cache.findTopWl(2L, null).isEmpty());
    }
}
