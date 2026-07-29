"""Model card generator for the LightGBM blender.

Writes ``model_card.md`` + ``gate_report.json`` to the artefact directory,
following Prediction Engine Spec §10 + §11.1. The card is regenerated on
every training run; the committed copy under
``models/prediction/variant-a-v3.0.0/model_card.md`` is the template that
ships with the harness — it is overwritten by ``train_blender(...)``.
"""

from __future__ import annotations

from datetime import datetime, timezone
import json
from pathlib import Path
from typing import Mapping

from . import gates
from .features import FeatureCatalogue
from .walk_forward import WalkForwardSlice


def render_model_card(
    *,
    variant: str,
    catalogue: FeatureCatalogue,
    slice_: WalkForwardSlice,
    report: gates.GateReport,
    booster_metadata: Mapping[str, object],
) -> str:
    lines: list[str] = []
    lines.append(f"# LightGBM Blender — Variant {variant.upper()}")
    lines.append("")
    lines.append(
        "Auto-generated model card. Regenerated on every nightly walk-forward "
        "refit (Prediction Engine Spec §6.4)."
    )
    lines.append("")

    lines.append("## Identity")
    lines.append("")
    lines.append(f"- **Model version:** `{booster_metadata.get('model_version', 'unknown')}`")
    lines.append(f"- **Feature registry:** `{catalogue.name}`")
    lines.append(f"- **Feature schema hash:** `{catalogue.schema_hash()}`")
    lines.append(f"- **Trained at UTC:** `{booster_metadata.get('trained_at_utc', '')}`")
    lines.append(f"- **Best iteration:** `{booster_metadata.get('best_iteration', '')}`")
    lines.append("")

    lines.append("## Training cohort")
    lines.append("")
    lines.append("| Slice | Start (UTC) | End (UTC) | Rows |")
    lines.append("| --- | --- | --- | --- |")
    lines.append(
        f"| Train | `{slice_.train_start.isoformat()}` | `{slice_.train_end.isoformat()}` | `{booster_metadata.get('train_rows', '')}` |"
    )
    lines.append(
        f"| Validation | `{slice_.validation_start.isoformat()}` | `{slice_.validation_end.isoformat()}` | `{booster_metadata.get('validation_rows', '')}` |"
    )
    lines.append(
        f"| Test | `{slice_.test_start.isoformat()}` | `{slice_.test_end.isoformat()}` | `{booster_metadata.get('test_rows', '')}` |"
    )
    lines.append("")
    lines.append(f"Purge gap between slices: `{int(slice_.purge_gap.total_seconds())}s`.")
    lines.append("")

    lines.append("## Hyperparameters (§6.3)")
    lines.append("")
    lines.append("```yaml")
    params = booster_metadata.get("params", {})
    if isinstance(params, dict):
        for key in sorted(params.keys()):
            lines.append(f"{key}: {params[key]}")
    lines.append("num_boost_round: 1500")
    lines.append("early_stopping_rounds: 50")
    lines.append("```")
    lines.append("")

    lines.append("## Acceptance gates (§6.4 + §7.5)")
    lines.append("")
    lines.append("| Metric | Value | Pass |")
    lines.append("| --- | --- | --- |")
    lines.append(f"| ECE (15-bin, equal-mass) | `{report.ece:.4f}` | `{report.passes.get('ece_le_threshold', False)}` |")
    lines.append(f"| Max bin deviation | `{report.max_bin_deviation:.4f}` | `{report.passes.get('max_bin_le_threshold', False)}` |")
    lines.append(f"| Brier score | `{report.brier_score:.4f}` | — |")
    lines.append(f"| Brier skill score vs. market | `{report.brier_skill_score:.4f}` | `{report.passes.get('bss_ge_threshold', False)}` |")
    lines.append(f"| Bins within 2σ | — | `{report.passes.get('bins_within_sigma', False)}` |")
    lines.append("")
    lines.append(f"**Overall pass:** `{report.overall_pass()}`")
    lines.append("")

    lines.append("## Caveats")
    lines.append("")
    lines.append(
        "- Variant A intentionally excludes §3.6 market features so the model "
        "competes against the market rather than copying it; do not enable "
        "the `with-market` variant for edge detection."
    )
    lines.append(
        "- Any material regression on the gates above blocks promotion. The "
        "promotion record (`promotion_record.yaml`) is committed alongside "
        "the artefact when promotion is approved."
    )
    lines.append(
        "- A model whose feature schema hash differs from the live "
        "`FeatureBuilder` will refuse to score — §3.10 hard-error contract."
    )
    lines.append("")
    return "\n".join(lines) + "\n"


def write_model_card(
    *,
    directory: Path,
    variant: str,
    catalogue: FeatureCatalogue,
    slice_: WalkForwardSlice,
    report: gates.GateReport,
    booster_metadata: Mapping[str, object],
) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    model_card_path = directory / "model_card.md"
    model_card_path.write_text(
        render_model_card(
            variant=variant,
            catalogue=catalogue,
            slice_=slice_,
            report=report,
            booster_metadata=booster_metadata,
        ),
        encoding="utf-8",
    )
    gate_path = directory / "gate_report.json"
    gate_path.write_text(
        json.dumps(
            {
                "trained_at_utc": booster_metadata.get(
                    "trained_at_utc", datetime.now(timezone.utc).isoformat()
                ),
                "ece": report.ece,
                "max_bin_deviation": report.max_bin_deviation,
                "brier_score": report.brier_score,
                "brier_skill_score": report.brier_skill_score,
                "overall_pass": report.overall_pass(),
                "passes": dict(report.passes),
                "bins": [
                    {
                        "lower": b.lower,
                        "upper": b.upper,
                        "count": b.count,
                        "mean_predicted": b.mean_predicted,
                        "mean_observed": b.mean_observed,
                    }
                    for b in report.bins
                ],
            },
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )
    return model_card_path
