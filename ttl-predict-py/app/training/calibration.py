"""Calibrators for the v3 blender (Prediction Engine Spec §7 + §8).

Three artefacts are produced per training run:

- ``platt.json`` — Stage 1 Platt scaling (logistic regression on the
  raw logit → label).
- ``isotonic.json`` — Stage 2 isotonic regression on the Platt outputs.
- ``conformal.json`` — Mondrian split-conformal quantiles, conditioned on
  ``(best_of, is_in_play, is_major_event)`` per §8.3.

Each writer's ``to_dict()`` shape is the contract Java consumes; the Java
loader in ``prediction.calibration`` mirrors these field names exactly.
"""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field
from math import exp, log
from typing import Iterable, Mapping, Sequence


EPSILON = 1e-6
DEFAULT_ALPHA = 0.1


def _clip(p: float) -> float:
    if p < EPSILON:
        return EPSILON
    if p > 1.0 - EPSILON:
        return 1.0 - EPSILON
    return p


def _sigmoid(z: float) -> float:
    if z >= 0:
        e = exp(-z)
        return 1.0 / (1.0 + e)
    e = exp(z)
    return e / (1.0 + e)


def _logit(p: float) -> float:
    p = _clip(p)
    return log(p / (1.0 - p))


# ---------------------------------------------------------------------------
# Platt scaling (logistic regression on a single logit feature).
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class PlattCalibrator:
    coef: float
    intercept: float
    version: str = "v3.0.0"

    def apply(self, probs: Sequence[float]) -> list[float]:
        return [_sigmoid(self.coef * _logit(p) + self.intercept) for p in probs]

    def to_dict(self) -> dict:
        return {
            "type": "platt",
            "version": self.version,
            "coef": float(self.coef),
            "intercept": float(self.intercept),
        }

    @classmethod
    def from_dict(cls, payload: Mapping) -> "PlattCalibrator":
        if payload.get("type") != "platt":
            raise ValueError(f"unexpected calibrator type: {payload.get('type')!r}")
        return cls(
            coef=float(payload["coef"]),
            intercept=float(payload["intercept"]),
            version=str(payload.get("version", "v3.0.0")),
        )


def fit_platt(
    probs: Sequence[float],
    labels: Sequence[int],
    *,
    version: str = "v3.0.0",
    max_iter: int = 200,
    learning_rate: float = 0.05,
    l2: float = 1e-3,
) -> PlattCalibrator:
    """Fit Platt scaling via batch gradient descent on the binary log loss.

    A tiny problem (single feature) so we avoid taking a dependency on
    scikit-learn at training time — this keeps the calibrator deterministic
    and easy to round-trip through JSON.
    """
    if len(probs) != len(labels):
        raise ValueError("probs and labels must align")
    if not probs:
        return PlattCalibrator(coef=1.0, intercept=0.0, version=version)
    xs = [_logit(p) for p in probs]
    ys = [float(y) for y in labels]
    w = 1.0
    b = 0.0
    n = len(xs)
    for _ in range(max_iter):
        grad_w = 0.0
        grad_b = 0.0
        for x, y in zip(xs, ys):
            p_hat = _sigmoid(w * x + b)
            error = p_hat - y
            grad_w += error * x
            grad_b += error
        grad_w = grad_w / n + l2 * w
        grad_b = grad_b / n
        w -= learning_rate * grad_w
        b -= learning_rate * grad_b
    return PlattCalibrator(coef=w, intercept=b, version=version)


# ---------------------------------------------------------------------------
# Isotonic regression via pool-adjacent-violators (PAVA).
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class IsotonicCalibrator:
    x_breakpoints: tuple[float, ...]
    y_breakpoints: tuple[float, ...]
    version: str = "v3.0.0"

    def apply(self, probs: Sequence[float]) -> list[float]:
        return [self.apply_one(p) for p in probs]

    def apply_one(self, prob: float) -> float:
        p = _clip(prob)
        xs = self.x_breakpoints
        ys = self.y_breakpoints
        if not xs:
            return p
        if p <= xs[0]:
            return ys[0]
        if p >= xs[-1]:
            return ys[-1]
        # Binary search
        lo, hi = 0, len(xs) - 1
        while lo + 1 < hi:
            mid = (lo + hi) // 2
            if xs[mid] <= p:
                lo = mid
            else:
                hi = mid
        x0, x1 = xs[lo], xs[hi]
        y0, y1 = ys[lo], ys[hi]
        if x1 == x0:
            return y0
        return y0 + (y1 - y0) * (p - x0) / (x1 - x0)

    def to_dict(self) -> dict:
        return {
            "type": "isotonic",
            "version": self.version,
            "x": list(self.x_breakpoints),
            "y": list(self.y_breakpoints),
        }

    @classmethod
    def from_dict(cls, payload: Mapping) -> "IsotonicCalibrator":
        if payload.get("type") != "isotonic":
            raise ValueError(f"unexpected calibrator type: {payload.get('type')!r}")
        return cls(
            x_breakpoints=tuple(float(v) for v in payload["x"]),
            y_breakpoints=tuple(float(v) for v in payload["y"]),
            version=str(payload.get("version", "v3.0.0")),
        )


