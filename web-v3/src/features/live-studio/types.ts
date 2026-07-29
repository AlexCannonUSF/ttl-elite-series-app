export type PaperTradeBet = {
  id: number
  status: string
  eventName: string
  sideName: string
  stake: number
  profitLoss: number | null
  decimalOdds: number
  modelProbability: number
  impliedProbability: number
  edge: number
  topTrigger: string | null
  placedAt: string | null
  settledAt: string | null
  // Optional fields the server already returns; widened for the Home lists.
  competitionName?: string | null
  startTimeIso?: string | null
  potentialPayout?: number | null
  americanOdds?: number | null
  player1Name?: string | null
  player2Name?: string | null
  externalEventId?: string | null
  lockedExternalEventId?: string | null
  matchupKey?: string | null
}

export type ExposureMetrics = {
  openExposure: number
  openExposureCap: number
  openExposureUsagePct: number
  openExposureRemaining: number
  maxConcurrentOpenBets: number
  concurrentOpenBetUsagePct: number
  mostExposedPlayerName: string | null
  mostExposedPlayerStake: number
  mostExposedPlayerCap: number
  mostExposedPlayerCapUsagePct: number
  playerNearCapCount: number
  mostExposedTrigger: string | null
  mostExposedTriggerStake: number
  mostExposedTriggerCap: number
  mostExposedTriggerCapUsagePct: number
  triggerNearCapCount: number
}

export type ClvMetrics = {
  betsInWindow: number
  betsWithClosingSnapshot: number
  coverageRatio: number
  avgClvPct: number
  avgPlacedImpliedPct: number
  avgClosingImpliedPct: number
  lastClosingSnapshotAt: string | null
}

export type EquityPoint = {
  at: string
  bankroll: number
  cumulativePnl: number
}

export type PaperTradingSession = {
  sessionId: number | null
  label: string
  status: string
  startingBankroll: number
  currentBankroll: number
  peakBankroll: number
  realizedPnl: number
  roiPct: number
  totalStaked: number
  totalReturned: number
  totalBets: number
  openBets: number
  wins: number
  losses: number
  pushes: number
  voidedBets: number
  settledWinRate: number
  createdAt: string | null
  updatedAt: string | null
  lastSyncAt: string | null
  exposureMetrics: ExposureMetrics
  clvMetrics: ClvMetrics
  openBetsList: PaperTradeBet[]
  recentBets: PaperTradeBet[]
  equityCurve: EquityPoint[]
}

export type PaperTradingSyncResult = {
  strategy: string
  modelVersion: string | null
  rowsScanned: number
  betsPlaced: number
  betsSkipped: number
  betsSettled: number
  betsVoided: number
  syncedAt: string
  session: PaperTradingSession
}

export type LiveOddsRecommendation = {
  source: string
  strategy: string
  modelVersion: string
  eventName: string
  competitionName: string
  live: boolean
  startTimeIso: string | null
  liveScore: string | null
  matchPhase: string | null
  player1Id: number | null
  player1Name: string
  player2Id: number | null
  player2Name: string
  decimalOddsPlayer1: number
  decimalOddsPlayer2: number
  americanOddsPlayer1: number
  americanOddsPlayer2: number
  impliedProbabilityPlayer1: number
  impliedProbabilityPlayer2: number
  modelProbabilityPlayer1: number | null
  modelProbabilityPlayer2: number | null
  edgePlayer1: number | null
  edgePlayer2: number | null
  modelFairAmericanOddsPlayer1: number | null
  modelFairAmericanOddsPlayer2: number | null
  suggestedSide: string | null
  suggestedEdge: number | null
  suggestedFairAmericanOdds: number | null
  confidenceLow: number | null
  confidenceHigh: number | null
  recommended: boolean
  grade: string
  rationale: string
  topTrigger: string | null
  topTriggerContribution: number | null
  overallReliability: number | null
  ratingAgreement: number | null
  topTriggerReliability: number | null
  suggestedSideBaselineStability: number | null
  matchupKey: string | null
  suggestedDedupeKey: string | null
  sourceType: string | null
  sourceConfidence: number | null
  externalEventId: string | null
  displayed: boolean
  resulted: boolean
  matchCompleted: boolean
  sourceFeedCode: string | null
  sourceFeedEventId: string | null
  scoreDetail: string | null
}

export type LiveBoardHistoryPoint = {
  time: number
  player1Odds: number
  player2Odds: number
}

export type TrackedMatchObservation = {
  id: number | null
  sessionId: number | null
  betId: number | null
  eventKey: string
  dedupeKey: string | null
  externalEventId: string | null
  source: string | null
  sourceKind: string | null
  sourceConfidence: number
  displayed: boolean
  resulted: boolean
  matchCompleted: boolean
  sourceFeedCode: string | null
  sourceFeedEventId: string | null
  live: boolean
  trackedAfterClose: boolean
  eventName: string | null
  competitionName: string | null
  startTimeIso: string | null
  player1Id: number | null
  player1Name: string | null
  player2Id: number | null
  player2Name: string | null
  liveScore: string | null
  matchPhase: string | null
  scoreDetail: string | null
  observedAt: string | null
}
