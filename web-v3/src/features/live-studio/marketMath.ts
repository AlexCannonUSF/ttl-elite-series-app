export function calculateBookMargin(decimalOddsPlayer1: number, decimalOddsPlayer2: number) {
  if (
    !Number.isFinite(decimalOddsPlayer1)
    || !Number.isFinite(decimalOddsPlayer2)
    || decimalOddsPlayer1 <= 1
    || decimalOddsPlayer2 <= 1
  ) {
    return null
  }

  return (1 / decimalOddsPlayer1) + (1 / decimalOddsPlayer2) - 1
}

/** Remove a two-way book's proportional overround from one offered probability. */
export function calculateNoVigMarketProbability(
  offeredImpliedProbability: number | null,
  bookMargin: number | null,
) {
  if (
    offeredImpliedProbability == null
    || !Number.isFinite(offeredImpliedProbability)
    || offeredImpliedProbability <= 0
    || bookMargin == null
    || !Number.isFinite(bookMargin)
    || bookMargin <= -1
  ) {
    return null
  }

  return clampProbability(offeredImpliedProbability / (1 + bookMargin))
}

/**
 * Turn a no-vig model probability into the retail quote it would produce if
 * it carried the same proportional two-way margin as the current book.
 */
export function calculateModelPriceAtBookMargin(
  modelProbability: number | null,
  bookMargin: number | null,
) {
  if (
    modelProbability == null
    || !Number.isFinite(modelProbability)
    || modelProbability <= 0
    || modelProbability >= 1
    || bookMargin == null
    || !Number.isFinite(bookMargin)
  ) {
    return null
  }

  return probabilityToAmericanOdds(clampProbability(modelProbability * (1 + Math.max(0, bookMargin))))
}

export function probabilityToAmericanOdds(probability: number | null) {
  if (probability == null || !Number.isFinite(probability) || probability <= 0 || probability >= 1) {
    return null
  }
  return probability <= 0.5
    ? Math.round((100 * (1 - probability)) / probability)
    : Math.round((-100 * probability) / (1 - probability))
}

function clampProbability(value: number) {
  return Math.min(0.999999, Math.max(0.000001, value))
}
