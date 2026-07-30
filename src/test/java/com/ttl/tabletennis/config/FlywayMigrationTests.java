package com.ttl.tabletennis.config;

import db.migration.V20260729001__phase_01_evidence_integrity;
import db.migration.V20260729002__phase_02_score_backed_settlement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlywayMigrationTests {

    @Test
    void phase00MigrationCreatesShadowTablesAndAddsCorrelationColumns() throws Exception {
        String url = "jdbc:h2:mem:flyway-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE scrape_run (id BIGINT AUTO_INCREMENT PRIMARY KEY)");
                statement.execute("CREATE TABLE scrape_error (id BIGINT AUTO_INCREMENT PRIMARY KEY)");
                statement.execute("CREATE TABLE odds_quote (id BIGINT AUTO_INCREMENT PRIMARY KEY)");
                statement.execute("CREATE TABLE paper_trade_decision_sample (id BIGINT AUTO_INCREMENT PRIMARY KEY)");
                statement.execute("CREATE TABLE paper_trade_learning_sample (id BIGINT AUTO_INCREMENT PRIMARY KEY)");
                statement.execute("CREATE TABLE value_opportunity (id BIGINT AUTO_INCREMENT PRIMARY KEY)");
                statement.execute("CREATE TABLE tracked_match_observation (id BIGINT AUTO_INCREMENT PRIMARY KEY)");
            }
        }

        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .baselineOnMigrate(true)
                .cleanDisabled(false)
                .locations("classpath:db/migration")
                .load();

        assertTrue(flyway.migrate().migrationsExecuted >= 2);

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            DatabaseMetaData metaData = connection.getMetaData();
            assertTrue(tableExists(metaData, "flyway_schema_history"));
            assertTrue(tableExists(metaData, "paper_trade_session_shadow"));
            assertTrue(tableExists(metaData, "paper_trade_bet_shadow"));
            assertTrue(tableExists(metaData, "settlement_diff_log"));

            assertTrue(columnExists(metaData, "scrape_run", "correlation_id"));
            assertTrue(columnExists(metaData, "scrape_error", "correlation_id"));
            assertTrue(columnExists(metaData, "odds_quote", "correlation_id"));
            assertTrue(columnExists(metaData, "paper_trade_decision_sample", "correlation_id"));
            assertTrue(columnExists(metaData, "paper_trade_learning_sample", "correlation_id"));
            assertTrue(columnExists(metaData, "value_opportunity", "correlation_id"));
            assertTrue(columnExists(metaData, "tracked_match_observation", "correlation_id"));
            assertTrue(columnExists(metaData, "paper_trade_session_shadow", "source_session_id"));
            assertTrue(columnExists(metaData, "paper_trade_bet_shadow", "source_bet_id"));
            assertTrue(columnExists(metaData, "paper_trade_session_shadow", "mirrored_at"));
            assertTrue(columnExists(metaData, "paper_trade_bet_shadow", "mirrored_at"));
        }

        assertEquals(0, flyway.migrate().migrationsExecuted);
        flyway.clean();
    }

    @Test
    void phase01MigrationCreatesIngestionAndObservationTables() throws Exception {
        String url = "jdbc:h2:mem:flyway-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .baselineOnMigrate(true)
                .cleanDisabled(false)
                .locations("classpath:db/migration")
                .load();

        assertTrue(flyway.migrate().migrationsExecuted >= 3);

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            DatabaseMetaData metaData = connection.getMetaData();

            assertTrue(tableExists(metaData, "odds_snapshot"));
            assertTrue(tableExists(metaData, "mirror_observation"));
            assertTrue(tableExists(metaData, "stream_observation"));
            assertTrue(tableExists(metaData, "feed_health_sample"));
            assertTrue(tableExists(metaData, "ingest_dlq"));

            assertTrue(columnExists(metaData, "odds_snapshot", "tracked_event_id"));
            assertTrue(columnExists(metaData, "odds_snapshot", "correlation_id"));
            assertTrue(columnExists(metaData, "mirror_observation", "payload_json"));
            assertTrue(columnExists(metaData, "mirror_observation", "completion_signal"));
            assertTrue(columnExists(metaData, "stream_observation", "frame_ref"));
            assertTrue(columnExists(metaData, "stream_observation", "vlm_fallback_used"));
            assertTrue(columnExists(metaData, "feed_health_sample", "rolling_p50_latency_ms"));
            assertTrue(columnExists(metaData, "feed_health_sample", "rolling_p95_latency_ms"));
            assertTrue(columnExists(metaData, "ingest_dlq", "payload_json"));
            assertTrue(columnExists(metaData, "ingest_dlq", "next_retry_at"));

            assertTrue(indexExists(metaData, "odds_snapshot", "idx_odds_snapshot_event_time"));
            assertTrue(indexExists(metaData, "mirror_observation", "idx_mirror_observation_event_time"));
            assertTrue(indexExists(metaData, "stream_observation", "idx_stream_observation_event_time"));
            assertTrue(indexExists(metaData, "feed_health_sample", "idx_feed_health_sample_source_observed"));
            assertTrue(indexExists(metaData, "ingest_dlq", "idx_ingest_dlq_next_retry"));
        }

        assertEquals(0, flyway.migrate().migrationsExecuted);
        flyway.clean();
    }

    @Test
    void phase02MigrationCreatesScoreTruthPersistenceTables() throws Exception {
        String url = "jdbc:h2:mem:flyway-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .baselineOnMigrate(true)
                .cleanDisabled(false)
                .locations("classpath:db/migration")
                .load();

        assertTrue(flyway.migrate().migrationsExecuted >= 4);

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            DatabaseMetaData metaData = connection.getMetaData();

            assertTrue(tableExists(metaData, "settlement_evidence"));
            assertTrue(tableExists(metaData, "contradiction"));
            assertTrue(tableExists(metaData, "settlement_audit"));

            assertTrue(columnExists(metaData, "settlement_evidence", "tracked_event_id"));
            assertTrue(columnExists(metaData, "settlement_evidence", "payload_json"));
            assertTrue(columnExists(metaData, "settlement_evidence", "correlation_id"));
            assertTrue(columnExists(metaData, "contradiction", "evidence_id"));
            assertTrue(columnExists(metaData, "contradiction", "payload_json"));
            assertTrue(columnExists(metaData, "contradiction", "resolved"));
            assertTrue(columnExists(metaData, "settlement_audit", "tracked_event_id"));
            assertTrue(columnExists(metaData, "settlement_audit", "payload_json"));
            assertTrue(columnExists(metaData, "settlement_audit", "evidence_id"));

            assertTrue(indexExists(metaData, "settlement_evidence", "idx_settlement_evidence_bet_asof"));
            assertTrue(indexExists(metaData, "settlement_evidence", "idx_settlement_evidence_event_asof"));
            assertTrue(indexExists(metaData, "contradiction", "idx_contradiction_bet_observed"));
            assertTrue(indexExists(metaData, "contradiction", "idx_contradiction_evidence"));
            assertTrue(indexExists(metaData, "settlement_audit", "idx_settlement_audit_bet_decided"));
            assertTrue(indexExists(metaData, "settlement_audit", "idx_settlement_audit_event_decided"));
        }

        assertEquals(0, flyway.migrate().migrationsExecuted);
        flyway.clean();
    }

    @Test
    void phase03MigrationAddsPendingEvidenceHoldOpenColumnsAndIndexes() throws Exception {
        String url = "jdbc:h2:mem:flyway-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE paper_trade_bet (
                            id BIGINT AUTO_INCREMENT PRIMARY KEY,
                            status VARCHAR(16) NOT NULL
                        )
                        """);
            }
        }

        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .baselineOnMigrate(true)
                .cleanDisabled(false)
                .locations("classpath:db/migration")
                .load();

        assertTrue(flyway.migrate().migrationsExecuted >= 6);

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            DatabaseMetaData metaData = connection.getMetaData();

            assertTrue(tableExists(metaData, "paper_trade_bet"));
            assertTrue(tableExists(metaData, "paper_trade_bet_shadow"));
            assertTrue(tableExists(metaData, "settlement_policy_audit"));

            assertPendingEvidenceColumns(metaData, "paper_trade_bet");
            assertPendingEvidenceColumns(metaData, "paper_trade_bet_shadow");

            assertTrue(indexExists(metaData, "paper_trade_bet", "idx_paper_bet_pending_poll"));
            assertTrue(indexExists(metaData, "paper_trade_bet", "idx_paper_bet_pending_until"));
            assertTrue(indexExists(metaData, "paper_trade_bet_shadow", "idx_paper_bet_shadow_pending_poll"));
            assertTrue(indexExists(metaData, "paper_trade_bet_shadow", "idx_paper_bet_shadow_pending_until"));
            assertTrue(indexExists(metaData, "settlement_policy_audit", "idx_settlement_policy_audit_policy_time"));
            assertTrue(indexExists(metaData, "settlement_policy_audit", "idx_settlement_policy_audit_status_time"));
        }

        assertEquals(0, flyway.migrate().migrationsExecuted);
        flyway.clean();
    }

    @Test
    void phase03MigrationCreatesStreamWorkerTables() throws Exception {
        String url = "jdbc:h2:mem:flyway-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .baselineOnMigrate(true)
                .cleanDisabled(false)
                .locations("classpath:db/migration")
                .load();

        assertTrue(flyway.migrate().migrationsExecuted >= 7);

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            DatabaseMetaData metaData = connection.getMetaData();

            assertTrue(tableExists(metaData, "stream_worker_config"));
            assertTrue(tableExists(metaData, "stream_worker_health_1m"));
            assertTrue(tableExists(metaData, "stream_route"));

            assertStreamWorkerConfigColumns(metaData);
            assertStreamWorkerHealthColumns(metaData);
            assertStreamRouteColumns(metaData);

            assertTrue(indexExists(metaData, "stream_worker_config", "idx_stream_worker_config_state"));
            assertTrue(indexExists(metaData, "stream_worker_config", "idx_stream_worker_config_updated"));
            assertTrue(indexExists(metaData, "stream_worker_health_1m", "idx_stream_worker_health_bucket"));
            assertTrue(indexExists(metaData, "stream_worker_health_1m", "idx_stream_worker_health_match_bucket"));
            assertTrue(indexExists(metaData, "stream_route", "idx_stream_route_event_table"));
            assertTrue(indexExists(metaData, "stream_route", "idx_stream_route_platform"));
            assertTrue(indexExists(metaData, "stream_route", "idx_stream_route_updated"));
        }

        assertEquals(0, flyway.migrate().migrationsExecuted);
        flyway.clean();
    }

    @Test
    void phase01EvidenceIntegrityMigrationAddsProvenanceAndPreservesLegacyReviewState() throws Exception {
        String url = "jdbc:h2:mem:phase-01-integrity-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE settlement_evidence (id BIGINT AUTO_INCREMENT PRIMARY KEY)");
            statement.execute("""
                    CREATE TABLE settlement_audit (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        bet_id BIGINT NOT NULL,
                        decision VARCHAR(24) NOT NULL,
                        payload_json LONGTEXT NOT NULL,
                        decided_at TIMESTAMP NOT NULL
                    )
                    """);
            statement.execute("CREATE TABLE settlement_diff_log (id BIGINT AUTO_INCREMENT PRIMARY KEY)");
            statement.execute("CREATE TABLE paper_trade_bet (id BIGINT AUTO_INCREMENT PRIMARY KEY)");
            statement.execute("CREATE TABLE paper_trade_learning_sample (id BIGINT AUTO_INCREMENT PRIMARY KEY)");
            statement.execute("""
                    INSERT INTO settlement_audit (id, bet_id, decision, payload_json, decided_at)
                    VALUES
                      (10, 100, 'MANUAL_REVIEW', '{}', TIMESTAMP '2026-07-29 18:00:00'),
                      (11, 100, 'MANUAL_REVIEW_ACCEPTED', '{"reviewDecisionId":10}', TIMESTAMP '2026-07-29 18:01:00'),
                      (12, 101, 'MANUAL_REVIEW', '{}', TIMESTAMP '2026-07-29 18:00:00'),
                      (13, 101, 'MANUAL_REVIEW', '{}', TIMESTAMP '2026-07-29 18:01:00'),
                      (14, 102, 'MANUAL_REVIEW', '{}', TIMESTAMP '2026-07-29 18:00:00'),
                      (15, 102, 'SETTLE', '{}', TIMESTAMP '2026-07-29 18:01:00')
                    """);

            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            V20260729001__phase_01_evidence_integrity migration =
                    new V20260729001__phase_01_evidence_integrity();

            migration.migrate(context);
            migration.migrate(context);

            DatabaseMetaData metaData = connection.getMetaData();
            assertTrue(columnExists(metaData, "settlement_evidence", "evidence_fingerprint"));
            assertTrue(columnExists(metaData, "settlement_audit", "decision_fingerprint"));
            assertTrue(columnExists(metaData, "settlement_audit", "review_status"));
            assertTrue(columnExists(metaData, "settlement_audit", "review_decision_id"));
            assertTrue(columnExists(metaData, "settlement_diff_log", "diff_fingerprint"));
            assertTrue(columnExists(metaData, "paper_trade_bet", "settlement_confidence"));
            assertTrue(columnExists(metaData, "paper_trade_bet", "settlement_evidence_fingerprint"));
            assertTrue(columnExists(metaData, "paper_trade_bet", "closing_decimal_odds"));
            assertTrue(columnExists(metaData, "paper_trade_bet", "closing_source"));
            assertTrue(columnExists(metaData, "paper_trade_learning_sample", "closing_source"));
            assertTrue(indexExists(metaData, "settlement_evidence", "uq_settlement_evidence_fingerprint"));
            assertTrue(indexExists(metaData, "settlement_audit", "uq_settlement_audit_fingerprint"));
            assertTrue(indexExists(metaData, "settlement_audit", "idx_settlement_audit_review"));
            assertTrue(indexExists(metaData, "settlement_diff_log", "uq_settlement_diff_fingerprint"));

            try (ResultSet reviews = statement.executeQuery("""
                    SELECT id, review_status, review_decision_id
                    FROM settlement_audit
                    ORDER BY id
                    """)) {
                assertTrue(reviews.next());
                assertEquals(10L, reviews.getLong("id"));
                assertEquals("ACCEPTED", reviews.getString("review_status"));
                assertTrue(reviews.next());
                assertEquals(11L, reviews.getLong("id"));
                assertEquals(10L, reviews.getLong("review_decision_id"));
                assertTrue(reviews.next());
                assertEquals(12L, reviews.getLong("id"));
                assertEquals("SUPERSEDED", reviews.getString("review_status"));
                assertTrue(reviews.next());
                assertEquals(13L, reviews.getLong("id"));
                assertEquals("OPEN", reviews.getString("review_status"));
                assertTrue(reviews.next());
                assertEquals(14L, reviews.getLong("id"));
                assertEquals("RESOLVED", reviews.getString("review_status"));
            }
        }
    }

    @Test
    void phase02ScoreBackedMigrationAddsQualityEvidenceIdempotently() throws Exception {
        String url = "jdbc:h2:mem:phase-02-score-backed-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE paper_trade_bet (id BIGINT AUTO_INCREMENT PRIMARY KEY)");
            statement.execute("""
                    CREATE TABLE settlement_evidence (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        bundle_as_of TIMESTAMP NOT NULL
                    )
                    """);

            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            V20260729002__phase_02_score_backed_settlement migration =
                    new V20260729002__phase_02_score_backed_settlement();

            migration.migrate(context);
            migration.migrate(context);

            DatabaseMetaData metaData = connection.getMetaData();
            assertTrue(columnExists(metaData, "paper_trade_bet", "score_evidence_quality"));
            assertTrue(columnExists(metaData, "paper_trade_bet", "score_evidence_finality"));
            assertTrue(columnExists(metaData, "paper_trade_bet", "score_evidence_confidence"));
            assertTrue(columnExists(metaData, "paper_trade_bet", "score_evidence_latest_score"));
            assertTrue(columnExists(metaData, "paper_trade_bet", "score_evidence_contradictory"));
            assertTrue(columnExists(metaData, "settlement_evidence", "score_evidence_quality"));
            assertTrue(columnExists(metaData, "settlement_evidence", "score_evidence_finality"));
            assertTrue(columnExists(metaData, "settlement_evidence", "score_inferred_winner_id"));
            assertTrue(indexExists(metaData, "paper_trade_bet", "idx_paper_bet_score_evidence"));
            assertTrue(indexExists(metaData, "settlement_evidence", "idx_settlement_evidence_score_quality"));
        }
    }

    private boolean tableExists(DatabaseMetaData metaData, String tableName) throws Exception {
        try (ResultSet rs = metaData.getTables(null, null, null, new String[]{"TABLE"})) {
            while (rs.next()) {
                if (matches(rs.getString("TABLE_NAME"), tableName)) {
                    return true;
                }
            }
            return false;
        }
    }

    private boolean columnExists(DatabaseMetaData metaData, String tableName, String columnName) throws Exception {
        try (ResultSet rs = metaData.getColumns(null, null, null, null)) {
            while (rs.next()) {
                if (matches(rs.getString("TABLE_NAME"), tableName)
                        && matches(rs.getString("COLUMN_NAME"), columnName)) {
                    return true;
                }
            }
            return false;
        }
    }

    private boolean indexExists(DatabaseMetaData metaData, String tableName, String indexName) throws Exception {
        String lookupTable = tableName == null ? null : tableName.toUpperCase(Locale.ROOT);
        try (ResultSet rs = metaData.getIndexInfo(null, null, lookupTable, false, false)) {
            while (rs.next()) {
                if (matches(rs.getString("TABLE_NAME"), tableName)
                        && matches(rs.getString("INDEX_NAME"), indexName)) {
                    return true;
                }
            }
            return false;
        }
    }

    private void assertPendingEvidenceColumns(DatabaseMetaData metaData, String tableName) throws Exception {
        assertTrue(columnExists(metaData, tableName, "pending_evidence_until"));
        assertTrue(columnExists(metaData, tableName, "pending_evidence_next_poll_at"));
        assertTrue(columnExists(metaData, tableName, "pending_evidence_reason"));
        assertTrue(columnExists(metaData, tableName, "pending_evidence_note"));
        assertTrue(columnExists(metaData, tableName, "pending_evidence_updated_at"));
    }

    private void assertStreamWorkerConfigColumns(DatabaseMetaData metaData) throws Exception {
        assertTrue(columnExists(metaData, "stream_worker_config", "match_id"));
        assertTrue(columnExists(metaData, "stream_worker_config", "stream_url"));
        assertTrue(columnExists(metaData, "stream_worker_config", "platform"));
        assertTrue(columnExists(metaData, "stream_worker_config", "roi_template_id"));
        assertTrue(columnExists(metaData, "stream_worker_config", "started_at_utc"));
        assertTrue(columnExists(metaData, "stream_worker_config", "stopped_at_utc"));
        assertTrue(columnExists(metaData, "stream_worker_config", "last_state"));
        assertTrue(columnExists(metaData, "stream_worker_config", "last_error"));
        assertTrue(columnExists(metaData, "stream_worker_config", "updated_at_utc"));
    }

    private void assertStreamWorkerHealthColumns(DatabaseMetaData metaData) throws Exception {
        assertTrue(columnExists(metaData, "stream_worker_health_1m", "match_id"));
        assertTrue(columnExists(metaData, "stream_worker_health_1m", "minute_bucket_utc"));
        assertTrue(columnExists(metaData, "stream_worker_health_1m", "frames_ingested"));
        assertTrue(columnExists(metaData, "stream_worker_health_1m", "frames_emitted"));
        assertTrue(columnExists(metaData, "stream_worker_health_1m", "p50_confidence"));
        assertTrue(columnExists(metaData, "stream_worker_health_1m", "p95_latency_ms"));
        assertTrue(columnExists(metaData, "stream_worker_health_1m", "vlm_calls"));
        assertTrue(columnExists(metaData, "stream_worker_health_1m", "state_seen_json"));
    }

    private void assertStreamRouteColumns(DatabaseMetaData metaData) throws Exception {
        assertTrue(columnExists(metaData, "stream_route", "route_id"));
        assertTrue(columnExists(metaData, "stream_route", "event_code"));
        assertTrue(columnExists(metaData, "stream_route", "table_number"));
        assertTrue(columnExists(metaData, "stream_route", "platform"));
        assertTrue(columnExists(metaData, "stream_route", "channel_or_base"));
        assertTrue(columnExists(metaData, "stream_route", "roi_template_id"));
        assertTrue(columnExists(metaData, "stream_route", "updated_at_utc"));
    }

    private boolean matches(String actual, String expected) {
        return actual != null
                && expected != null
                && actual.trim().toLowerCase(Locale.ROOT).equals(expected.trim().toLowerCase(Locale.ROOT));
    }
}
