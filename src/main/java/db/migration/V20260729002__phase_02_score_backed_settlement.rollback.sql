DROP INDEX IF EXISTS idx_settlement_evidence_score_quality;
DROP INDEX IF EXISTS idx_paper_bet_score_evidence;

ALTER TABLE settlement_evidence DROP COLUMN IF EXISTS score_inferred_winner_id;
ALTER TABLE settlement_evidence DROP COLUMN IF EXISTS score_completion_signal_count;
ALTER TABLE settlement_evidence DROP COLUMN IF EXISTS score_source_count;
ALTER TABLE settlement_evidence DROP COLUMN IF EXISTS score_observation_count;
ALTER TABLE settlement_evidence DROP COLUMN IF EXISTS score_evidence_confidence;
ALTER TABLE settlement_evidence DROP COLUMN IF EXISTS score_evidence_finality;
ALTER TABLE settlement_evidence DROP COLUMN IF EXISTS score_evidence_quality;

ALTER TABLE paper_trade_bet DROP COLUMN IF EXISTS score_evidence_contradictory;
ALTER TABLE paper_trade_bet DROP COLUMN IF EXISTS score_evidence_latest_phase;
ALTER TABLE paper_trade_bet DROP COLUMN IF EXISTS score_evidence_latest_score;
ALTER TABLE paper_trade_bet DROP COLUMN IF EXISTS score_evidence_inferred_winner_id;
ALTER TABLE paper_trade_bet DROP COLUMN IF EXISTS score_evidence_completion_signals;
ALTER TABLE paper_trade_bet DROP COLUMN IF EXISTS score_evidence_agreeing_sources;
ALTER TABLE paper_trade_bet DROP COLUMN IF EXISTS score_evidence_source_count;
ALTER TABLE paper_trade_bet DROP COLUMN IF EXISTS score_evidence_observation_count;
ALTER TABLE paper_trade_bet DROP COLUMN IF EXISTS score_evidence_confidence;
ALTER TABLE paper_trade_bet DROP COLUMN IF EXISTS score_evidence_finality;
ALTER TABLE paper_trade_bet DROP COLUMN IF EXISTS score_evidence_quality;
