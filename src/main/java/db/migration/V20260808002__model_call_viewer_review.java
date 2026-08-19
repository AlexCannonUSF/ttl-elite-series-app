package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/** Adds append-only viewer grades without changing canonical result truth. */
public class V20260808002__model_call_viewer_review extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metadata = connection.getMetaData();
        if (!tableExists(metadata, "model_call_viewer_review")) {
            execute(connection, """
                    CREATE TABLE model_call_viewer_review (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        call_id BIGINT NOT NULL,
                        session_id BIGINT NOT NULL,
                        event_key VARCHAR(320) NOT NULL,
                        winner_player_id BIGINT NOT NULL,
                        winner_name VARCHAR(180) NOT NULL,
                        score VARCHAR(80),
                        reviewer VARCHAR(80) NOT NULL,
                        note VARCHAR(400),
                        created_at TIMESTAMP NOT NULL
                    )
                    """);
        }
        ensureIndex(connection, metadata, "model_call_viewer_review",
                "idx_model_call_review_call_time", "call_id, created_at");
        ensureIndex(connection, metadata, "model_call_viewer_review",
                "idx_model_call_review_session_time", "session_id, created_at");
    }

    private static void ensureIndex(Connection connection,
                                    DatabaseMetaData metadata,
                                    String table,
                                    String index,
                                    String columns) throws SQLException {
        if (!indexExists(metadata, table, index)) {
            execute(connection, "CREATE INDEX " + index + " ON " + table + " (" + columns + ")");
        }
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

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
