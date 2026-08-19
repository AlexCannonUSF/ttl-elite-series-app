package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/** Persists timestamped two-way no-vig market context on every odds side. */
public class V20260815001__odds_snapshot_no_vig_market extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metadata = connection.getMetaData();
        add(connection, metadata, "no_vig_probability", "DOUBLE");
        add(connection, metadata, "market_overround", "DOUBLE");
        backfillExistingTwoWaySnapshots(connection);
    }

    private static void backfillExistingTwoWaySnapshots(Connection connection) throws SQLException {
        String twoWayTotal = "(SELECT MAX(CASE WHEN paired.side = 'P1' THEN paired.implied_prob END) "
                + "+ MAX(CASE WHEN paired.side = 'P2' THEN paired.implied_prob END) "
                + "FROM odds_snapshot paired WHERE paired.tracked_event_id = current_row.tracked_event_id "
                + "AND paired.observed_at = current_row.observed_at)";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE odds_snapshot current_row SET "
                    + "no_vig_probability = current_row.implied_prob / " + twoWayTotal + ", "
                    + "market_overround = " + twoWayTotal + " - 1.0 "
                    + "WHERE current_row.no_vig_probability IS NULL AND " + twoWayTotal + " > 0.0");
        }
    }

    private static void add(Connection connection, DatabaseMetaData metadata, String column, String type)
            throws SQLException {
        if (!columnExists(metadata, "odds_snapshot", column)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE odds_snapshot ADD " + column + " " + type);
            }
        }
    }

    private static boolean columnExists(DatabaseMetaData metadata, String table, String column) throws SQLException {
        for (String candidate : new String[]{table, table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT)}) {
            try (ResultSet rows = metadata.getColumns(null, null, candidate, null)) {
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
}
