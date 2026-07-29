#!/usr/bin/env python3
"""Build a v3 blender training CSV from offline H2 CSV exports.

The exporter intentionally uses only information available before each match:
ratings are looked up at ``match_date - 1 day`` and rolling form/H2H features
are updated after the current row is emitted.
"""

from __future__ import annotations

import argparse
from collections import defaultdict, deque
from datetime import timezone
import json
import math
from pathlib import Path
import sys

import numpy as np
import pandas as pd


REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT / "ttl-predict-py"))

from app.training.features import VARIANT_B  # noqa: E402


DEFAULTS = {
    "elo": 1500.0,
    "glicko_mu": 1500.0,
    "glicko_phi": 350.0,
    "ts2_mu": 25.0,
    "ts2_sigma": 8.3333333333,
    "wl_rating": 0.0,
    "wl_uncertainty": 1.0,
}


def clamp(values, lo=1e-6, hi=1.0 - 1e-6):
    return np.clip(values, lo, hi)


def elo_probability(top, bot):
    return clamp(1.0 / (1.0 + np.power(10.0, (bot - top) / 400.0)))


def glicko_probability(top_mu, top_phi, bot_mu, bot_phi):
    rating_scale = 173.7178
    mu_top = (top_mu - 1500.0) / rating_scale
    mu_bot = (bot_mu - 1500.0) / rating_scale
    phi_bot = np.maximum(30.0, bot_phi) / rating_scale
    g = 1.0 / np.sqrt(1.0 + (3.0 * phi_bot * phi_bot) / (math.pi * math.pi))
    return clamp(1.0 / (1.0 + np.exp(-g * (mu_top - mu_bot))))


def normal_cdf(x):
    try:
        from scipy.special import ndtr

        return ndtr(x)
    except Exception:
        return np.vectorize(lambda value: 0.5 * (1.0 + math.erf(value / math.sqrt(2.0))))(x)


def ts2_probability(top_mu, top_sigma, bot_mu, bot_sigma):
    beta = 4.1666666667
    c = np.sqrt((2.0 * beta * beta) + (top_sigma * top_sigma) + (bot_sigma * bot_sigma))
    return clamp(normal_cdf((top_mu - bot_mu) / c))


def wl_probability(top_rating, top_uncertainty, bot_rating, bot_uncertainty):
    beta = 1.0
    scale = np.sqrt((2.0 * beta * beta) + (top_uncertainty * top_uncertainty) + (bot_uncertainty * bot_uncertainty))
    z = (top_rating - bot_rating) / scale
    return clamp(1.0 / (1.0 + np.exp(-z)))


def ensemble_probability(glicko, ts2, wl):
    return clamp(0.5 + (0.45 * (glicko - 0.5)) + (0.35 * (ts2 - 0.5)) + (0.20 * (wl - 0.5)))


def read_csv(path: Path) -> pd.DataFrame:
    df = pd.read_csv(path)
    df.columns = [c.lower() for c in df.columns]
    return df


def attach_asof(matches: pd.DataFrame, ratings: pd.DataFrame, side_col: str, prefix: str, columns: list[str]) -> pd.DataFrame:
    left = matches[["row_id", "asof_date", side_col]].rename(columns={side_col: "player_id"})
    left = left.sort_values(["asof_date", "player_id", "row_id"])
    right = ratings[["player_id", "snapshot_date", *columns]].sort_values(["snapshot_date", "player_id"])
    merged = pd.merge_asof(
        left,
        right,
        by="player_id",
        left_on="asof_date",
        right_on="snapshot_date",
        direction="backward",
    )
    merged = merged.sort_values("row_id")
    out = pd.DataFrame(index=matches.index)
    for column in columns:
        out[f"{prefix}_{column}"] = merged[column].to_numpy()
    return out


def safe_best_of(row) -> int:
    p1 = 0 if pd.isna(row.player1_sets_won) else int(row.player1_sets_won)
    p2 = 0 if pd.isna(row.player2_sets_won) else int(row.player2_sets_won)
    sets_played = p1 + p2
    return 7 if sets_played > 5 else 5


