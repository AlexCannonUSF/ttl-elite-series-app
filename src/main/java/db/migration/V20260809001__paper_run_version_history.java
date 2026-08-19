package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/** Adds immutable model/policy identity to each paper-trading run. */
public class V20260809001__paper_run_version_history extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metadata = connection.getMetaData();
        if (!tableExists(metadata, "paper_trade_session")) {
            return;
        }
        add(connection, metadata, "requested_model_version", "VARCHAR(100)");
        add(connection, metadata, "effective_model_version", "VARCHAR(100)");
        add(connection, metadata, "effective_model_family", "VARCHAR(40)");
        add(connection, metadata, "policy_version", "VARCHAR(100)");
        add(connection, metadata, "code_revision", "VARCHAR(80)");
        add(connection, metadata, "closed_at", "TIMESTAMP");
    }

    private static void add(Connection connection, DatabaseMetaData metadata, String column, String type)
            throws SQLException {
        if (!columnExists(metadata, "paper_trade_session", column)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE paper_trade_session ADD " + column + " " + type);
            }
        }
    }

    private static boolean columnExists(DatabaseMetaData metadata, String table, String column) throws SQLException {
        for (String tableName : new String[]{table, table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT)}) {
            try (ResultSet rows = metadata.getColumns(null, null, tableName, null)) {
                while (rows.next()) {
                    if (table.equalsIgnoreCase(rows.getString("TABLE_NAME"))
                            && column.equalsIgnoreCase(rows.getString("COLUMN_NAME"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean tableExists(DatabaseMetaData metadata, String table) throws SQLException {
        for (String tableName : new String[]{table, table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT)}) {
            try (ResultSet rows = metadata.getTables(null, null, tableName, new String[]{"TABLE"})) {
                while (rows.next()) {
                    if (table.equalsIgnoreCase(rows.getString("TABLE_NAME"))) return true;
                }
            }
        }
        return false;
    }
}
