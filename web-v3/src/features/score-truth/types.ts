export type JsonValue =
  | string
  | number
  | boolean
  | null
  | JsonValue[]
  | { [key: string]: JsonValue }

export type ScoreTruthEvidenceSnapshot = {
  evidenceId: number
  betId: number
  trackedEventId: string
  bundleAsOf: string
  coverageState: string
  ambiguityScore: number
  confidence: number
  learningEligible: boolean
  learningExclusionReason: string | null
  payload: { [key: string]: JsonValue } | null
}

export type ScoreTruthContradiction = {
  id: number
  evidenceId: number
  betId: number
  observedAt: string
  kind: string
  severity: number
  resolved: boolean
  resolutionNote: string | null
  payload: { [key: string]: JsonValue } | null
}

export type ScoreTruthDecision = {
  id: number
  betId: number
  trackedEventId: string
  decision: string
  reason: string
  confidence: number | null
  evidenceId: number | null
  decidedAt: string
  payload: { [key: string]: JsonValue } | null
}

export type ScoreTruthEvidenceResponse = {
  generatedAt: string
  matchId: string
  evidence: ScoreTruthEvidenceSnapshot
  contradictions: ScoreTruthContradiction[]
  decisions: ScoreTruthDecision[]
}

export type ScoreTruthReviewItem = {
  decisionId: number
  betId: number
  trackedEventId: string | null
  reason: string
  confidence: number | null
  evidenceId: number | null
  decidedAt: string | null
  payload: { [key: string]: JsonValue } | null
  reviewStatus: string
  reviewer: string | null
  reviewComment: string | null
  reviewedAt: string | null
  reviewActionId: number | null
}

export type ScoreTruthReviewQueueResponse = {
  generatedAt: string
  page: number
  size: number
  totalItems: number
  totalPages: number
  hasPrevious: boolean
  hasNext: boolean
  items: ScoreTruthReviewItem[]
}

export type ScoreTruthReviewAction = 'ACCEPT' | 'REJECT' | 'COMMENT'

export type ScoreTruthReviewActionRequest = {
  action: ScoreTruthReviewAction
  comment?: string | null
  reviewer?: string | null
}

export type ScoreTruthReviewActionResponse = {
  id: number
  decisionId: number
  action: ScoreTruthReviewAction
  reviewer: string
  comment: string | null
  reviewedAt: string
}

export type SettlementReviewItem = {
  betId: number
  sessionId: number
  status: string
  eventName: string
  competitionName: string
  player1Name: string
  player2Name: string
  selectedSide: string
  winnerPlayerId: number | null
  winnerName: string | null
  settlementSource: string | null
  settlementReason: string | null
  settledAt: string | null
  selectedCandidateMatchId: number | null
  selectedCandidateDate: string | null
  playerSetConfidence: number | null
  feedIdentityMatch: boolean | null
  archiveConfidence: number | null
  selectedCandidateInRecentCompleted: boolean
  recentCompletedCandidateCount: number
  sameDayCandidateCount: number
  lastObservedScore: string | null
  lastObservedPhase: string | null
  lateScoreDirectionPlayerId: number | null
  lateScoreDirectionName: string | null
  scoreEvidenceQuality: string | null
  scoreEvidenceFinality: string | null
  scoreEvidenceConfidence: number | null
  scoreEvidenceObservationCount: number | null
  scoreEvidenceSourceCount: number | null
  scoreEvidenceAgreeingSources: number | null
  scoreEvidenceCompletionSignals: number | null
  evidenceId: number | null
  coverageState: string | null
  ambiguityScore: number | null
  settlementConfidence: number | null
  trustBand: 'HIGH' | 'MEDIUM' | 'LOW'
  suspicious: boolean
  suspicionFlags: string[]
  contradictionFlags: string[]
  explanation: string
}

export type SettlementReviewPageResponse = {
  generatedAt: string
  page: number
  size: number
  totalItems: number
  totalPages: number
  hasPrevious: boolean
  hasNext: boolean
  suspiciousItems: number
  highTrustItems: number
  lowTrustItems: number
  items: SettlementReviewItem[]
}
