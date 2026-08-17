export type ReplayEvent = {
  sequenceNumber: number
  sourceRunId: number
  sourceCallId: number
  eventTime: string
  eventType: string
  eventName: string | null
  captureType: string | null
  predictedWinnerName: string | null
  modelProbability: number | null
  hardRockAmericanOdds: number | null
  decisionStatus: string | null
  pipelineStage: string | null
  effectiveOutcome: string | null
  outcomeSource: string | null
  flatStakeProfit: number | null
}

export type Replay = {
  id: number
  parentReplayId: number | null
  label: string
  status: string
  replayMode: string
  sourceRunIds: number[]
  windowStart: string | null
  windowEnd: string | null
  captureRule: string
  modelLaneKeys: string[]
  portfolioKeys: string[]
  executionBook: string
  initialBankroll: number
  maxQuoteAgeSeconds: number
  deterministicSeed: number
  definitionChecksum: string
  leakageAuditStatus: string
  reproducible: boolean
  eventCount: number
  resolvedCount: number
  pricedResolvedCount: number
  correctCount: number
  accuracyPct: number
  flatStakePnl: number
  flatStakeRoiPct: number
  createdAt: string
  startedAt: string | null
  completedAt: string | null
  events: ReplayEvent[]
  integrityNotes: string[]
}

export type ReplayDefinitionInput = {
  label: string
  sourceRunIds: number[]
  replayMode: string
  windowStart?: string | null
  windowEnd?: string | null
  captureRule: string
  modelLaneKeys: string[]
  portfolioKeys: string[]
  executionBook: string
  initialBankroll: number
  maxQuoteAgeSeconds: number
  deterministicSeed: number
}
