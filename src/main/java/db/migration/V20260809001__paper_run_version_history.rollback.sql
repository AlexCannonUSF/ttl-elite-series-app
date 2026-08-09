ALTER TABLE paper_trade_session DROP COLUMN IF EXISTS closed_at;
ALTER TABLE paper_trade_session DROP COLUMN IF EXISTS code_revision;
ALTER TABLE paper_trade_session DROP COLUMN IF EXISTS policy_version;
ALTER TABLE paper_trade_session DROP COLUMN IF EXISTS effective_model_family;
ALTER TABLE paper_trade_session DROP COLUMN IF EXISTS effective_model_version;
ALTER TABLE paper_trade_session DROP COLUMN IF EXISTS requested_model_version;
