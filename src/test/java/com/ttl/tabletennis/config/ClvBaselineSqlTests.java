package com.ttl.tabletennis.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClvBaselineSqlTests {

    @Test
    void clvBaselineSqlReturnsNonNullBaselineForRepresentativePhase01Data() throws Exception {
        String url = "jdbc:h2:mem:clv-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE paper_trade_bet (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        external_event_id VARCHAR(96),
                        locked_external_event_id VARCHAR(96),
                        player1_id BIGINT,
                        player2_id BIGINT,
                        side_player_id BIGINT,
                        player1_name VARCHAR(180),
                        player2_name VARCHAR(180),
                        side_name VARCHAR(180),
                        implied_probability DOUBLE NOT NULL,
                        placed_at TIMESTAMP NOT NULL,
                        settled_at TIMESTAMP NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE odds_snapshot (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        booker_event_id VARCHAR(128),
                        side VARCHAR(4) NOT NULL,
                        implied_prob DOUBLE NOT NULL,
                        market_state VARCHAR(24) NOT NULL,
                        observed_at TIMESTAMP NOT NULL
                    )
                    """);

            statement.execute("""
                    INSERT INTO paper_trade_bet (
                        external_event_id, locked_external_event_id, player1_id, player2_id, side_player_id,
                        player1_name, player2_name, side_name, implied_probability, placed_at, settled_at
                    ) VALUES (
                        'ev-100', NULL, 10, 20, 10,
                        'Adrian Adach', 'Damian Fira', 'Adrian Adach', 0.400000,
                        DATEADD('HOUR', -2, CURRENT_TIMESTAMP), DATEADD('MINUTE', -30, CURRENT_TIMESTAMP)
                    )
                    """);
            statement.execute("""
                    INSERT INTO odds_snapshot (booker_event_id, side, implied_prob, market_state, observed_at) VALUES
                    ('ev-100', 'P1', 0.410000, 'OPEN', DATEADD('MINUTE', -100, CURRENT_TIMESTAMP)),
                    ('ev-100', 'P1', 0.455000, 'SUSPENDED', DATEADD('MINUTE', -40, CURRENT_TIMESTAMP)),
                    ('ev-100', 'P1', 0.460000, 'CLOSED', DATEADD('MINUTE', -35, CURRENT_TIMESTAMP)),
                    ('ev-100', 'P2', 0.540000, 'CLOSED', DATEADD('MINUTE', -35, CURRENT_TIMESTAMP))
                    """);

            String sql = Files.readString(Path.of("infra", "sql", "clv_baseline.sql"));
            try (ResultSet rs = statement.executeQuery(sql)) {
                assertTrue(rs.next());
                assertEquals(1L, rs.getLong("bets_in_window"));
                assertEquals(1L, rs.getLong("bets_with_closing_snapshot"));
                assertEquals(1.0, rs.getDouble("coverage_ratio"), 1.0e-9);
                assertEquals(0.06, rs.getDouble("clv_baseline"), 1.0e-9);
                assertNotNull(rs.getTimestamp("last_closing_snapshot_at"));
            }
        }
    }
}
