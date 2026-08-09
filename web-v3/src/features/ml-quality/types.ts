export type ReliabilityBin = {
  lowerBound: number
  upperBound: number
  count: number
  meanPredicted: number
  observedRate: number
}

export type ReliabilitySnapshot = {
  label: string
  sampleCount: number
  ece: number | null
  maxBinDeviation: number | null
  brierScore: number | null
  bins: ReliabilityBin[]
}

export type HistogramBin = {
  lowerBound: number
  upperBound: number
  count: number
}

export type DailyCount = {
  date: string
  predictions: number
}

export type DriftSummary = {
  eceDelta: number | null
  meanPredictedDelta: number | null
  meanObservedDelta: number | null
  severity: 'GREEN' | 'AMBER' | 'RED' | 'UNKNOWN'
}

export type MlQualityResponse = {
  windowDays: number
  computedAtUtc: string
  training: ReliabilitySnapshot
  recent: ReliabilitySnapshot
  probabilityHistogram: HistogramBin[]
  dailyVolume: DailyCount[]
  drift: DriftSummary
}

export type LearningSegment = {
  segment: string
  rawSampleSize: number
  effectiveSampleSize: number
  winRate: number
  meanPredicted: number
  calibrationError: number
  roiPct: number
}

export type LearningFactor = {
  factor: string
  rawSampleSize: number
  effectiveSampleSize: number
  meanAbsoluteContribution: number
  directionalAccuracy: number
  meanContributionWhenWon: number
  meanContributionWhenLost: number
}

export type ScoreRulePerformance = {
  method: string
  resolvedObservations: number
  correct: number
  accuracy: number
  meanStatedConfidence: number
  calibrationGap: number
}

export type ModelLearningAudit = {
  generatedAt: string
  windowDays: number
  outcomeQuality: {
    totalSamples: number
    trustedSettledSamples: number
    excludedSettledSamples: number
    calibrationEligible: number
    lowConfidenceExcluded: number
    nonBinaryExcluded: number
    eligibleCoveragePct: number
    exclusionReasons: Array<{
      reason: string
      count: number
    }>
  }
  calibrationEvidence: {
    rawSampleSize: number
    effectiveSampleSize: number
    meanPredicted: number
    observedWinRate: number
    calibrationError: number
    brierScore: number
    logLoss: number
  }
  triggers: LearningSegment[]
  priceRegimes: LearningSegment[]
  factors: LearningFactor[]
  scoreRules: ScoreRulePerformance[]
  clv: {
    eligibleBets: number
    closingLineSamples: number
    coveragePct: number
    stakeWeightedClvPct: number | null
  }
}

export type StakingPolicyConfig = {
  fractionalKelly: number
  kellyCapUnits: number
  perEventCapUnits: number
  perPlayerDailyCapUnits: number
  maxOpenExposureUnits: number
  minStakeUnits: number
  minimumEdge: number
  drawdownLookbackBets: number
  drawdownTriggerRoi: number
  drawdownFactor: number
}

export type StakingPolicy = {
  policyName: string
  sourcePath: string
  checksum: string
  fileBacked: boolean
  loadedAt: string
  config: StakingPolicyConfig
}

export type ModelRun = {
  sessionId: number
  label: string
  status: string
  requestedModelVersion: string | null
  effectiveModelVersion: string | null
  effectiveModelFamily: string | null
  policyVersion: string | null
  codeRevision: string | null
  createdAt: string | null
  closedAt: string | null
  lastSyncAt: string | null
  modelCalls: number
  totalBets: number
  openBets: number
  settledBets: number
  wins: number
  losses: number
  pushes: number
  voids: number
  totalStaked: number
  realizedPnl: number
  roiPct: number
  sampleReadinessPct: number
}

export type ModelRunHistory = {
  generatedAt: string
  runs: ModelRun[]
}

export type ModelRegistryEntry = {
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
  createdAt: string | null
}
