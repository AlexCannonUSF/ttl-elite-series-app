package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

public class V20260416001__phase_00_foundations extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metaData = connection.getMetaData();

        ensurePaperTradeSessionShadow(connection, metaData);
        ensurePaperTradeBetShadow(connection, metaData);

        ensureCorrelationColumnIfTableExists(connection, metaData, "scrape_run");
        ensureCorrelationColumnIfTableExists(connection, metaData, "scrape_error");
        ensureCorrelationColumnIfTableExists(connection, metaData, "odds_quote");
        ensureCorrelationColumnIfTableExists(connection, metaData, "paper_trade_decision_sample");
        ensureCorrelationColumnIfTableExists(connection, metaData, "paper_trade_learning_sample");
        ensureCorrelationColumnIfTableExists(connection, metaData, "value_opportunity");
        ensureCorrelationColumnIfTableExists(connection, metaData, "tracked_match_observation");
    }

    private void ensurePaperTradeSessionShadow(Connection connection, DatabaseMetaData metaData) throws SQLException {
        if (!tableExists(metaData, "paper_trade_session_shadow")) {
            execute(connection, """
                    CREATE TABLE paper_trade_session_shadow (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        source_session_id BIGINT NOT NULL,
                        status VARCHAR(20) NOT NULL,
                        label VARCHAR(80) NOT NULL,
                        starting_bankroll DOUBLE NOT NULL,
                        current_bankroll DOUBLE NOT NULL,
                        peak_bankroll DOUBLE NOT NULL,
                        realized_pnl DOUBLE NOT NULL,
                        total_staked DOUBLE NOT NULL,
                        total_returned DOUBLE NOT NULL,
                        total_bets INT NOT NULL,
                        wins INT NOT NULL,
                        losses INT NOT NULL,
                        pushes INT NOT NULL,
                        simulation_rows_scanned BIGINT NOT NULL,
                        simulation_bets_placed BIGINT NOT NULL,
                        simulation_bets_settled BIGINT NOT NULL,
                        simulation_bets_voided BIGINT NOT NULL,
                        adaptive_sample_size INT NOT NULL,
                        adaptive_edge_shift DOUBLE NOT NULL,
                        adaptive_selection_shift DOUBLE NOT NULL,
                        adaptive_stake_multiplier DOUBLE NOT NULL,
                        adaptive_calibration_error DOUBLE NOT NULL,
                        adaptive_roi_signal DOUBLE NOT NULL,
                        adaptive_updated_at TIMESTAMP NULL,
                        last_sync_at TIMESTAMP NULL,
                        created_at TIMESTAMP NOT NULL,
                        updated_at TIMESTAMP NOT NULL,
                        mirrored_at TIMESTAMP NOT NULL,
                        correlation_id VARCHAR(64) NULL,
                        CONSTRAINT uq_paper_trade_session_shadow_source UNIQUE (source_session_id)
                    )
                    """);
        }

        ensureIndex(connection, metaData, "paper_trade_session_shadow", "idx_paper_session_shadow_source_session", true, "source_session_id");
        ensureIndex(connection, metaData, "paper_trade_session_shadow", "idx_paper_session_shadow_status", false, "status");
        ensureIndex(connection, metaData, "paper_trade_session_shadow", "idx_paper_session_shadow_updated", false, "updated_at");
        ensureIndex(connection, metaData, "paper_trade_session_shadow", "idx_paper_session_shadow_mirrored", false, "mirrored_at");
    }

    private void ensurePaperTradeBetShadow(Connection connection, DatabaseMetaData metaData) throws SQLException {
        if (!tableExists(metaData, "paper_trade_bet_shadow")) {
            execute(connection, """
                    CREATE TABLE paper_trade_bet_shadow (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        source_bet_id BIGINT NOT NULL,
                        session_id BIGINT NOT NULL,
                        status VARCHAR(16) NOT NULL,
                        source VARCHAR(128) NOT NULL,
                        strategy VARCHAR(24) NOT NULL,
                        model_version VARCHAR(80) NOT NULL,
                        event_key VARCHAR(320) NOT NULL,
                        dedupe_key VARCHAR(420) NOT NULL,
                        event_name VARCHAR(220) NOT NULL,
                        competition_name VARCHAR(180) NOT NULL,
                        start_time_iso VARCHAR(64) NULL,
                        external_event_id VARCHAR(96) NULL,
                        identity_locked BOOLEAN NOT NULL,
                        identity_locked_at TIMESTAMP NULL,
                        locked_start_time_iso VARCHAR(64) NULL,
                        locked_external_event_id VARCHAR(96) NULL,
                        locked_source_feed_event_id VARCHAR(128) NULL,
                        identity_drift_count INT NOT NULL,
                        last_identity_drift_at TIMESTAMP NULL,
                        live_at_placement BOOLEAN NOT NULL,
                        player1_id BIGINT NULL,
                        player2_id BIGINT NULL,
                        side_player_id BIGINT NULL,
                        player1_name VARCHAR(180) NOT NULL,
                        player2_name VARCHAR(180) NOT NULL,
                        side_name VARCHAR(180) NOT NULL,
                        decimal_odds DOUBLE NOT NULL,
                        american_odds INT NOT NULL,
                        implied_probability DOUBLE NOT NULL,
                        model_probability DOUBLE NOT NULL,
                        edge DOUBLE NOT NULL,
                        confidence_low DOUBLE NULL,
                        confidence_high DOUBLE NULL,
                        stake DOUBLE NOT NULL,
                        potential_payout DOUBLE NOT NULL,
                        profit_loss DOUBLE NULL,
                        winner_player_id BIGINT NULL,
                        result_match_id BIGINT NULL,
                        top_trigger VARCHAR(180) NULL,
                        top_trigger_contribution DOUBLE NULL,
                        grade VARCHAR(8) NULL,
                        rationale VARCHAR(512) NULL,
                        last_observed_score VARCHAR(64) NULL,
                        last_observed_phase VARCHAR(48) NULL,
                        last_score_source VARCHAR(48) NULL,
                        last_score_confidence DOUBLE NULL,
                        last_observation_displayed BOOLEAN NOT NULL,
                        last_observation_resulted BOOLEAN NOT NULL,
                        last_match_completed BOOLEAN NOT NULL,
                        last_source_feed_code VARCHAR(64) NULL,
                        last_source_feed_event_id VARCHAR(128) NULL,
                        last_score_detail VARCHAR(180) NULL,
                        tracked_after_close BOOLEAN NOT NULL,
                        settlement_reason VARCHAR(96) NULL,
                        settlement_source VARCHAR(48) NULL,
                        last_observed_at TIMESTAMP NULL,
                        missing_board_count INT NOT NULL,
                        placed_at TIMESTAMP NOT NULL,
                        settled_at TIMESTAMP NULL,
                        mirrored_at TIMESTAMP NOT NULL,
                        correlation_id VARCHAR(64) NULL,
                        CONSTRAINT uq_paper_trade_bet_shadow_source UNIQUE (source_bet_id)
                    )
                    """);
        }

        ensureIndex(connection, metaData, "paper_trade_bet_shadow", "idx_paper_bet_shadow_source_bet", true, "source_bet_id");
        ensureIndex(connection, metaData, "paper_trade_bet_shadow", "idx_paper_bet_shadow_session_status", false, "session_id, status");
        ensureIndex(connection, metaData, "paper_trade_bet_shadow", "idx_paper_bet_shadow_session_placed", false, "session_id, placed_at");
        ensureIndex(connection, metaData, "paper_trade_bet_shadow", "idx_paper_bet_shadow_session_settled", false, "session_id, settled_at");
        ensureIndex(connection, metaData, "paper_trade_bet_shadow", "idx_paper_bet_shadow_matchup", false, "player1_id, player2_id");
        ensureIndex(connection, metaData, "paper_trade_bet_shadow", "idx_paper_bet_shadow_dedupe", false, "session_id, dedupe_key");
        ensureIndex(connection, metaData, "paper_trade_bet_shadow", "idx_paper_bet_shadow_mirrored", false, "mirrored_at");
    }

    private void ensureCorrelationColumnIfTableExists(Connection connection,
                                                      DatabaseMetaData metaData,
                                                      String tableName) throws SQLException {
        if (!tableExists(metaData, tableName) || columnExists(metaData, tableName, "correlation_id")) {
            return;
        }
        execute(connection, "ALTER TABLE " + tableName + " ADD COLUMN correlation_id VARCHAR(64)");
    }

    private boolean tableExists(DatabaseMetaData metaData, String tableName) throws SQLException {
        return anyNameMatch(metaData.getTables(null, null, null, new String[]{"TABLE"}), "TABLE_NAME", tableName);
    }

    private boolean columnExists(DatabaseMetaData metaData, String tableName, String columnName) throws SQLException {
        return anyNameMatch(metaData.getColumns(null, null, null, null), "COLUMN_NAME", tableName, "TABLE_NAME", columnName);
    }

    private boolean anyNameMatch(ResultSet resultSet,
                                 String targetColumn,
                                 String expectedValue) throws SQLException {
        try (ResultSet rs = resultSet) {
            while (rs.next()) {
                String actual = rs.getString(targetColumn);
                if (matchesName(actual, expectedValue)) {
                    return true;
                }
            }
            return false;
        }
    }

    private boolean anyNameMatch(ResultSet resultSet,
                                 String primaryColumn,
                                 String primaryExpected,
                                 String secondaryColumn,
                                 String secondaryExpected) throws SQLException {
        try (ResultSet rs = resultSet) {
            while (rs.next()) {
                String primaryActual = rs.getString(primaryColumn);
                String secondaryActual = rs.getString(secondaryColumn);
                if (matchesName(primaryActual, primaryExpected) && matchesName(secondaryActual, secondaryExpected)) {
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
                String actualTable = rs.getString("TABLE_NAME");
                String actualIndex = rs.getString("INDEX_NAME");
                if (matchesName(actualTable, tableName) && matchesName(actualIndex, indexName)) {
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
