package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

public class V20260518001__phase_04_prediction_diff_log extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metaData = connection.getMetaData();

        if (!tableExists(metaData, "prediction_diff_log")) {
            execute(connection, """
                    CREATE TABLE prediction_diff_log (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        prediction_id VARCHAR(64) NOT NULL,
                        player1_id BIGINT NOT NULL,
                        player2_id BIGINT NOT NULL,
                        as_of_date DATE NOT NULL,
                        v2_model_family VARCHAR(48) NULL,
                        v2_model_version VARCHAR(48) NULL,
                        v2_p1_probability DECIMAL(7,6) NOT NULL,
                        v3_model_version VARCHAR(48) NULL,
                        v3_calibrator_version VARCHAR(64) NULL,
                        v3_conformal_version VARCHAR(64) NULL,
                        v3_uncertainty_label VARCHAR(32) NULL,
                        v3_p1_probability DECIMAL(7,6) NULL,
                        abs_diff DECIMAL(7,6) NULL,
                        shadow_status VARCHAR(32) NOT NULL,
                        error_reason VARCHAR(255) NULL,
                        latency_ms BIGINT NULL,
                        computed_at_utc TIMESTAMP NOT NULL
                    )
                    """);
        }

        ensureIndex(connection, metaData, "prediction_diff_log", "idx_prediction_diff_log_date", false, "as_of_date");
        ensureIndex(connection, metaData, "prediction_diff_log", "idx_prediction_diff_log_status", false, "shadow_status, computed_at_utc");
        ensureIndex(connection, metaData, "prediction_diff_log", "idx_prediction_diff_log_pred", true, "prediction_id");
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

    private void ensureIndex(Connection connection, DatabaseMetaData metaData, String tableName,
                             String indexName, boolean unique, String columns) throws SQLException {
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
