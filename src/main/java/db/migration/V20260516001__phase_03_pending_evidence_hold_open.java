package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

public class V20260516001__phase_03_pending_evidence_hold_open extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metaData = connection.getMetaData();

        ensurePendingEvidenceColumns(connection, metaData, "paper_trade_bet", "idx_paper_bet");
        ensurePendingEvidenceColumns(connection, metaData, "paper_trade_bet_shadow", "idx_paper_bet_shadow");
    }

    private void ensurePendingEvidenceColumns(Connection connection,
                                              DatabaseMetaData metaData,
                                              String tableName,
                                              String indexPrefix) throws SQLException {
        if (!tableExists(metaData, tableName)) {
            return;
        }
        addColumnIfMissing(connection, metaData, tableName, "pending_evidence_until", "TIMESTAMP NULL");
        addColumnIfMissing(connection, metaData, tableName, "pending_evidence_next_poll_at", "TIMESTAMP NULL");
        addColumnIfMissing(connection, metaData, tableName, "pending_evidence_reason", "VARCHAR(96) NULL");
        addColumnIfMissing(connection, metaData, tableName, "pending_evidence_note", "VARCHAR(256) NULL");
        addColumnIfMissing(connection, metaData, tableName, "pending_evidence_updated_at", "TIMESTAMP NULL");

        ensureIndex(connection, metaData, tableName, indexPrefix + "_pending_poll", false, "status, pending_evidence_next_poll_at");
        ensureIndex(connection, metaData, tableName, indexPrefix + "_pending_until", false, "status, pending_evidence_until");
    }

    private void addColumnIfMissing(Connection connection,
                                    DatabaseMetaData metaData,
                                    String tableName,
                                    String columnName,
                                    String definition) throws SQLException {
        if (columnExists(metaData, tableName, columnName)) {
            return;
        }
        execute(connection, "ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
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

    private boolean columnExists(DatabaseMetaData metaData, String tableName, String columnName) throws SQLException {
        try (ResultSet rs = metaData.getColumns(null, null, null, null)) {
            while (rs.next()) {
                if (matchesName(rs.getString("TABLE_NAME"), tableName)
                        && matchesName(rs.getString("COLUMN_NAME"), columnName)) {
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
