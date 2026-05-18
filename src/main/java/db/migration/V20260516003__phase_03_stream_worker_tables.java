package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

public class V20260516003__phase_03_stream_worker_tables extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metaData = connection.getMetaData();

        ensureStreamWorkerConfigTable(connection, metaData);
        ensureStreamWorkerHealthTable(connection, metaData);
        ensureStreamRouteTable(connection, metaData);
    }

    private void ensureStreamWorkerConfigTable(Connection connection, DatabaseMetaData metaData) throws SQLException {
        if (!tableExists(metaData, "stream_worker_config")) {
            execute(connection, """
                    CREATE TABLE stream_worker_config (
                        match_id VARCHAR(120) PRIMARY KEY,
                        stream_url LONGTEXT NOT NULL,
                        platform VARCHAR(24) NOT NULL,
                        roi_template_id VARCHAR(64) NOT NULL,
                        started_at_utc TIMESTAMP NULL,
                        stopped_at_utc TIMESTAMP NULL,
                        last_state VARCHAR(32) NULL,
                        last_error LONGTEXT NULL,
                        updated_at_utc TIMESTAMP NOT NULL
                    )
                    """);
        }

        ensureIndex(connection, metaData, "stream_worker_config", "idx_stream_worker_config_state", false, "last_state");
        ensureIndex(connection, metaData, "stream_worker_config", "idx_stream_worker_config_updated", false, "updated_at_utc");
    }

    private void ensureStreamWorkerHealthTable(Connection connection, DatabaseMetaData metaData) throws SQLException {
        if (!tableExists(metaData, "stream_worker_health_1m")) {
            execute(connection, """
                    CREATE TABLE stream_worker_health_1m (
                        match_id VARCHAR(120) NOT NULL,
                        minute_bucket_utc TIMESTAMP NOT NULL,
                        frames_ingested INT NOT NULL,
                        frames_emitted INT NOT NULL,
                        p50_confidence DECIMAL(4,3) NULL,
                        p95_latency_ms INT NULL,
                        vlm_calls INT NOT NULL,
                        state_seen_json LONGTEXT NOT NULL,
                        PRIMARY KEY (match_id, minute_bucket_utc)
                    )
                    """);
        }

        ensureIndex(connection, metaData, "stream_worker_health_1m", "idx_stream_worker_health_bucket", false, "minute_bucket_utc");
        ensureIndex(connection, metaData, "stream_worker_health_1m", "idx_stream_worker_health_match_bucket", false, "match_id, minute_bucket_utc");
    }

    private void ensureStreamRouteTable(Connection connection, DatabaseMetaData metaData) throws SQLException {
        if (!tableExists(metaData, "stream_route")) {
            execute(connection, """
                    CREATE TABLE stream_route (
                        route_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        event_code VARCHAR(32) NOT NULL,
                        table_number VARCHAR(16) NOT NULL,
                        platform VARCHAR(24) NOT NULL,
                        channel_or_base LONGTEXT NOT NULL,
                        roi_template_id VARCHAR(64) NOT NULL,
                        updated_at_utc TIMESTAMP NOT NULL
                    )
                    """);
        }

        ensureIndex(connection, metaData, "stream_route", "idx_stream_route_event_table", true, "event_code, table_number");
        ensureIndex(connection, metaData, "stream_route", "idx_stream_route_platform", false, "platform");
        ensureIndex(connection, metaData, "stream_route", "idx_stream_route_updated", false, "updated_at_utc");
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