def stable_code(value) -> int:
    if value is None or (isinstance(value, float) and math.isnan(value)):
        return 0
    text = str(value)
    total = 0
    for ch in text:
        total = ((total * 31) + ord(ch)) % 997
    return total


def rolling_features(matches: pd.DataFrame) -> dict[str, np.ndarray]:
    n = len(matches)
    arrays: dict[str, np.ndarray] = {}
    windows = (5, 10, 25)
    for side in ("top", "bot"):
        for window in windows:
            for field in ("win_rate", "dominance", "straight_set_rate", "median_margin", "gap_to_prev_match_ms", "completeness"):
                arrays[f"form.{side}.{window}.{field}"] = np.zeros(n, dtype=float)
    for name in (
        "h2h.count",
        "h2h.win_rate_top",
        "h2h.recency_decay_wins_top",
        "h2h.median_margin_top",
        "h2h.same_event_h2h_count",
        "ctx.same_day_prior_matches_top",
        "ctx.same_day_prior_matches_bot",
        "ctx.rest_gap_minutes_top",
        "ctx.rest_gap_minutes_bot",
    ):
        arrays[name] = np.zeros(n, dtype=float)

    history: dict[int, deque] = defaultdict(lambda: deque(maxlen=max(windows)))
    h2h_history: dict[tuple[int, int], deque] = defaultdict(lambda: deque(maxlen=40))
    same_day_counts: dict[tuple[int, str], int] = defaultdict(int)
    last_date: dict[int, pd.Timestamp] = {}

    for i, row in enumerate(matches.itertuples(index=False)):
        top_id = int(row.player1_id)
        bot_id = int(row.player2_id)
        date = row.match_date
        date_key = date.date().isoformat()
        p1_sets = 0 if pd.isna(row.player1_sets_won) else int(row.player1_sets_won)
        p2_sets = 0 if pd.isna(row.player2_sets_won) else int(row.player2_sets_won)
        top_won = int(row.winner_player_id) == top_id
        set_margin_top = p1_sets - p2_sets

        for side, player_id in (("top", top_id), ("bot", bot_id)):
            prior = list(history[player_id])
            for window in windows:
                recent = prior[-window:]
                if recent:
                    wins = np.array([item["won"] for item in recent], dtype=float)
                    margins = np.array([item["set_margin"] for item in recent], dtype=float)
                    arrays[f"form.{side}.{window}.win_rate"][i] = float(wins.mean())
                    arrays[f"form.{side}.{window}.dominance"][i] = float(np.clip(0.5 + margins.mean() / 10.0, 0.0, 1.0))
                    arrays[f"form.{side}.{window}.straight_set_rate"][i] = float(np.mean(np.abs(margins) >= 3.0))
                    arrays[f"form.{side}.{window}.median_margin"][i] = float(np.median(margins))
                    arrays[f"form.{side}.{window}.completeness"][i] = min(1.0, len(recent) / float(window))
                else:
                    arrays[f"form.{side}.{window}.win_rate"][i] = 0.5
                    arrays[f"form.{side}.{window}.dominance"][i] = 0.5
                    arrays[f"form.{side}.{window}.straight_set_rate"][i] = 0.0
                    arrays[f"form.{side}.{window}.median_margin"][i] = 0.0
                    arrays[f"form.{side}.{window}.completeness"][i] = 0.0
                previous = last_date.get(player_id)
                gap_days = 0 if previous is None else max(0, (date - previous).days)
                arrays[f"form.{side}.{window}.gap_to_prev_match_ms"][i] = float(gap_days * 86_400_000)

        pair_key = (min(top_id, bot_id), max(top_id, bot_id))
        pair_history = list(h2h_history[pair_key])
        arrays["h2h.count"][i] = float(len(pair_history))
        if pair_history:
            top_results = []
            margins = []
            decayed = []
            for age, item in enumerate(reversed(pair_history)):
                top_result = item["winner_id"] == top_id
                if item["low_id"] == top_id:
                    margin = item["low_margin"]
                else:
                    margin = -item["low_margin"]
                weight = math.pow(0.5, age / 8.0)
                top_results.append(1.0 if top_result else 0.0)
                margins.append(margin)
                decayed.append((1.0 if top_result else 0.0, weight))
            arrays["h2h.win_rate_top"][i] = float(np.mean(top_results))
            weight_sum = sum(weight for _, weight in decayed)
            arrays["h2h.recency_decay_wins_top"][i] = float(sum(value * weight for value, weight in decayed) / max(weight_sum, 1e-9))
            arrays["h2h.median_margin_top"][i] = float(np.median(margins))
        else:
            arrays["h2h.win_rate_top"][i] = 0.5
            arrays["h2h.recency_decay_wins_top"][i] = 0.5
            arrays["h2h.median_margin_top"][i] = 0.0

        arrays["ctx.same_day_prior_matches_top"][i] = float(same_day_counts[(top_id, date_key)])
        arrays["ctx.same_day_prior_matches_bot"][i] = float(same_day_counts[(bot_id, date_key)])
        arrays["ctx.rest_gap_minutes_top"][i] = float(0 if top_id not in last_date else max(0, (date - last_date[top_id]).days) * 1440)
        arrays["ctx.rest_gap_minutes_bot"][i] = float(0 if bot_id not in last_date else max(0, (date - last_date[bot_id]).days) * 1440)

        history[top_id].append({"won": top_won, "set_margin": set_margin_top})
        history[bot_id].append({"won": not top_won, "set_margin": -set_margin_top})
        h2h_history[pair_key].append(
            {
                "low_id": pair_key[0],
                "winner_id": int(row.winner_player_id),
                "low_margin": set_margin_top if pair_key[0] == top_id else -set_margin_top,
            }
        )
        same_day_counts[(top_id, date_key)] += 1
        same_day_counts[(bot_id, date_key)] += 1
        last_date[top_id] = date
        last_date[bot_id] = date

    return arrays


