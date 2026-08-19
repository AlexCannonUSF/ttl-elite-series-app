DROP INDEX IF EXISTS idx_paper_bet_pending_poll;
DROP INDEX IF EXISTS idx_paper_bet_pending_until;
DROP INDEX IF EXISTS idx_paper_bet_shadow_pending_poll;
DROP INDEX IF EXISTS idx_paper_bet_shadow_pending_until;

ALTER TABLE paper_trade_bet DROP COLUMN IF EXISTS pending_evidence_updated_at;
ALTER TABLE paper_trade_bet DROP COLUMN IF EXISTS pending_evidence_note;
ALTER TABLE paper_trade_bet DROP COLUMN IF EXISTS pending_evidence_reason;
ALTER TABLE paper_trade_bet DROP COLUMN IF EXISTS pending_evidence_next_poll_at;
ALTER TABLE paper_trade_bet DROP COLUMN IF EXISTS pending_evidence_until;

ALTER TABLE paper_trade_bet_shadow DROP COLUMN IF EXISTS pending_evidence_updated_at;
ALTER TABLE paper_trade_bet_shadow DROP COLUMN IF EXISTS pending_evidence_note;
ALTER TABLE paper_trade_bet_shadow DROP COLUMN IF EXISTS pending_evidence_reason;
ALTER TABLE paper_trade_bet_shadow DROP COLUMN IF EXISTS pending_evidence_next_poll_at;
ALTER TABLE paper_trade_bet_shadow DROP COLUMN IF EXISTS pending_evidence_until;
