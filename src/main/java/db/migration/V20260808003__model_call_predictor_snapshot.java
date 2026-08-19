package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/** Adds the immutable trigger/factor snapshot used by live-run attribution. */
public class V20260808003__model_call_predictor_snapshot extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metadata = connection.getMetaData();
        add(connection, metadata, "top_trigger", "VARCHAR(180)");
        add(connection, metadata, "feature_contributions", "VARCHAR(2400)");
        add(connection, metadata, "overall_reliability", "DOUBLE");
        add(connection, metadata, "rating_agreement", "DOUBLE");
        add(connection, metadata, "trigger_reliability", "DOUBLE");
        add(connection, metadata, "baseline_stability", "DOUBLE");
        add(connection, metadata, "suggested_edge", "DOUBLE");
        add(connection, metadata, "selection_score", "DOUBLE");
        add(connection, metadata, "signal_quality", "DOUBLE");
        add(connection, metadata, "confidence_width", "DOUBLE");
    }

    private static void add(Connection connection,
                            DatabaseMetaData metadata,
                            String column,
                            String type) throws SQLException {
        if (!columnExists(metadata, "paper_trade_model_call", column)) {
            execute(connection, "ALTER TABLE paper_trade_model_call ADD " + column + " " + type);
        }
    }

    private static boolean columnExists(DatabaseMetaData metadata,
                                        String table,
                                        String column) throws SQLException {
        for (String tableName : new String[]{table, table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT)}) {
            try (ResultSet rows = metadata.getColumns(null, null, tableName, null)) {
                while (rows.next()) {
                    if (table.equalsIgnoreCase(rows.getString("TABLE_NAME"))
                            && column.equalsIgnoreCase(rows.getString("COLUMN_NAME"))) return true;
                }
            }
        }
        return false;
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
