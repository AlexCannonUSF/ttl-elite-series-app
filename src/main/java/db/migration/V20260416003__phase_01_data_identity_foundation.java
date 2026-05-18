package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

public class V20260416003__phase_01_data_identity_foundation extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metaData = connection.getMetaData();

        ensureOddsSnapshotTable(connection, metaData);
        ensureMirrorObservationTable(connection, metaData);
        ensureStreamObservationTable(connection, metaData);
        ensureFeedHealthSampleTable(connection, metaData);
        ensureIngestDlqTable(connection, metaData);
    }

    private void ensureOddsSnapshotTable(Connection connection, DatabaseMetaData metaData) throws SQLException {
        if (!tableExists(metaData, "odds_snapshot")) {
            execute(connection, """
                    CREATE TABLE odds_snapshot (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        tracked_event_id VARCHAR(64) NOT NULL,
                        booker_event_id VARCHAR(128) NULL,
                        match_key VARCHAR(128) NULL,
                        side VARCHAR(4) NOT NULL,
                        price_decimal DOUBLE NOT NULL,
                        implied_prob DOUBLE NOT NULL,
                        market_state VARCHAR(24) NOT NULL,
                        source_id VARCHAR(16) NOT NULL,
                        observed_at TIMESTAMP NOT NULL,
                        correlation_id VARCHAR(64) NULL,
                        raw_payload_ref VARCHAR(128) NULL
                    )
                    """);
        }

        ensureIndex(connection, metaData, "odds_snapshot", "idx_odds_snapshot_event_time", false, "tracked_event_id, observed_at");
        ensureIndex(connection, metaData, "odds_snapshot", "idx_odds_snapshot_match_time", false, "match_key, observed_at");
        ensureIndex(connection, metaData, "odds_snapshot", "idx_odds_snapshot_source_observed", false, "source_id, observed_at");
    }

    private void ensureMirrorObservationTable(Connection connection, DatabaseMetaData metaData) throws SQLException {
        if (!tableExists(metaData, "mirror_observation")) {
            execute(connection, """
                    CREATE TABLE mirror_observation (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        tracked_event_id VARCHAR(64) NOT NULL,
                        source_id VARCHAR(16) NOT NULL,
                        observed_at TIMESTAMP NOT NULL,
                        phase VARCHAR(16) NULL,
                        games_p1 INT NULL,
                        games_p2 INT NULL,
                        points_p1 INT NULL,
                        points_p2 INT NULL,
                        server VARCHAR(4) NULL,
                        completion_signal BOOLEAN NULL,
                        confidence DOUBLE NULL,
                        correlation_id VARCHAR(64) NULL,
                        payload_json LONGTEXT NOT NULL,
                        raw_payload_ref VARCHAR(128) NULL
                    )
                    """);
        }

        ensureIndex(connection, metaData, "mirror_observation", "idx_mirror_observation_event_time", false, "tracked_event_id, observed_at");
        ensureIndex(connection, metaData, "mirror_observation", "idx_mirror_observation_source_time", false, "source_id, observed_at");
    }

    private void ensureStreamObservationTable(Connection connection, DatabaseMetaData metaData) throws SQLException {
        if (!tableExists(metaData, "stream_observation")) {
            execute(connection, """
                    CREATE TABLE stream_observation (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        tracked_event_id VARCHAR(64) NOT NULL,
                        stream_url VARCHAR(256) NULL,
                        observed_at TIMESTAMP NOT NULL,
                        games_p1 INT NULL,
                        games_p2 INT NULL,
                        points_p1 INT NULL,
                        points_p2 INT NULL,
                        server VARCHAR(4) NULL,
                        ocr_confidence DOUBLE NULL,
                        state_machine_passed BOOLEAN NULL,
                        correlation_id VARCHAR(64) NULL,
                        frame_ref VARCHAR(256) NULL,
                        vlm_fallback_used BOOLEAN NULL
                    )
                    """);
        }

        ensureIndex(connection, metaData, "stream_observation", "idx_stream_observation_event_time", false, "tracked_event_id, observed_at");
        ensureIndex(connection, metaData, "stream_observation", "idx_stream_observation_observed", false, "observed_at");
    }

    private void ensureFeedHealthSampleTable(Connection connection, DatabaseMetaData metaData) throws SQLException {
        if (!tableExists(metaData, "feed_health_sample")) {
            execute(connection, """
                    CREATE TABLE feed_health_sample (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        source_id VARCHAR(16) NOT NULL,
                        observed_at TIMESTAMP NOT NULL,
                        rolling_success_rate_5m DOUBLE NULL,
                        rolling_p50_latency_ms DOUBLE NULL,
                        rolling_p95_latency_ms DOUBLE NULL,
                        in_flight INT NULL,
                        backoff_state VARCHAR(16) NULL,
                        last_error VARCHAR(256) NULL
                    )
                    """);
        }

        ensureIndex(connection, metaData, "feed_health_sample", "idx_feed_health_sample_source_observed", false, "source_id, observed_at");
        ensureIndex(connection, metaData, "feed_health_sample", "idx_feed_health_sample_observed", false, "observed_at");
    }

    private void ensureIngestDlqTable(Connection connection, DatabaseMetaData metaData) throws SQLException {
        if (!tableExists(metaData, "ingest_dlq")) {
            execute(connection, """
                    CREATE TABLE ingest_dlq (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        topic VARCHAR(64) NOT NULL,
                        source_id VARCHAR(16) NOT NULL,
                        correlation_id VARCHAR(64) NULL,
                        payload_json LONGTEXT NOT NULL,
                        failure_count INT NOT NULL,
                        last_error TEXT NULL,
                        next_retry_at TIMESTAMP NULL,
                        arrived_at TIMESTAMP NOT NULL
                    )
                    """);
        }

        ensureIndex(connection, metaData, "ingest_dlq", "idx_ingest_dlq_topic_source_arrived", false, "topic, source_id, arrived_at");
        ensureIndex(connection, metaData, "ingest_dlq", "idx_ingest_dlq_next_retry", false, "next_retry_at");
    }

    private boolean tableExists(DatabaseMetaData metaData, String tableName) throws SQLException {
        try (ResultSet rs = metaData.getTables(null, null, null, new String[]{"TABLE"})) {
            while (rs.next()) {
                if (matchesName(rs.getString("TABLE_NAME"), tableName)) {
                    return true;
                }
            }
            return false;
        }
    }

    private void ensureIndex(Connection connection,
                             DatabaseMetaData metaData,
                             String tableName,
                             String indexName,
                             boolean unique,
                             String columns) throws SQLException {
        if (indexExists(metaData, tableName, indexName)) {
            return;
        }
        String uniqueness = unique ? "UNIQUE " : "";
        execute(connection, "CREATE " + uniqueness + "INDEX " + indexName + " ON " + tableName + " (" + columns + ")");
    }

    private boolean indexExists(DatabaseMetaData metaData, String tableName, String indexName) throws SQLException {
        String lookupTable = tableName == null ? null : tableName.toUpperCase(Locale.ROOT);
        try (ResultSet rs = metaData.getIndexInfo(null, null, lookupTable, false, false)) {
            while (rs.next()) {
                if (matchesName(rs.getString("TABLE_NAME"), tableName)
                        && matchesName(rs.getString("INDEX_NAME"), indexName)) {
                    return true;
                }
            }
            return false;
        }
    }

    private boolean matchesName(String actual, String expected) {
        if (actual == null || expected == null) {
            return false;
        }
        return actual.trim().toLowerCase(Locale.ROOT).equals(expected.trim().toLowerCase(Locale.ROOT));
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
