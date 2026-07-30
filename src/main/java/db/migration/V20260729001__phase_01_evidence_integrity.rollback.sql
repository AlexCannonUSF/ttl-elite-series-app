DROP INDEX IF EXISTS idx_paper_bet_settlement_evidence;
DROP INDEX IF EXISTS uq_settlement_diff_fingerprint;
DROP INDEX IF EXISTS idx_settlement_audit_review;
DROP INDEX IF EXISTS uq_settlement_audit_fingerprint;
DROP INDEX IF EXISTS uq_settlement_evidence_fingerprint;

ALTER TABLE paper_trade_learning_sample DROP COLUMN IF EXISTS closing_market_state;
ALTER TABLE paper_trade_learning_sample DROP COLUMN IF EXISTS closing_source;

ALTER TABLE paper_trade_bet DROP COLUMN IF EXISTS closing_market_state;
ALTER TABLE paper_trade_bet DROP COLUMN IF EXISTS closing_source;
ALTER TABLE paper_trade_bet DROP COLUMN IF EXISTS closing_observed_at;
ALTER TABLE paper_trade_bet DROP COLUMN IF EXISTS closing_decimal_odds;
ALTER TABLE paper_trade_bet DROP COLUMN IF EXISTS settlement_observed_at;
ALTER TABLE paper_trade_bet DROP COLUMN IF EXISTS settlement_ambiguity_score;
ALTER TABLE paper_trade_bet DROP COLUMN IF EXISTS settlement_coverage_state;
ALTER TABLE paper_trade_bet DROP COLUMN IF EXISTS settlement_evidence_source_count;
ALTER TABLE paper_trade_bet DROP COLUMN IF EXISTS settlement_evidence_fingerprint;
ALTER TABLE paper_trade_bet DROP COLUMN IF EXISTS settlement_evidence_id;
ALTER TABLE paper_trade_bet DROP COLUMN IF EXISTS settlement_confidence;

ALTER TABLE settlement_diff_log DROP COLUMN IF EXISTS diff_fingerprint;
ALTER TABLE settlement_audit DROP COLUMN IF EXISTS review_decision_id;
ALTER TABLE settlement_audit DROP COLUMN IF EXISTS review_status;
ALTER TABLE settlement_audit DROP COLUMN IF EXISTS decision_fingerprint;
ALTER TABLE settlement_evidence DROP COLUMN IF EXISTS evidence_fingerprint;
