DROP INDEX IF EXISTS uk_paper_trade_single_active;
ALTER TABLE paper_trade_session DROP COLUMN IF EXISTS active_guard;
