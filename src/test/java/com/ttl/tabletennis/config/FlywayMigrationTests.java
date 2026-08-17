package com.ttl.tabletennis.config;

import db.migration.V20260729001__phase_01_evidence_integrity;
import db.migration.V20260729002__phase_02_score_backed_settlement;
import db.migration.V20260807001__phase_04_learning_eligibility;
import db.migration.V20260815001__odds_snapshot_no_vig_market;
import db.migration.V20260815002__model_call_artifact_identity;
import db.migration.V20260817001__research_run_foundation;
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
    void researchFoundationMigrationBackfillsOneSharedOpportunityWithoutSampleInflation() throws Exception {
        String url = "jdbc:h2:mem:research-foundation-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE paper_trade_session (
                        id BIGINT PRIMARY KEY, effective_model_family VARCHAR(40),
                        effective_model_version VARCHAR(100), effective_artifact_checksum VARCHAR(64),
                        feature_schema_checksum VARCHAR(64), calibration_id VARCHAR(100),
                        policy_version VARCHAR(100), created_at TIMESTAMP NOT NULL)
                    """);
            statement.execute("""
                    CREATE TABLE paper_trade_model_call (
                        id BIGINT PRIMARY KEY, session_id BIGINT NOT NULL, event_key VARCHAR(320) NOT NULL,
                        external_event_id VARCHAR(160), source_feed_event_id VARCHAR(160), event_name VARCHAR(220),
                        competition_name VARCHAR(180), player1_id BIGINT, player2_id BIGINT,
                        player1_name VARCHAR(180), player2_name VARCHAR(180), start_time_iso VARCHAR(80),
                        capture_type VARCHAR(24), captured_at TIMESTAMP, match_id_high_watermark BIGINT,
                        model_version VARCHAR(100), artifact_checksum VARCHAR(64), feature_schema_checksum VARCHAR(64),
                        calibration_id VARCHAR(100), predicted_winner_player_id BIGINT,
                        predicted_winner_name VARCHAR(180), model_probability DOUBLE, raw_model_probability DOUBLE,
                        confidence_low DOUBLE, confidence_high DOUBLE, model_fair_american_odds INT,
                        selection_score DOUBLE, signal_quality DOUBLE, top_trigger VARCHAR(180),
                        feature_contributions VARCHAR(2400), decision_status VARCHAR(32),
                        decision_reason VARCHAR(180), suggested_edge DOUBLE, hard_rock_no_vig_probability DOUBLE,
                        hard_rock_american_odds INT, opponent_hard_rock_american_odds INT)
                    """);
            statement.execute("""
                    INSERT INTO paper_trade_session VALUES
                    (7, 'ENSEMBLE', 'model-r3', 'artifact', 'schema', 'PLATT', 'strict-r3',
                     TIMESTAMP '2026-08-17 08:00:00')
                    """);
            statement.execute("""
                    INSERT INTO paper_trade_model_call VALUES
                    (11, 7, 'event-1', 'external-1', 'feed-1', 'Alpha vs Beta', 'TT Elite',
                     1, 2, 'Alpha', 'Beta', '2026-08-17T09:00:00-04:00', 'PREMATCH_CLOSE',
                     TIMESTAMP '2026-08-17 08:59:00', 100, 'model-r3', 'artifact', 'schema', 'PLATT',
                     1, 'Alpha', 0.62, 0.61, 0.55, 0.69, -163, 5.1, 0.8, 'RATING_EDGE',
                     'elo=0.20', 'SKIPPED', 'PRICE_GATE', 0.03, 0.58, -145, 125)
                    """);
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            V20260817001__research_run_foundation migration = new V20260817001__research_run_foundation();

            migration.migrate(context);
            migration.migrate(context);

            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(tableExists(metadata, "decision_opportunity"));
            assertTrue(tableExists(metadata, "run_model_lane_evaluation"));
            assertTrue(tableExists(metadata, "run_portfolio_decision"));
            assertTrue(tableExists(metadata, "experiment_collection"));
            try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM decision_opportunity")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
            try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM run_model_lane_evaluation")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
            try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM run_portfolio_decision")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
            }
        }
    }

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

    @Test
    void phase04LearningEligibilityMigrationBackfillsAndQuarantinesEvidence() throws Exception {
        String url = "jdbc:h2:mem:phase-04-learning-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE paper_trade_learning_sample (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        bet_id BIGINT NOT NULL,
                        status VARCHAR(16) NOT NULL,
                        calibration_eligible BOOLEAN DEFAULT FALSE NOT NULL,
                        settlement_source VARCHAR(64) NULL,
                        settlement_reason VARCHAR(120) NULL,
                        event_occurred_at TIMESTAMP NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE settlement_evidence (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        bet_id BIGINT NOT NULL,
                        bundle_as_of TIMESTAMP NOT NULL
                    )
                    """);
            statement.execute("""
                    INSERT INTO paper_trade_learning_sample
                        (bet_id, status, calibration_eligible, settlement_source, settlement_reason, event_occurred_at)
                    VALUES (10, 'WON', TRUE, 'SCORE_BACKED', 'SCORE_BACKED_FINISHED', CURRENT_TIMESTAMP),
                           (20, 'LOST', FALSE, 'HEURISTIC', 'LAST_SCORE', CURRENT_TIMESTAMP),
                           (30, 'VOIDED', FALSE, 'TIMEOUT', 'VOID', CURRENT_TIMESTAMP),
                           (40, 'WON', TRUE, 'OFFICIAL_RESULT', 'SETTLED_FROM_OFFICIAL_RESULT', CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO settlement_evidence (bet_id, bundle_as_of)
                    VALUES (10, CURRENT_TIMESTAMP), (20, CURRENT_TIMESTAMP), (99, CURRENT_TIMESTAMP)
                    """);

            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            V20260807001__phase_04_learning_eligibility migration =
                    new V20260807001__phase_04_learning_eligibility();

            migration.migrate(context);
            migration.migrate(context);

            DatabaseMetaData metaData = connection.getMetaData();
            assertTrue(columnExists(metaData, "paper_trade_learning_sample", "learning_eligible"));
            assertTrue(columnExists(metaData, "paper_trade_learning_sample", "learning_exclusion_reason"));
            assertTrue(columnExists(metaData, "settlement_evidence", "learning_eligible"));
            assertTrue(indexExists(metaData, "paper_trade_learning_sample", "idx_paper_learning_eligible_event"));
            assertTrue(indexExists(metaData, "settlement_evidence", "idx_settlement_evidence_learning"));

            try (ResultSet samples = statement.executeQuery("""
                    SELECT bet_id, learning_eligible, learning_exclusion_reason
                    FROM paper_trade_learning_sample ORDER BY bet_id
                    """)) {
                assertTrue(samples.next());
                assertEquals(10L, samples.getLong("bet_id"));
                assertTrue(samples.getBoolean("learning_eligible"));
                assertTrue(samples.next());
                assertEquals("LEGACY_LOW_CONFIDENCE", samples.getString("learning_exclusion_reason"));
                assertTrue(samples.next());
                assertEquals("NON_BINARY_OUTCOME", samples.getString("learning_exclusion_reason"));
                assertTrue(samples.next());
                assertEquals(40L, samples.getLong("bet_id"));
                assertEquals("LEGACY_ARCHIVE_UNVERIFIED", samples.getString("learning_exclusion_reason"));
            }
            try (ResultSet evidence = statement.executeQuery("""
                    SELECT bet_id, learning_eligible, learning_exclusion_reason
                    FROM settlement_evidence ORDER BY bet_id
                    """)) {
                assertTrue(evidence.next());
                assertTrue(evidence.getBoolean("learning_eligible"));
                assertTrue(evidence.next());
                assertEquals("LEGACY_LOW_CONFIDENCE", evidence.getString("learning_exclusion_reason"));
                assertTrue(evidence.next());
                assertEquals("LEGACY_EVIDENCE_UNCLASSIFIED", evidence.getString("learning_exclusion_reason"));
            }
        }
    }

    @Test
    void noVigMigrationBackfillsTimestampMatchedTwoWayPrices() throws Exception {
        String url = "jdbc:h2:mem:no-vig-market-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE odds_snapshot (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        tracked_event_id VARCHAR(64) NOT NULL,
                        side VARCHAR(4) NOT NULL,
                        observed_at TIMESTAMP NOT NULL,
                        implied_prob DOUBLE NOT NULL
                    )
                    """);
            statement.execute("""
                    INSERT INTO odds_snapshot (tracked_event_id, side, observed_at, implied_prob)
                    VALUES ('event-1', 'P1', TIMESTAMP '2026-08-15 10:00:00', 0.55),
                           ('event-1', 'P2', TIMESTAMP '2026-08-15 10:00:00', 0.50)
                    """);

            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            V20260815001__odds_snapshot_no_vig_market migration =
                    new V20260815001__odds_snapshot_no_vig_market();
            migration.migrate(context);
            migration.migrate(context);

            try (ResultSet rows = statement.executeQuery("""
                    SELECT side, no_vig_probability, market_overround
                    FROM odds_snapshot ORDER BY side
                    """)) {
                assertTrue(rows.next());
                assertEquals(0.55 / 1.05, rows.getDouble("no_vig_probability"), 1.0e-9);
                assertEquals(0.05, rows.getDouble("market_overround"), 1.0e-9);
                assertTrue(rows.next());
                assertEquals(0.50 / 1.05, rows.getDouble("no_vig_probability"), 1.0e-9);
            }
        }
    }

    @Test
    void artifactIdentityMigrationBackfillsAndRequiresFrozenTelemetry() throws Exception {
        String url = "jdbc:h2:mem:artifact-identity-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE paper_trade_model_call (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        selection_score DOUBLE,
                        signal_quality DOUBLE
                    )
                    """);
            statement.execute("CREATE TABLE paper_trade_decision_sample (id BIGINT AUTO_INCREMENT PRIMARY KEY)");
            statement.execute("CREATE TABLE paper_trade_session (id BIGINT AUTO_INCREMENT PRIMARY KEY)");
            statement.execute("INSERT INTO paper_trade_model_call (selection_score, signal_quality) VALUES (NULL, NULL)");

            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            V20260815002__model_call_artifact_identity migration =
                    new V20260815002__model_call_artifact_identity();
            migration.migrate(context);
            migration.migrate(context);

            DatabaseMetaData metaData = connection.getMetaData();
            assertTrue(columnExists(metaData, "paper_trade_model_call", "artifact_checksum"));
            assertTrue(columnExists(metaData, "paper_trade_model_call", "raw_model_probability"));
            assertTrue(columnExists(metaData, "paper_trade_model_call", "gate_results"));
            assertTrue(columnExists(metaData, "paper_trade_decision_sample", "gate_results"));
            assertTrue(columnExists(metaData, "paper_trade_session", "frozen_run_summary_checksum"));
            assertTrue(columnRequired(metaData, "paper_trade_model_call", "selection_score"));
            assertTrue(columnRequired(metaData, "paper_trade_model_call", "signal_quality"));
            try (ResultSet row = statement.executeQuery(
                    "SELECT selection_score, signal_quality FROM paper_trade_model_call")) {
                assertTrue(row.next());
                assertEquals(0.0, row.getDouble("selection_score"));
                assertEquals(0.0, row.getDouble("signal_quality"));
            }
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

    private boolean columnRequired(DatabaseMetaData metaData, String tableName, String columnName) throws Exception {
        try (ResultSet rs = metaData.getColumns(null, null, null, null)) {
            while (rs.next()) {
                if (matches(rs.getString("TABLE_NAME"), tableName)
                        && matches(rs.getString("COLUMN_NAME"), columnName)) {
                    return "NO".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
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
