-- Rollback for V20260416001__phase_00_foundations.
-- Drops the two shadow tables and the correlation_id columns added to pre-existing tables.
-- Columns are dropped with IF EXISTS because the parent table may pre-date or post-date this migration.

DROP TABLE IF EXISTS paper_trade_bet_shadow;
DROP TABLE IF EXISTS paper_trade_session_shadow;

ALTER TABLE scrape_run                    DROP COLUMN IF EXISTS correlation_id;
ALTER TABLE scrape_error                  DROP COLUMN IF EXISTS correlation_id;
ALTER TABLE odds_quote                    DROP COLUMN IF EXISTS correlation_id;
ALTER TABLE paper_trade_decision_sample   DROP COLUMN IF EXISTS correlation_id;
ALTER TABLE paper_trade_learning_sample   DROP COLUMN IF EXISTS correlation_id;
ALTER TABLE value_opportunity             DROP COLUMN IF EXISTS correlation_id;
ALTER TABLE tracked_match_observation     DROP COLUMN IF EXISTS correlation_id;
