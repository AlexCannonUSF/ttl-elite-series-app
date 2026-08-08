package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/** Creates the session-scoped, all-match model winner call ledger. */
public class V20260808001__paper_model_call_ledger extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metadata = connection.getMetaData();
        if (!tableExists(metadata, "paper_trade_model_call")) {
            execute(connection, """
                    CREATE TABLE paper_trade_model_call (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        session_id BIGINT NOT NULL,
                        event_key VARCHAR(320) NOT NULL,
                        event_name VARCHAR(220),
                        competition_name VARCHAR(180),
                        source VARCHAR(96),
                        strategy VARCHAR(24),
                        model_version VARCHAR(80),
                        capture_type VARCHAR(24) NOT NULL,
                        captured_at TIMESTAMP NOT NULL,
                        start_time_iso VARCHAR(80),
                        external_event_id VARCHAR(160),
                        source_feed_event_id VARCHAR(160),
                        match_id_high_watermark BIGINT,
                        player1_id BIGINT,
                        player1_name VARCHAR(180),
                        player2_id BIGINT,
                        player2_name VARCHAR(180),
                        predicted_winner_player_id BIGINT,
                        predicted_winner_name VARCHAR(180),
                        model_probability DOUBLE,
                        model_fair_american_odds INTEGER,
                        hard_rock_american_odds INTEGER,
                        opponent_hard_rock_american_odds INTEGER,
                        hard_rock_no_vig_probability DOUBLE,
                        recommended_at_capture BOOLEAN DEFAULT FALSE NOT NULL,
                        has_paper_pick BOOLEAN DEFAULT FALSE NOT NULL,
                        decision_status VARCHAR(24),
                        decision_reason VARCHAR(160)
                    )
                    """);
        }
        ensureIndex(connection, metadata, "paper_trade_model_call",
                "uk_paper_model_call_session_event", "session_id, event_key", true);
        ensureIndex(connection, metadata, "paper_trade_model_call",
                "idx_paper_model_call_session_captured", "session_id, captured_at", false);
        ensureIndex(connection, metadata, "paper_trade_model_call",
                "idx_paper_model_call_external", "external_event_id", false);
        ensureIndex(connection, metadata, "paper_trade_model_call",
                "idx_paper_model_call_feed_event", "source_feed_event_id", false);
    }

    private static void ensureIndex(Connection connection,
                                    DatabaseMetaData metadata,
                                    String table,
                                    String index,
                                    String columns,
                                    boolean unique) throws SQLException {
        if (indexExists(metadata, table, index)) return;
        execute(connection, "CREATE " + (unique ? "UNIQUE " : "")
                + "INDEX " + index + " ON " + table + " (" + columns + ")");
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
