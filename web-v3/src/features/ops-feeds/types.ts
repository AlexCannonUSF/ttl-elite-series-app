export type FeedStatus = 'HEALTHY' | 'DEGRADED' | 'DOWN' | 'IDLE'

export type OpsFeedStatus = {
  sourceId: string
  trustTier: string
  capabilities: string[]
  status: FeedStatus
  liveTick: boolean
  successRate5m: number | null
  p50LatencyMs: number | null
  p95LatencyMs: number | null
  stalenessSeconds: number | null
  inFlight: number
  backoffState: string | null
  lastError: string | null
  dlqDepth: number
  lastSuccessAt: string | null
  lastFailureAt: string | null
  lastSampleAt: string | null
}

export type OpsFeedsSummary = {
  totalSources: number
  healthySources: number
  degradedSources: number
  downSources: number
  idleSources: number
  totalDlqDepth: number
}

export type OpsFeedsResponse = {
  generatedAt: string
  summary: OpsFeedsSummary
  feeds: OpsFeedStatus[]
}

export type OpsStreamWorkerStatus = 'READY' | 'OFF' | string

export type OpsStreamWorker = {
  component: string
  workerType: string
  rolloutState: string
  enabled: boolean
  status: OpsStreamWorkerStatus
  detail: string
}

export type OpsStreamVlmUsage = {
  enabled: boolean
  meteringState: string
  activeForceRequests: number
  framesSentToday: number
  successfulCallsToday: number
  failedCallsToday: number
  estimatedCostUsdToday: number
  lastRequestAt: string | null
  detail: string
}

export type OpsStreamsSummary = {
  totalWorkers: number
  enabledWorkers: number
  offWorkers: number
  routeOverrides: number
  routeWarnings: number
  roiTemplates: number
  activeForceRequests: number
}

export type OpsStreamsResponse = {
  generatedAt: string
  summary: OpsStreamsSummary
  vlmUsage: OpsStreamVlmUsage
  workers: OpsStreamWorker[]
}
