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
  settlementReason?: string | null
  settlementSource?: string | null
  settlementConfidence?: number | null
  settlementEvidenceId?: number | null
  settlementEvidenceFingerprint?: string | null
  settlementEvidenceSourceCount?: number | null
  settlementCoverageState?: string | null
  settlementAmbiguityScore?: number | null
  settlementObservedAt?: string | null
  scoreEvidenceQuality?: string | null
  scoreEvidenceFinality?: string | null
  scoreEvidenceConfidence?: number | null
  scoreEvidenceObservationCount?: number | null
  scoreEvidenceSourceCount?: number | null
  scoreEvidenceAgreeingSources?: number | null
  scoreEvidenceCompletionSignals?: number | null
  scoreEvidenceInferredWinnerId?: number | null
  scoreEvidenceLatestScore?: string | null
  scoreEvidenceLatestPhase?: string | null
  scoreEvidenceContradictory?: boolean
  closingDecimalOdds?: number | null
  closingObservedAt?: string | null
  closingSource?: string | null
  closingMarketState?: string | null
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
  status?: 'COMPLETED' | 'ALREADY_RUNNING' | string
  message?: string | null
}

export type ModelCallResult = {
  callId: number
  matchId: number | null
  eventKey: string
  eventName: string
  competitionName: string | null
  captureType: 'PREMATCH_CLOSE' | 'LIVE_FIRST_SEEN' | string
  capturedAt: string | null
  matchDateIso: string | null
  startTimeIso: string | null
  player1Name: string
  player2Name: string
  predictedWinnerPlayerId: number | null
  predictedWinnerName: string | null
  modelProbability: number | null
  modelFairAmericanOdds: number | null
  hardRockAmericanOdds: number | null
  opponentHardRockAmericanOdds: number | null
  hardRockNoVigProbability: number | null
  actualWinnerPlayerId: number | null
  actualWinnerName: string
  score: string
  outcome: 'CORRECT' | 'INCORRECT' | 'NO_LEAN' | string
  paperPickPlaced: boolean
  recommendedAtCapture: boolean
}

export type ModelCallScorecard = {
  sessionId: number | null
  sessionLabel: string
  generatedAt: string
  totalCalls: number
  awaitingResult: number
  settledCalls: number
  correct: number
  incorrect: number
  noLean: number
  accuracyPct: number
  pregameSettled: number
  pregameCorrect: number
  pregameAccuracyPct: number
  liveFirstSettled: number
  liveFirstCorrect: number
  liveFirstAccuracyPct: number
  averageConfidencePct: number
  brierScore: number | null
  flatStakeSettled: number
  flatStakeWins: number
  flatStakeLosses: number
  flatStakeWagered: number
  flatStakeReturned: number
  flatStakeNetProfit: number
  flatStakeRoiPct: number
  viewerGradedCalls: number
  viewerCorrect: number
  viewerIncorrect: number
  viewerAccuracyPct: number
  viewerApprovedPending: number
  viewerConflicts: number
  recentResults: ModelCallResult[]
}

export type ModelCallPipelineStage =
  | 'SCHEDULED'
  | 'WAITING_FOR_FEED'
  | 'LIVE_MONITORING'
  | 'SETTLEMENT_REVIEW'
  | 'VIEWER_APPROVED'
  | 'SYSTEM_CONFIRMED'
  | 'RESULT_CONFLICT'
  | string

export type ModelCallTracking = {
  callId: number
  sessionId: number
  eventKey: string
  eventName: string
  competitionName: string | null
  source: string | null
  strategy: string | null
  modelVersion: string | null
  captureType: string
  capturedAt: string | null
  startTimeIso: string | null
  player1Id: number | null
  player1Name: string
  player2Id: number | null
  player2Name: string
  predictedWinnerPlayerId: number | null
  predictedWinnerName: string | null
  modelProbability: number | null
  modelFairAmericanOdds: number | null
  hardRockAmericanOdds: number | null
  opponentHardRockAmericanOdds: number | null
  hardRockNoVigProbability: number | null
  hardRockMarginPct: number | null
  recommendedAtCapture: boolean
  paperPickPlaced: boolean
  decisionStatus: string | null
  decisionReason: string | null
  latestScore: string | null
  latestPhase: string | null
  latestSource: string | null
  latestObservedAt: string | null
  latestLive: boolean
  completionSignalSeen: boolean
  provisionalOutcomeMethod: string | null
  provisionalOutcomeConfidence: number | null
  pipelineStage: ModelCallPipelineStage
  pipelineLabel: string
  pipelineDetail: string
  systemWinnerPlayerId: number | null
  systemWinnerName: string | null
  systemScore: string | null
  systemResultSource: string | null
  systemResolvedAt: string | null
  viewerWinnerPlayerId: number | null
  viewerWinnerName: string | null
  viewerScore: string | null
  viewerNote: string | null
  viewerReviewedAt: string | null
  effectiveOutcome: 'CORRECT' | 'INCORRECT' | 'NO_LEAN' | 'AWAITING' | string
  effectiveOutcomeSource: 'SYSTEM' | 'VIEWER' | null
  canApprove: boolean
}

export type ModelCallMonitor = {
  sessionId: number | null
  sessionLabel: string
  generatedAt: string
  totalCalls: number
  scheduled: number
  liveTracking: number
  settlementReview: number
  viewerApproved: number
  systemConfirmed: number
  conflicts: number
  calls: ModelCallTracking[]
}

export type HardRockScoreStreamStatus = {
  enabled: boolean
  connected: boolean
  trackedEvents: number
  liveEvents: number
  completedEventsCached: number
  connectedAt: string | null
  lastMessageAt: string | null
  lastScoreAt: string | null
  reconnectCount: number
  lastError: string | null
}

export type ModelCallApproval = {
  winnerPlayerId: number
  score?: string
  reviewer?: string
  note?: string
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

export type MatchupForm = {
  recentMatches: number
  recentWins: number
  recentWinPct: number
  averageSetMargin: number
  streak: number
  streakWin: boolean
}

export type MatchupRatings = {
  elo: number
  glicko: number
  glickoDeviation: number
  trueSkill2: number
  wengLin: number
  stability: number
}

export type MatchupAnalysis = {
  player1: {
    id: number
    firstName: string
    lastName: string
    fullName: string
  }
  player2: {
    id: number
    firstName: string
    lastName: string
    fullName: string
  }
  headToHead: {
    player1Name: string
    player2Name: string
    player1Wins: number
    player2Wins: number
    totalMatches: number
    player1WinPct: number
    player2WinPct: number
  }
  player1Form: MatchupForm
  player2Form: MatchupForm
  player1Last50: MatchupForm
  player2Last50: MatchupForm
  recentHeadToHead: {
    matches: number
    player1Wins: number
    player2Wins: number
  }
  player1Ratings: MatchupRatings
  player2Ratings: MatchupRatings
  player1Probability: {
    probability: number
    confidenceLow: number
    confidenceHigh: number
    americanOdds: number
  }
  player2Probability: {
    probability: number
    confidenceLow: number
    confidenceHigh: number
    americanOdds: number
  }
  featureContributions: Array<{
    feature: string
    contribution: number
  }>
  modelComparison: {
    baselineProbabilityPlayer1: number
    eloProbabilityPlayer1: number
    glickoProbabilityPlayer1: number
  }
  explanation: string
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
