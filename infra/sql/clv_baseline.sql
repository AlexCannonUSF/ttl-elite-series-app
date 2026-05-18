WITH bet_window AS (
    SELECT
        b.id AS bet_id,
        b.placed_at,
        b.settled_at,
        b.implied_probability AS placed_implied_probability,
        COALESCE(NULLIF(TRIM(b.locked_external_event_id), ''), NULLIF(TRIM(b.external_event_id), '')) AS booker_event_id,
        CASE
            WHEN b.side_player_id IS NOT NULL AND b.player1_id IS NOT NULL AND b.side_player_id = b.player1_id THEN 'P1'
            WHEN b.side_player_id IS NOT NULL AND b.player2_id IS NOT NULL AND b.side_player_id = b.player2_id THEN 'P2'
            WHEN LOWER(TRIM(COALESCE(b.side_name, ''))) = LOWER(TRIM(COALESCE(b.player1_name, ''))) THEN 'P1'
            WHEN LOWER(TRIM(COALESCE(b.side_name, ''))) = LOWER(TRIM(COALESCE(b.player2_name, ''))) THEN 'P2'
            ELSE NULL
        END AS snapshot_side
    FROM paper_trade_bet b
    WHERE b.placed_at >= TIMESTAMPADD(DAY, -7, CURRENT_TIMESTAMP)
      AND COALESCE(NULLIF(TRIM(b.locked_external_event_id), ''), NULLIF(TRIM(b.external_event_id), '')) IS NOT NULL
),
closing_candidates AS (
    SELECT
        bw.bet_id,
        os.observed_at AS closing_observed_at,
        os.implied_prob AS closing_implied_probability,
        os.market_state,
        ROW_NUMBER() OVER (
            PARTITION BY bw.bet_id
            ORDER BY
                CASE
                    WHEN os.market_state = 'CLOSED' THEN 0
                    WHEN os.market_state = 'SUSPENDED' THEN 1
                    ELSE 2
                END,
                os.observed_at DESC
        ) AS row_num
    FROM bet_window bw
    JOIN odds_snapshot os
      ON os.booker_event_id = bw.booker_event_id
     AND os.side = bw.snapshot_side
     AND os.observed_at >= bw.placed_at
     AND os.observed_at <= COALESCE(bw.settled_at, TIMESTAMPADD(HOUR, 6, bw.placed_at))
),
resolved AS (
    SELECT
        bw.bet_id,
        bw.placed_at,
        bw.placed_implied_probability,
        cc.closing_implied_probability,
        cc.closing_observed_at
    FROM bet_window bw
    LEFT JOIN closing_candidates cc
      ON cc.bet_id = bw.bet_id
     AND cc.row_num = 1
)
SELECT
    TIMESTAMPADD(DAY, -7, CURRENT_TIMESTAMP) AS window_start_utc,
    CURRENT_TIMESTAMP AS window_end_utc,
    COUNT(*) AS bets_in_window,
    SUM(CASE WHEN closing_implied_probability IS NOT NULL THEN 1 ELSE 0 END) AS bets_with_closing_snapshot,
    ROUND(
        COALESCE(
            SUM(CASE WHEN closing_implied_probability IS NOT NULL THEN 1 ELSE 0 END) * 1.0 / NULLIF(COUNT(*), 0),
            0.0
        ),
        4
    ) AS coverage_ratio,
    ROUND(COALESCE(AVG(closing_implied_probability - placed_implied_probability), 0.0), 6) AS clv_baseline,
    ROUND(COALESCE(AVG(placed_implied_probability), 0.0), 6) AS avg_placed_implied_probability,
    ROUND(COALESCE(AVG(closing_implied_probability), 0.0), 6) AS avg_closing_implied_probability,
    MIN(closing_observed_at) AS first_closing_snapshot_at,
    MAX(closing_observed_at) AS last_closing_snapshot_at
FROM resolved;
