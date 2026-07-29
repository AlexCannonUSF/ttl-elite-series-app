package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

public class V20260517002__phase_04_settlement_audit_evidence_refs extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metaData = connection.getMetaData();

        if (!columnExists(metaData, "settlement_audit", "evidence_refs")) {
            execute(connection, "ALTER TABLE settlement_audit ADD COLUMN evidence_refs LONGTEXT NULL");
        }
    }

    private boolean columnExists(DatabaseMetaData metaData, String tableName, String columnName) throws SQLException {
        try (ResultSet rs = metaData.getColumns(null, null, tableName.toUpperCase(Locale.ROOT), null)) {
            while (rs.next()) {
                if (matchesName(rs.getString("COLUMN_NAME"), columnName)) {
                    return true;
                }
            }
        }
        try (ResultSet rs = metaData.getColumns(null, null, tableName, null)) {
            while (rs.next()) {
                if (matchesName(rs.getString("COLUMN_NAME"), columnName)) {
                    return true;
                }
            }
        }
        return false;
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