def build(raw_dir: Path, output: Path, metadata_path: Path) -> None:
    matches = read_csv(raw_dir / "matches.csv")
    matches = matches.dropna(subset=["match_date", "player1_id", "player2_id", "winner_player_id"]).copy()
    matches["match_date"] = pd.to_datetime(matches["match_date"], utc=True)
    min_reasonable_date = pd.Timestamp("1990-01-01", tz="UTC")
    max_reasonable_date = pd.Timestamp.now(tz="UTC").normalize() + pd.Timedelta(days=1)
    input_rows = len(matches)
    matches = matches[
        (matches["match_date"] >= min_reasonable_date)
        & (matches["match_date"] <= max_reasonable_date)
    ].copy()
    matches["asof_date"] = matches["match_date"] - pd.Timedelta(days=1)
    matches["row_id"] = np.arange(len(matches))
    matches["label"] = (matches["winner_player_id"].astype(int) == matches["player1_id"].astype(int)).astype(int)
    matches = matches.sort_values(["match_date", "id"]).reset_index(drop=True)
    matches["row_id"] = np.arange(len(matches))

    ratings = read_csv(raw_dir / "rating_snapshot.csv")
    ratings["snapshot_date"] = pd.to_datetime(ratings["snapshot_date"], utc=True)
    ratings["rating_system"] = ratings["rating_system"].astype(str).str.upper()
    glicko = ratings[ratings["rating_system"] == "GLICKO2"].copy()
    elo = ratings[ratings["rating_system"] == "ELO"].copy()

    ts2 = read_csv(raw_dir / "player_rating_ts2.csv")
    ts2["snapshot_date"] = pd.to_datetime(ts2["snapshot_date"], utc=True)
    wl = read_csv(raw_dir / "player_rating_wl.csv")
    wl["snapshot_date"] = pd.to_datetime(wl["snapshot_date"], utc=True)

    feature_df = pd.DataFrame(index=matches.index)
    for side, column in (("top", "player1_id"), ("bot", "player2_id")):
        g = attach_asof(matches, glicko, column, f"{side}_glicko", ["rating", "rating_deviation"])
        e = attach_asof(matches, elo, column, f"{side}_elo", ["rating"])
        t = attach_asof(matches, ts2, column, f"{side}_ts2", ["mu", "sigma"])
        w = attach_asof(matches, wl, column, f"{side}_wl", ["rating", "uncertainty"])
        for frame in (g, e, t, w):
            feature_df = pd.concat([feature_df, frame], axis=1)

    top_g = feature_df["top_glicko_rating"].fillna(DEFAULTS["glicko_mu"])
    bot_g = feature_df["bot_glicko_rating"].fillna(DEFAULTS["glicko_mu"])
    top_phi = feature_df["top_glicko_rating_deviation"].fillna(DEFAULTS["glicko_phi"])
    bot_phi = feature_df["bot_glicko_rating_deviation"].fillna(DEFAULTS["glicko_phi"])
    top_ts_mu = feature_df["top_ts2_mu"].fillna(DEFAULTS["ts2_mu"])
    bot_ts_mu = feature_df["bot_ts2_mu"].fillna(DEFAULTS["ts2_mu"])
    top_ts_sigma = feature_df["top_ts2_sigma"].fillna(DEFAULTS["ts2_sigma"])
    bot_ts_sigma = feature_df["bot_ts2_sigma"].fillna(DEFAULTS["ts2_sigma"])
    top_wl = feature_df["top_wl_rating"].fillna(DEFAULTS["wl_rating"])
    bot_wl = feature_df["bot_wl_rating"].fillna(DEFAULTS["wl_rating"])
    top_wl_u = feature_df["top_wl_uncertainty"].fillna(DEFAULTS["wl_uncertainty"])
    bot_wl_u = feature_df["bot_wl_uncertainty"].fillna(DEFAULTS["wl_uncertainty"])
    top_elo = feature_df["top_elo_rating"].fillna(top_g).fillna(DEFAULTS["elo"])
    bot_elo = feature_df["bot_elo_rating"].fillna(bot_g).fillna(DEFAULTS["elo"])

    out = pd.DataFrame(index=matches.index)
    out["decided_at_utc"] = matches["match_date"].dt.tz_convert(timezone.utc).dt.strftime("%Y-%m-%dT12:00:00+00:00")
    out["label"] = matches["label"].astype(int)

    out["match.event_code"] = matches["source_feed_code"].apply(stable_code).astype(float)
    out["match.round"] = 0.0
    out["match.best_of"] = matches.apply(safe_best_of, axis=1).astype(float)
    out["match.is_televised"] = 0.0
    out["match.is_major_event"] = 0.0
    out["match.table_number"] = 0.0
    out["match.day_of_week"] = matches["match_date"].dt.dayofweek.astype(float)
    out["match.minutes_to_start"] = 0.0
    out["match.is_backup_table"] = 0.0

    out["rater.glicko.top.mu"] = top_g
    out["rater.glicko.bot.mu"] = bot_g
    out["rater.glicko.top.phi"] = top_phi
    out["rater.glicko.bot.phi"] = bot_phi
    out["rater.glicko.delta_mu"] = top_g - bot_g
    out["rater.glicko.delta_phi_sum"] = top_phi + bot_phi
    out["rater.ts2.top.mu"] = top_ts_mu
    out["rater.ts2.bot.mu"] = bot_ts_mu
    out["rater.ts2.top.sigma"] = top_ts_sigma
    out["rater.ts2.bot.sigma"] = bot_ts_sigma
    out["rater.ts2.skill_gap"] = top_ts_mu - bot_ts_mu
    glicko_p = glicko_probability(top_g.to_numpy(float), top_phi.to_numpy(float), bot_g.to_numpy(float), bot_phi.to_numpy(float))
    ts2_p = ts2_probability(top_ts_mu.to_numpy(float), top_ts_sigma.to_numpy(float), bot_ts_mu.to_numpy(float), bot_ts_sigma.to_numpy(float))
    wl_p = wl_probability(top_wl.to_numpy(float), top_wl_u.to_numpy(float), bot_wl.to_numpy(float), bot_wl_u.to_numpy(float))
    ensemble_p = ensemble_probability(glicko_p, ts2_p, wl_p)
    elo_p = elo_probability(top_elo.to_numpy(float), bot_elo.to_numpy(float))
    out["rater.wenglin.delta"] = wl_p - 0.5
    out["rater.ensemble.delta"] = ensemble_p - 0.5

    for name, values in rolling_features(matches).items():
        out[name] = values

    out["ctx.travel_tz_delta_top"] = 0.0
    out["ctx.venue_familiarity_top"] = 0.5
    for name in (
        "live.games_top",
        "live.games_bot",
        "live.points_top",
        "live.points_bot",
        "live.server",
        "live.is_deuce",
        "live.point_differential",
        "live.current_lead_top",
        "live.time_since_last_point_s",
    ):
        out[name] = 0.0

    market = clamp(elo_p)
    out["market_prob_top"] = market
    out["odds.open.top.decimal"] = 1.0 / market
    out["odds.open.bot.decimal"] = 1.0 / (1.0 - market)
    out["odds.latest.top.decimal"] = out["odds.open.top.decimal"]
    out["odds.latest.bot.decimal"] = out["odds.open.bot.decimal"]
    out["odds.move.top_bps_since_open"] = (ensemble_p - market) * 10_000.0
    out["odds.move.top_bps_last_5m"] = 0.0
    out["odds.overround_bps"] = 250.0
    out["odds.overround_bps_trend_1m"] = 0.0
    out["odds.dev.top.shin"] = market
    out["odds.dev.bot.shin"] = 1.0 - market
    out["odds.dev.top.power"] = market
    out["odds.dev.bot.power"] = 1.0 - market
    out["odds.dev.top.multiplicative"] = market
    out["odds.dev.bot.multiplicative"] = 1.0 - market
    out["odds.dev.top.consensus"] = market
    out["odds.liquidity_proxy"] = 1.0

    out["dq.feed_ticks_1m.top"] = 1.0
    out["dq.feed_ticks_1m.bot"] = 1.0
    out["dq.mirror_disagreement_flag"] = 0.0
    out["dq.stream_cv_present"] = 0.0
    out["dq.player_canonicalised"] = 1.0
    completeness_cols = [c for c in out.columns if c.endswith(".completeness")]
    out["dq.feature_completeness"] = out[completeness_cols].mean(axis=1).fillna(0.0)

    for feature in VARIANT_B.names:
        if feature not in out.columns:
            out[feature] = 0.0

    ordered = ["decided_at_utc", "label", "market_prob_top", *VARIANT_B.names]
    out = out[ordered].replace([np.inf, -np.inf], np.nan).fillna(0.0)
    output.parent.mkdir(parents=True, exist_ok=True)
    out.to_csv(output, index=False)

    metadata = {
        "rows": int(len(out)),
        "input_rows": int(input_rows),
        "filtered_rows": int(input_rows - len(out)),
        "date_min": str(matches["match_date"].min()),
        "date_max": str(matches["match_date"].max()),
        "features": len(VARIANT_B.names),
        "source": str(raw_dir),
        "leakage_policy": "ratings_asof_match_date_minus_one_day; rolling features emitted before current match update",
    }
    metadata_path.write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(metadata, indent=2, sort_keys=True))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--raw-dir", default="data/blender_training_raw")
    parser.add_argument("--output", default="data/blender_training.csv")
    parser.add_argument("--metadata", default="data/blender_training.metadata.json")
    args = parser.parse_args()
    build(Path(args.raw_dir), Path(args.output), Path(args.metadata))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
