package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

public class V20260416002__settlement_diff_log extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metaData = connection.getMetaData();

        if (!tableExists(metaData, "settlement_diff_log")) {
            execute(connection, """
                    CREATE TABLE settlement_diff_log (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        bet_id BIGINT NOT NULL,
                        old_reason VARCHAR(64) NULL,
                        new_reason VARCHAR(64) NULL,
                        diff_kind VARCHAR(32) NULL,
                        old_winner BIGINT NULL,
                        new_winner BIGINT NULL,
                        decided_at TIMESTAMP NOT NULL,
                        correlation_id VARCHAR(64) NULL
                    )
                    """);
        }

        ensureIndex(connection, metaData, "settlement_diff_log", "idx_settlement_diff_bet_decided", false, "bet_id, decided_at");
        ensureIndex(connection, metaData, "settlement_diff_log", "idx_settlement_diff_decided", false, "decided_at");
        ensureIndex(connection, metaData, "settlement_diff_log", "idx_settlement_diff_kind", false, "diff_kind");
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
        try (ResultSet rs = metaData.getIndexInfo(null, null, tableName, false, false)) {
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
