import type {
  LiveRunAnalytics,
  ModelCallMonitor,
  ModelCallScorecard,
} from '@/features/live-studio/types'
import type { ModelRun } from '@/features/ml-quality/types'

export type ResearchRunIntegrity = {
  modelIdentityComplete: boolean
  datasetWindowKnown: boolean
  closedRunImmutable: boolean
  postCloseCallCount: number
  settlementCoverageComplete: boolean
  totalCalls: number
  settledCalls: number
  awaitingCalls: number
  settlementCoveragePct: number
  status: string
  explanation: string
}

export type ResearchRunDetail = {
  generatedAt: string
  run: ModelRun
  scorecard: ModelCallScorecard
  analytics: LiveRunAnalytics
  pipeline: ModelCallMonitor
  foundation: ResearchRunFoundation
  integrity: ResearchRunIntegrity
}

export type ResearchRunFoundation = {
  runId: number
  opportunityCount: number
  legacyModelCallCount: number
  synchronizedOpportunityCount: number
  telemetryCompletenessPct: number
  modelLanes: Array<{
    id: number
    laneKey: string
    displayName: string
    role: string
    ordinal: number
    modelFamily: string | null
    modelVersion: string | null
    artifactChecksum: string | null
    featureSchemaChecksum: string | null
    calibrationId: string | null
    enabled: boolean
    primary: boolean
    evaluations: number
    opportunityCoveragePct: number
    resolved: number
    correct: number
    accuracyPct: number
    brierScore: number | null
    pricedResolved: number
    flatStakePnl: number
    flatStakeRoiPct: number
  }>
  portfolios: Array<{
    id: number
    portfolioKey: string
    displayName: string
    type: string
    modelLaneKey: string | null
    policyVersion: string | null
    enabled: boolean
    primary: boolean
    decisions: number
    actioned: number
    passed: number
    opportunityCoveragePct: number
    resolved: number
    correct: number
    accuracyPct: number
    pricedResolved: number
    flatStakePnl: number
    flatStakeRoiPct: number
  }>
  benchmarks: Array<{
    benchmarkKey: string
    evaluations: number
    opportunityCoveragePct: number
    resolved: number
    correct: number
    accuracyPct: number
    pricedResolved: number
    flatStakePnl: number
    flatStakeRoiPct: number
  }>
  annotations: ResearchRunAnnotation[]
}

export type ResearchRunAnnotation = {
  id: number
  targetType: string
  targetId: string | null
  text: string
  tags: string[]
  author: string
  createdAt: string
}

export type ResearchRunComparisonRow = {
  run: ModelRun
  naturalCohort: LiveRunAnalytics
  distinctOpportunityCount: number
  sharedOpportunityCount: number
  sharedCoveragePct: number
}

export type ResearchRunComparison = {
  generatedAt: string
  requestedRunIds: number[]
  sharedOpportunityCount: number
  runs: ResearchRunComparisonRow[]
  cautions: string[]
}

export type ExperimentCollection = {
  id: number
  name: string
  description: string | null
  hypothesis: string | null
  status: string
  createdBy: string
  createdAt: string
  updatedAt: string
  runs: Array<{
    id: number
    runId: number
    role: string
    note: string | null
    linkedAt: string
  }>
}
