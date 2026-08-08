DROP INDEX IF EXISTS idx_settlement_evidence_learning;
DROP INDEX IF EXISTS idx_paper_learning_eligible_event;

ALTER TABLE settlement_evidence DROP COLUMN IF EXISTS learning_exclusion_reason;
ALTER TABLE settlement_evidence DROP COLUMN IF EXISTS learning_eligible;

ALTER TABLE paper_trade_learning_sample DROP COLUMN IF EXISTS learning_exclusion_reason;
ALTER TABLE paper_trade_learning_sample DROP COLUMN IF EXISTS learning_eligible;
