package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

public class V20260517001__phase_04_stream_vlm_call extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metaData = connection.getMetaData();

        if (!tableExists(metaData, "stream_vlm_call")) {
            execute(connection, """
                    CREATE TABLE stream_vlm_call (
                        call_id VARCHAR(36) PRIMARY KEY,
                        match_id VARCHAR(120) NOT NULL,
                        worker_id VARCHAR(120) NOT NULL,
                        frame_id VARCHAR(160) NULL,
                        model VARCHAR(48) NOT NULL,
                        decision VARCHAR(32) NOT NULL,
                        tokens_in INT NULL,
                        tokens_out INT NULL,
                        latency_ms BIGINT NULL,
                        cost_usd_est DECIMAL(10,6) NULL,
                        response_valid BOOLEAN NOT NULL DEFAULT FALSE,
                        error_reason VARCHAR(255) NULL,
                        called_at_utc TIMESTAMP NOT NULL
                    )
                    """);
        }

        ensureIndex(connection, metaData, "stream_vlm_call", "idx_stream_vlm_call_called_at", false, "called_at_utc");
        ensureIndex(connection, metaData, "stream_vlm_call", "idx_stream_vlm_call_model_called", false, "model, called_at_utc");
        ensureIndex(connection, metaData, "stream_vlm_call", "idx_stream_vlm_call_worker_called", false, "worker_id, called_at_utc");
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
