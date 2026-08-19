package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Adds the covering indexes used by the live-run dashboard and historical
 * feature reconstruction. Without them, a refresh can fan out into several
 * full-table scans and exhaust the application's connection pool.
 */
public class V20260809002__live_run_query_indexes extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metadata = connection.getMetaData();
        ensureIndex(connection, metadata, "odds_snapshot", "idx_odds_snapshot_book_side_time",
                "booker_event_id, side, observed_at");
        ensureIndex(connection, metadata, "tracked_match_observation", "idx_tracked_obs_session_event_time",
                "session_id, event_key, observed_at, id");
        ensureIndex(connection, metadata, "matches", "idx_matches_complete_p1_date",
                "is_complete, player1_id, match_date, id");
        ensureIndex(connection, metadata, "matches", "idx_matches_complete_p2_date",
                "is_complete, player2_id, match_date, id");
        ensureIndex(connection, metadata, "matches", "idx_matches_complete_h2h_date",
                "is_complete, player1_id, player2_id, match_date, id");
    }

    private static void ensureIndex(Connection connection,
                                    DatabaseMetaData metadata,
                                    String table,
                                    String index,
                                    String columns) throws SQLException {
        if (!tableExists(metadata, table)
                || !columnsExist(metadata, table, columns)
                || indexExists(metadata, table, index)) return;
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE INDEX " + index + " ON " + table + " (" + columns + ")");
        }
    }

    private static boolean columnsExist(DatabaseMetaData metadata, String table, String columns) throws SQLException {
        for (String column : columns.split(",")) {
            if (!columnExists(metadata, table, column.trim())) return false;
        }
        return true;
    }

    private static boolean columnExists(DatabaseMetaData metadata, String table, String column) throws SQLException {
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

    private static boolean tableExists(DatabaseMetaData metadata, String table) throws SQLException {
        try (ResultSet rows = metadata.getTables(null, null, null, new String[]{"TABLE"})) {
            while (rows.next()) {
                if (table.equalsIgnoreCase(rows.getString("TABLE_NAME"))) return true;
            }
        }
        return false;
    }

    private static boolean indexExists(DatabaseMetaData metadata, String table, String index) throws SQLException {
        for (String tableName : new String[]{table, table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT)}) {
            try (ResultSet rows = metadata.getIndexInfo(null, null, tableName, false, false)) {
                while (rows.next()) {
                    if (table.equalsIgnoreCase(rows.getString("TABLE_NAME"))
                            && index.equalsIgnoreCase(rows.getString("INDEX_NAME"))) return true;
                }
            }
        }
        return false;
    }
}
