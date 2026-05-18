export interface PlayerDto {
  id: number
  firstName: string
  lastName: string
  fullName: string
}

export interface PlayerStatisticsDto {
  playerId: number
  playerName: string
  wins: number
  losses: number
  matches: number
  winPct: number
}

export interface MatchDto {
  id: number
  externalId: string
  date: string
  player1: PlayerDto
  player2: PlayerDto
  result: string
  player1SetsWon: number | null
  player2SetsWon: number | null
  winnerPlayerId: number | null
  complete: boolean
}

export interface HeadToHeadStatsDto {
  player1Name: string
  player2Name: string
  player1Wins: number
  player2Wins: number
  totalMatches: number
  player1WinPct: number
  player2WinPct: number
}

export interface MatchupAnalysisDto {
  player1: PlayerDto
  player2: PlayerDto
  headToHead: HeadToHeadStatsDto
  player1Form: {
    recentMatches: number
    recentWins: number
    recentWinPct: number
    averageSetMargin: number
    streak: number
    streakWin: boolean
  }
  player2Form: {
    recentMatches: number
    recentWins: number
    recentWinPct: number
    averageSetMargin: number
    streak: number
    streakWin: boolean
  }
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
  featureContributions: Array<{ feature: string; contribution: number }>
  modelComparison?: {
    baselineProbabilityPlayer1: number
    eloProbabilityPlayer1: number
    glickoProbabilityPlayer1: number
  }
  explanation: string
}

export interface MatchupFeatureVectorDto {
  player1Id: number
  player2Id: number
  asOfDate: string
  headToHeadWinRatePlayer1: number
  headToHeadWinRatePlayer2: number
  headToHeadSampleWeight: number
  headToHeadReliability: number
  player1: {
    recentForm: number
    opponentAdjustedForm: number
    scheduleStrength: number
    eloRating: number
    glickoRating: number
    glickoRatingDeviation: number
    glickoVolatility: number
    recentFormSampleWeight: number
    opponentAdjustedSampleWeight: number
    scheduleStrengthSampleWeight: number
    recentFormReliability: number
    opponentAdjustedReliability: number
    scheduleStrengthReliability: number
    ratingStability: number
  }
  player2: {
    recentForm: number
    opponentAdjustedForm: number
    scheduleStrength: number
    eloRating: number
    glickoRating: number
    glickoRatingDeviation: number
    glickoVolatility: number
    recentFormSampleWeight: number
    opponentAdjustedSampleWeight: number
    scheduleStrengthSampleWeight: number
    recentFormReliability: number
    opponentAdjustedReliability: number
    scheduleStrengthReliability: number
    ratingStability: number
  }
  eloProbabilityPlayer1: number
  glickoProbabilityPlayer1: number
  reliabilitySummary: {
    overallReliability: number
    ratingAgreement: number
    player1BaselineStability: number
    player2BaselineStability: number
  }
  significanceSummary: {
    sampleDepth: number
    headToHeadSupport: number
    recentFormSupport: number
    opponentAdjustedSupport: number
    scheduleStrengthSupport: number
    baselineSupport: number
    strongSignalCount: number
    usableSignalCount: number
    thinSignalCount: number
    strongestSupportLabel: string
    strongestSupportValue: number
    weakestSupportLabel: string
    weakestSupportValue: number
  }
  player1Rating95PctInterval: { low: number; high: number }
  player2Rating95PctInterval: { low: number; high: number }
}

export interface ScrapeStatusDto {
  running: boolean
  mode: string
  startedAt: string | null
  finishedAt: string | null
  savedMatches: number
  error: string | null
}

export interface ScrapeRunRecordDto {
  runId: number
  mode: string
  startedAt: string
  finishedAt: string
  status: string
  savedMatches: number
  error: string | null
}

export interface ScrapeErrorRecordDto {
  runId: number
  occurredAt: string
  mode: string
  message: string
  url?: string | null
  context?: string | null
  htmlSnippet?: string | null
}

