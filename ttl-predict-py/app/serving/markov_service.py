"""Point-by-point Markov simulator for 11-point table tennis matches."""

from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache
import hashlib
import math
import random
from typing import Optional

from .schemas import MarkovRequest, MarkovResponse

VERSION = "v3.0.0-phase05-markov-1"
SECONDS_PER_POINT = 8.5
MONTE_CARLO_TRIALS = 50_000
EPSILON = 1e-6


@dataclass(frozen=True)
class GameProfile:
    p_top: float
    exp_points: float


@dataclass(frozen=True)
class MatchProfile:
    p_match_top: float
    p_3_0: Optional[float]
    p_3_1: Optional[float]
    p_3_2: Optional[float]
    exp_total_points: float
    method: str


def simulate(request: MarkovRequest) -> MarkovResponse:
    """Run a table-tennis Markov chain for best-of-3/5 or MC for larger matches."""

    p_serve = _bounded_probability(request.pPointTopOnServe)
    p_receive = _bounded_probability(
        request.pPointTopOnReceive
        if request.pPointTopOnReceive is not None
        else p_serve
    )
    best_of = int(request.bestOf)
    top_starts = _game_profile(p_serve, p_receive, top_serves_first=True)
    bottom_starts = _game_profile(p_serve, p_receive, top_serves_first=False)
    if best_of in {3, 5}:
        profile = _closed_form_match(best_of, top_starts, bottom_starts)
    else:
        profile = _monte_carlo_match(request.matchId, best_of, top_starts, bottom_starts)

    return MarkovResponse(
        matchId=request.matchId,
        pMatchTop=profile.p_match_top,
        p_3_0=profile.p_3_0,
        p_3_1=profile.p_3_1,
        p_3_2=profile.p_3_2,
        expTotalPoints=profile.exp_total_points,
        medianMatchMinutes=_median_minutes(profile.exp_total_points),
        method=profile.method,
        version=VERSION,
        note=(
            "Point-by-point 11-point Markov chain with two-serve alternation "
            "and one-serve deuce alternation."
        ),
    )


