-- Rollback for V20260517002__phase_04_settlement_audit_evidence_refs.
-- Removes the evidence_refs LONGTEXT column added to settlement_audit.

ALTER TABLE settlement_audit DROP COLUMN IF EXISTS evidence_refs;