export interface ScrapeMetricsDto {
  totalRuns: number
  successRuns: number
  failedRuns: number
  successRate: number
  averageDurationSeconds: number
  medianDurationSeconds: number
  p95DurationSeconds: number
  averageMatchesAdded: number
  lastRunAt: string | null
}

export interface DryRunPreviewDto {
  listUrl: string
  page: number
  selector: string
  postLinksFound: number
  sampleLinks: string[]
}

export interface StatisticsBenchmarkDto {
  iterations: number
  players: number
  matches: number
  optimizedMillis: number
  legacyScanMillis: number
  speedupX: number
}

export interface PlayerAliasDto {
  id: number
  playerId: number
  playerName: string
  aliasName: string
  normalizedAlias: string
  createdAt: string
}

export interface RatingSnapshotDto {
  id: number
  playerId: number
  playerName: string
  snapshotDate: string
  rating: number
  ratingDeviation: number | null
  volatility: number | null
  confidenceLow: number | null
  confidenceHigh: number | null
  ratingSystem: string
}

export interface Glicko2RebuildDto {
  fromDate: string | null
  toDate: string | null
  periodsProcessed: number
  playersProcessed: number
  snapshotsWritten: number
  tau: number
}

export interface Glicko2TauTuningDto {
  fromDate: string | null
  toDate: string | null
  bestTau: number
  candidates: Array<{
    tau: number
    averageLogLoss: number
    averageBrierScore: number
    predictions: number
  }>
}

export interface DuplicatePlayerCandidateDto {
  sourcePlayerId: number
  sourcePlayerName: string
  targetPlayerId: number
  targetPlayerName: string
  similarityScore: number
}

export interface ModelRegistryEntryDto {
  id: number
  modelVersion: string
  modelFamily: string
  trainingFrom: string | null
  trainingTo: string | null
  validationFrom: string | null
  validationTo: string | null
  accuracy: number | null
  logLoss: number | null
  brierScore: number | null
  calibrationMethod: string | null
  regularizationLambda: number | null
  folds: number | null
  active: boolean
  notes: string | null
  createdAt: string
}

export interface ModelTrainingReportDto {
  jobId: string
  trainingFrom: string
  trainingTo: string
  samples: number
  features: number
  championFamily: string
  championVersion: string
  trainedAt: string
  candidates: Array<{
    family: string
    version: string
    accuracy: number
    logLoss: number
    brierScore: number
    calibrationMethod: string
    active: boolean
  }>
  calibrationCurve: Array<{
    lowerBound: number
    upperBound: number
    count: number
    meanPredicted: number
    observedRate: number
  }>
  validationRegimes: Array<{
    label: string
    count: number
    meanPredicted: number
    observedRate: number
    accuracy: number
    brierScore: number
    roiPct: number | null
  }>
  operationalRegimes: Array<{
    label: string
    count: number
    meanPredicted: number
    observedRate: number
    accuracy: number
    brierScore: number
    roiPct: number | null
  }>
}

export interface AdaptiveRegimeProfileDto {
  label: string
  sampleSize: number
  reliability: number
  calibrationErrorPct: number
  roiPct: number
  confidenceScale: number
  ciBoost: number
  live: boolean
  phase: string
  sideType: string
}

export interface ValueOpportunityDto {
  id: number
  source: string
  strategy: string
  modelVersion: string
  player1Id: number
  player2Id: number
  playerSideId: number
  playerSideName: string
  modelProbability: number
  confidenceLow: number
  confidenceHigh: number
  impliedProbability: number
  edge: number
  threshold: number
  americanOdds: number
  createdAt: string
}

export interface OddsRefreshResultDto {
  source: string
  quotesFetched: number
  quotesResolved: number
  opportunitiesCreated: number
  strategy: string
  modelVersion: string
  refreshedAt: string
}