def fit_isotonic(
    probs: Sequence[float],
    labels: Sequence[int],
    *,
    version: str = "v3.0.0",
) -> IsotonicCalibrator:
    """Fit isotonic regression with pool-adjacent-violators.

    Returns monotone non-decreasing breakpoints. Input ``probs`` may be
    duplicated; equal x-values are coalesced into one breakpoint.
    """
    if len(probs) != len(labels):
        raise ValueError("probs and labels must align")
    if not probs:
        return IsotonicCalibrator(x_breakpoints=(), y_breakpoints=(), version=version)
    paired = sorted(((float(p), float(y), 1.0) for p, y in zip(probs, labels)), key=lambda x: x[0])
    xs: list[float] = []
    ys: list[float] = []
    ws: list[float] = []
    for x, y, w in paired:
        xs.append(x)
        ys.append(y)
        ws.append(w)
        # Coalesce equal x then run PAVA back-merge
        while len(xs) >= 2 and (xs[-2] == xs[-1] or ys[-2] > ys[-1]):
            wn = ws[-2] + ws[-1]
            yn = (ys[-2] * ws[-2] + ys[-1] * ws[-1]) / wn
            xn = (xs[-2] * ws[-2] + xs[-1] * ws[-1]) / wn
            xs.pop()
            xs.pop()
            ys.pop()
            ys.pop()
            ws.pop()
            ws.pop()
            xs.append(xn)
            ys.append(yn)
            ws.append(wn)
    # Sentinels at [0, 1] so out-of-range inputs are clipped sensibly.
    if xs[0] > 0.0:
        xs.insert(0, 0.0)
        ys.insert(0, max(0.0, min(ys[0], 1.0)))
    if xs[-1] < 1.0:
        xs.append(1.0)
        ys.append(max(0.0, min(ys[-1], 1.0)))
    return IsotonicCalibrator(
        x_breakpoints=tuple(xs),
        y_breakpoints=tuple(min(1.0, max(0.0, y)) for y in ys),
        version=version,
    )


# ---------------------------------------------------------------------------
# Mondrian split-conformal prediction (§8).
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class MondrianGroupKey:
    best_of: int
    is_in_play: bool
    is_major_event: bool

    def encode(self) -> str:
        return f"{self.best_of}|{str(self.is_in_play).lower()}|{str(self.is_major_event).lower()}"

    @classmethod
    def decode(cls, key: str) -> "MondrianGroupKey":
        parts = key.split("|")
        if len(parts) != 3:
            raise ValueError(f"invalid mondrian group key: {key!r}")
        return cls(
            best_of=int(parts[0]),
            is_in_play=parts[1].lower() == "true",
            is_major_event=parts[2].lower() == "true",
        )


CONFIDENT_TOP = "CONFIDENT_TOP"
CONFIDENT_BOT = "CONFIDENT_BOT"
AMBIGUOUS = "AMBIGUOUS"
ANOMALOUS = "ANOMALOUS"


@dataclass(frozen=True)
class Uncertainty:
    coverage: float
    alpha: float
    label: str
    intervalLow: float
    intervalHigh: float
    groupKey: str
    quantile: float
    method: str


