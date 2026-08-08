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
