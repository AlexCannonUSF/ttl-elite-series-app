package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

public class V20260516002__phase_03_bet_settlement_policy_audit extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metaData = connection.getMetaData();

        if (!tableExists(metaData, "settlement_policy_audit")) {
            execute(connection, """
                    CREATE TABLE settlement_policy_audit (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        policy_name VARCHAR(96) NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        triggered_by VARCHAR(96) NOT NULL,
                        source_path VARCHAR(512) NOT NULL,
                        checksum_before VARCHAR(96) NULL,
                        checksum_after VARCHAR(96) NULL,
                        error_message VARCHAR(512) NULL,
                        reloaded_at TIMESTAMP NOT NULL,
                        payload_json LONGTEXT NOT NULL,
                        correlation_id VARCHAR(64) NULL
                    )
                    """);
        }

        ensureIndex(connection, metaData, "settlement_policy_audit", "idx_settlement_policy_audit_policy_time", false, "policy_name, reloaded_at");
        ensureIndex(connection, metaData, "settlement_policy_audit", "idx_settlement_policy_audit_status_time", false, "status, reloaded_at");
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
