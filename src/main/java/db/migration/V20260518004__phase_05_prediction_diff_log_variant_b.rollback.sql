-- Rollback for V20260518004__phase_05_prediction_diff_log_variant_b.
-- Removes the three Variant-B columns added to prediction_diff_log.

ALTER TABLE prediction_diff_log DROP COLUMN IF EXISTS variant_ab_abs_diff;
ALTER TABLE prediction_diff_log DROP COLUMN IF EXISTS v3_variant_b_p1_probability;
ALTER TABLE prediction_diff_log DROP COLUMN IF EXISTS v3_variant_b_model_version;