@dataclass(frozen=True)
class MondrianSplitConformal:
    METHOD = "mondrian-split-conformal"

    alpha: float
    fallback_quantile: float
    quantiles: Mapping[str, float] = field(default_factory=dict)
    counts: Mapping[str, int] = field(default_factory=dict)
    version: str = "v3.0.0"

    def quantile_for(self, group_key: str | "MondrianGroupKey" | None) -> float:
        if group_key is None:
            return self.fallback_quantile
        key = group_key.encode() if hasattr(group_key, "encode") and not isinstance(group_key, str) else str(group_key)
        return self.quantiles.get(key, self.fallback_quantile)

    def uncertainty(self, calibrated_p_top: float, group_key: "MondrianGroupKey | None") -> Uncertainty:
        q = self.quantile_for(group_key)
        label = _classify(calibrated_p_top, q)
        interval_low = max(0.0, min(1.0, 1.0 - q))
        interval_high = max(0.0, min(1.0, q))
        return Uncertainty(
            coverage=1.0 - self.alpha,
            alpha=self.alpha,
            label=label,
            intervalLow=interval_low,
            intervalHigh=interval_high,
            groupKey="" if group_key is None else group_key.encode(),
            quantile=q,
            method=self.METHOD,
        )

    def to_dict(self) -> dict:
        return {
            "type": "mondrian-split-conformal",
            "version": self.version,
            "alpha": self.alpha,
            "fallback_quantile": self.fallback_quantile,
            "groups": {
                key: {"quantile": float(self.quantiles[key]), "n": int(self.counts.get(key, 0))}
                for key in sorted(self.quantiles.keys())
            },
        }

    @classmethod
    def from_dict(cls, payload: Mapping) -> "MondrianSplitConformal":
        if payload.get("type") != "mondrian-split-conformal":
            raise ValueError(f"unexpected calibrator type: {payload.get('type')!r}")
        groups = payload.get("groups", {}) or {}
        quantiles: dict[str, float] = {}
        counts: dict[str, int] = {}
        for key, body in groups.items():
            quantiles[str(key)] = float(body["quantile"])
            counts[str(key)] = int(body.get("n", 0))
        return cls(
            alpha=float(payload.get("alpha", DEFAULT_ALPHA)),
            fallback_quantile=float(payload.get("fallback_quantile", 0.9)),
            quantiles=quantiles,
            counts=counts,
            version=str(payload.get("version", "v3.0.0")),
        )


def fit_mondrian_split_conformal(
    *,
    probs: Sequence[float],
    labels: Sequence[int],
    group_keys: Sequence[str],
    alpha: float = DEFAULT_ALPHA,
    version: str = "v3.0.0",
    min_group_size: int = 30,
) -> MondrianSplitConformal:
    """Fit split-conformal quantiles per Mondrian group.

    Non-conformity ``s(x, y) = 1 - p_hat(y | x)``. For groups smaller than
    ``min_group_size`` we fall back to the pooled quantile (the spec hints
    at this in §8.3 — small subgroups inherit the global coverage).
    """
    if not (0.0 < alpha < 1.0):
        raise ValueError("alpha must lie in (0, 1)")
    if not (len(probs) == len(labels) == len(group_keys)):
        raise ValueError("probs, labels, and group_keys must align")

    grouped: dict[str, list[float]] = defaultdict(list)
    pooled: list[float] = []
    for p, y, key in zip(probs, labels, group_keys):
        score = 1.0 - (float(p) if int(y) == 1 else 1.0 - float(p))
        grouped[str(key)].append(score)
        pooled.append(score)

    pooled_quantile = _split_quantile(pooled, alpha) if pooled else 0.9
    quantiles: dict[str, float] = {}
    counts: dict[str, int] = {}
    for key, scores in grouped.items():
        counts[key] = len(scores)
        if len(scores) < min_group_size:
            quantiles[key] = pooled_quantile
            continue
        quantiles[key] = _split_quantile(scores, alpha)
    return MondrianSplitConformal(
        alpha=alpha,
        fallback_quantile=pooled_quantile,
        quantiles=quantiles,
        counts=counts,
        version=version,
    )


def _classify(p_top: float, quantile: float) -> str:
    top_in = p_top >= 1.0 - quantile
    bot_in = p_top <= quantile
    if top_in and bot_in:
        return AMBIGUOUS
    if top_in:
        return CONFIDENT_TOP
    if bot_in:
        return CONFIDENT_BOT
    return ANOMALOUS


def _split_quantile(scores: Iterable[float], alpha: float) -> float:
    arr = sorted(float(s) for s in scores)
    n = len(arr)
    if n == 0:
        return 1.0
    # Per §8.2: q̂ = ceil((n+1)(1-α))/n quantile of calibration scores.
    rank = int(((n + 1) * (1.0 - alpha) + 0.999999) // 1)
    rank = max(1, min(n, rank))
    return arr[rank - 1]


# ---------------------------------------------------------------------------
# Calibration bundle: ties the three artefacts together for the trainer.
# ---------------------------------------------------------------------------


@dataclass
class CalibrationBundle:
    platt: PlattCalibrator
    isotonic: IsotonicCalibrator
    conformal: MondrianSplitConformal

    def apply(self, probs: Sequence[float]) -> list[float]:
        return self.isotonic.apply(self.platt.apply(probs))

    def calibrate(self, prob: float) -> float:
        return self.apply([prob])[0]

    def quantile_for(self, group_key: str) -> float:
        return self.conformal.quantile_for(group_key)