def _game_profile(p_serve: float, p_receive: float, top_serves_first: bool) -> GameProfile:
    @lru_cache(maxsize=None)
    def state(top_points: int, bottom_points: int) -> tuple[float, float]:
        if top_points >= 11 and top_points - bottom_points >= 2:
            return 1.0, 0.0
        if bottom_points >= 11 and bottom_points - top_points >= 2:
            return 0.0, 0.0

        if top_points >= 10 and bottom_points >= 10:
            if top_points == bottom_points:
                return deuce_state(top_points + bottom_points)
            if top_points == bottom_points + 1:
                p_next = point_probability(top_points, bottom_points)
                deuce_prob, deuce_exp = state(top_points, bottom_points + 1)
                return p_next + (1.0 - p_next) * deuce_prob, 1.0 + (1.0 - p_next) * deuce_exp
            if bottom_points == top_points + 1:
                p_next = point_probability(top_points, bottom_points)
                deuce_prob, deuce_exp = state(top_points + 1, bottom_points)
                return p_next * deuce_prob, 1.0 + p_next * deuce_exp

        p_next = point_probability(top_points, bottom_points)
        win_prob, win_exp = state(top_points + 1, bottom_points)
        lose_prob, lose_exp = state(top_points, bottom_points + 1)
        return (
            p_next * win_prob + (1.0 - p_next) * lose_prob,
            1.0 + p_next * win_exp + (1.0 - p_next) * lose_exp,
        )

    def point_probability(top_points: int, bottom_points: int) -> float:
        top_serving = _top_serves_next(top_serves_first, top_points + bottom_points)
        return p_serve if top_serving else p_receive

    def deuce_state(total_points: int) -> tuple[float, float]:
        p_first = point_probability(total_points // 2, total_points // 2)
        p_second = _point_probability_from_total(p_serve, p_receive, top_serves_first, total_points + 1)
        top_two = p_first * p_second
        bottom_two = (1.0 - p_first) * (1.0 - p_second)
        decisive = max(EPSILON, top_two + bottom_two)
        return top_two / decisive, 2.0 / decisive

    p_game, exp_points = state(0, 0)
    return GameProfile(p_top=_round_probability(p_game), exp_points=exp_points)


def _closed_form_match(best_of: int, top_starts: GameProfile, bottom_starts: GameProfile) -> MatchProfile:
    target = best_of // 2 + 1

    @lru_cache(maxsize=None)
    def walk(top_games: int, bottom_games: int, game_index: int) -> tuple[float, tuple[tuple[int, int, float], ...], float]:
        if top_games == target:
            return 1.0, ((top_games, bottom_games, 1.0),), 0.0
        if bottom_games == target:
            return 0.0, tuple(), 0.0

        profile = top_starts if game_index % 2 == 0 else bottom_starts
        win_p, win_scores, win_exp = walk(top_games + 1, bottom_games, game_index + 1)
        lose_p, lose_scores, lose_exp = walk(top_games, bottom_games + 1, game_index + 1)
        score_probs: dict[tuple[int, int], float] = {}
        for score_top, score_bottom, probability in win_scores:
            score_probs[(score_top, score_bottom)] = score_probs.get((score_top, score_bottom), 0.0) + profile.p_top * probability
        for score_top, score_bottom, probability in lose_scores:
            score_probs[(score_top, score_bottom)] = score_probs.get((score_top, score_bottom), 0.0) + (1.0 - profile.p_top) * probability
        return (
            profile.p_top * win_p + (1.0 - profile.p_top) * lose_p,
            tuple((a, b, p) for (a, b), p in sorted(score_probs.items())),
            profile.exp_points + profile.p_top * win_exp + (1.0 - profile.p_top) * lose_exp,
        )

    p_match, score_tuple, exp_points = walk(0, 0, 0)
    scores = {(top, bottom): p for top, bottom, p in score_tuple}
    if target == 3:
        p_3_0 = scores.get((3, 0), 0.0)
        p_3_1 = scores.get((3, 1), 0.0)
        p_3_2 = scores.get((3, 2), 0.0)
    else:
        p_3_0 = p_3_1 = p_3_2 = None

    return MatchProfile(
        p_match_top=_round_probability(p_match),
        p_3_0=_round_optional_probability(p_3_0),
        p_3_1=_round_optional_probability(p_3_1),
        p_3_2=_round_optional_probability(p_3_2),
        exp_total_points=round(exp_points, 4),
        method=f"closed-form-best-of-{best_of}",
    )


def _monte_carlo_match(
    match_id: str,
    best_of: int,
    top_starts: GameProfile,
    bottom_starts: GameProfile,
) -> MatchProfile:
    target = best_of // 2 + 1
    rng = random.Random(_stable_seed(match_id, best_of, top_starts, bottom_starts))
    top_match_wins = 0
    total_points = 0.0

    for _ in range(MONTE_CARLO_TRIALS):
        top_games = 0
        bottom_games = 0
        game_index = 0
        while top_games < target and bottom_games < target:
            profile = top_starts if game_index % 2 == 0 else bottom_starts
            total_points += profile.exp_points
            if rng.random() < profile.p_top:
                top_games += 1
            else:
                bottom_games += 1
            game_index += 1
        if top_games == target:
            top_match_wins += 1

    return MatchProfile(
        p_match_top=_round_probability(top_match_wins / MONTE_CARLO_TRIALS),
        p_3_0=None,
        p_3_1=None,
        p_3_2=None,
        exp_total_points=round(total_points / MONTE_CARLO_TRIALS, 4),
        method=f"monte-carlo-{MONTE_CARLO_TRIALS}",
    )


def _top_serves_next(top_serves_first: bool, total_points: int) -> bool:
    if total_points >= 20:
        top_turn = (total_points - 20) % 2 == 0
    else:
        top_turn = (total_points // 2) % 2 == 0
    return top_turn if top_serves_first else not top_turn


def _point_probability_from_total(
    p_serve: float,
    p_receive: float,
    top_serves_first: bool,
    total_points: int,
) -> float:
    return p_serve if _top_serves_next(top_serves_first, total_points) else p_receive


def _median_minutes(exp_total_points: float) -> float:
    return round(max(0.0, exp_total_points) * SECONDS_PER_POINT / 60.0, 4)


def _stable_seed(match_id: str, best_of: int, top_starts: GameProfile, bottom_starts: GameProfile) -> int:
    raw = f"{match_id}|{best_of}|{top_starts.p_top:.12f}|{bottom_starts.p_top:.12f}".encode("utf-8")
    return int.from_bytes(hashlib.sha256(raw).digest()[:8], "big")


def _bounded_probability(value: float) -> float:
    if not math.isfinite(value):
        return 0.5
    return min(1.0 - EPSILON, max(EPSILON, float(value)))


def _round_probability(value: float) -> float:
    return round(min(1.0, max(0.0, value)), 10)


def _round_optional_probability(value: Optional[float]) -> Optional[float]:
    if value is None:
        return None
    return _round_probability(value)
