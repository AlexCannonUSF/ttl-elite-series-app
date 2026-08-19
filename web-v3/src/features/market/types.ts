export type MarketIntelligence = {
  generatedAt: string
  eventIdentity: string
  primarySource: string
  executionAvailable: boolean
  sourceCount: number
  consensusSourceCount: number
  consensusPlayer1Probability: number | null
  consensusPlayer2Probability: number | null
  consensusDispersionPctPoints: number | null
  freshestQuoteAgeSeconds: number
  books: MarketBookLine[]
  history: MarketHistoryPoint[]
  warnings: string[]
}

export type MarketBookLine = {
  sourceCode: string
  displayName: string
  role: string
  executable: boolean
  authorized: boolean
  marketState: string
  observedAt: string | null
  ageSeconds: number
  stale: boolean
  player1DecimalOdds: number | null
  player2DecimalOdds: number | null
  player1AmericanOdds: number | null
  player2AmericanOdds: number | null
  player1NoVigProbability: number | null
  player2NoVigProbability: number | null
  overroundPct: number | null
}

export type MarketHistoryPoint = {
  sourceCode: string
  observedAt: string
  player1DecimalOdds: number | null
  player2DecimalOdds: number | null
  marketState: string
}