export interface LiveOddsRecommendationDto {
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

export interface PaperTradeBetDto {
  id: number
  status: string
  source: string
  strategy: string
  modelVersion: string
  eventName: string
  competitionName: string
  liveAtPlacement: boolean
  startTimeIso: string | null
  externalEventId: string | null
  identityLocked: boolean
  identityLockedAt: string | null
  lockedStartTimeIso: string | null
  lockedExternalEventId: string | null
  lockedSourceFeedEventId: string | null
  identityDriftCount: number
  lastIdentityDriftAt: string | null
  player1Name: string
  player2Name: string
  sideName: string
  americanOdds: number
  decimalOdds: number
  stake: number
  potentialPayout: number
  profitLoss: number | null
  modelProbability: number
  impliedProbability: number
  edge: number
  confidenceLow: number | null
  confidenceHigh: number | null
  topTrigger: string | null
  topTriggerContribution: number | null
  grade: string | null
  rationale: string | null
  lastObservedScore: string | null
  lastObservedPhase: string | null
  lastScoreSource: string | null
  lastScoreConfidence: number | null
  lastObservationDisplayed: boolean
  lastObservationResulted: boolean
  lastMatchCompleted: boolean
  lastSourceFeedCode: string | null
  lastSourceFeedEventId: string | null
  lastScoreDetail: string | null
  trackedAfterClose: boolean
  trackingState: string | null
  settlementReason: string | null
  settlementSource: string | null
  lastObservedAt: string | null
  placedAt: string
  settledAt: string | null
  eventKey: string
  dedupeKey: string
  resultMatchId: number | null
  winnerPlayerId: number | null
}

export interface PaperTradingSessionDto {
  sessionId: number
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
  simulationRowsScanned: number
  simulationBetsPlaced: number
  simulationBetsSettled: number
  simulationBetsVoided: number
  settledWinRate: number
  createdAt: string
  updatedAt: string
  lastSyncAt: string | null
  adaptiveMetrics: {
    sampleSize: number
    edgeShiftPct: number
    selectionScoreShift: number
    stakeMultiplier: number
    calibrationErrorPct: number
    roiSignalPct: number
    updatedAt: string | null
  }
  decisionTelemetry: {
    consideredCount: number
    placedCount: number
    skippedCount: number
    fallbackPlacedCount: number
    placementRatePct: number
    avgSelectionScore: number
    avgSignalQualityPct: number
    avgPlacedEdgePct: number
    avgSkippedEdgePct: number
    topSkipReasons: Array<{
      reason: string
      count: number
    }>
  }
  exposureMetrics: {
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
  openBetsList: PaperTradeBetDto[]
  recentBets: PaperTradeBetDto[]
  topTriggers: Array<{
    trigger: string
    count: number
    wins: number
    losses: number
    winRate: number
    pnl: number
    avgEdgePct: number
    avgModelProbability: number
    avgImpliedProbability: number
    avgConfidenceWidthPct: number
    calibrationDeltaPct: number
    roiPct: number
  }>
  equityCurve: Array<{
    at: string
    bankroll: number
    cumulativePnl: number
  }>
}

export interface PaperTradingSyncResultDto {
  strategy: string
  modelVersion: string
  rowsScanned: number
  betsPlaced: number
  betsSkipped: number
  betsSettled: number
  betsVoided: number
  syncedAt: string
  session: PaperTradingSessionDto
}

export interface TrackedMatchObservationDto {
  id: number
  sessionId: number
  betId: number | null
  eventKey: string
  dedupeKey: string | null
  externalEventId: string | null
  source: string
  sourceKind: string
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
  observedAt: string
}

export interface LiveStudioIntegrityDto {
  trackedObservations: number
  boardObservations: number
  scoreFeedObservations: number
  trackedAfterCloseObservations: number
  scoreBackedSettlements: number
  targetedCompletionSettlements: number
  officialResultSettlements: number
  databaseSettlements: number
  heuristicSettlements: number
  voidedSettlements: number
}

export interface CompletedMatchLogDto {
  matchId: number
  eventName: string
  matchDateIso: string | null
  startTimeIso: string | null
  player1Name: string
  player2Name: string
  winnerName: string
  loserName: string
  score: string
  picked: boolean
  pickStatus: string | null
}
