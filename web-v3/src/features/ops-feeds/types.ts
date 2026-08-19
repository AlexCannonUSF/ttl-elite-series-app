export type FeedStatus = 'HEALTHY' | 'DEGRADED' | 'DOWN' | 'IDLE'

export type OpsFeedStatus = {
  sourceId: string
  trustTier: string
  capabilities: string[]
  lifecycle: 'ACTIVE' | 'STANDBY' | 'DISABLED'
  demandState: string
  cause: string
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
  activeSources: number
  standbySources: number
  disabledSources: number
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

export type OpsIngestBus = {
  mode: string
  status: string
  redisAvailable: boolean
  activeBus: string
  streamPrefix: string
  partitionLagWarning: number
  partitionLagCritical: number
  detail: string
}

export type OpsIngestDlqSource = {
  sourceId: string
  trustTier: string
  depth: number
}

export type OpsIngestDlq = {
  totalDepth: number
  sources: OpsIngestDlqSource[]
}

export type OpsIngestPartition = {
  streamKey: string
  family: string
  status: string
  streamLength: number
  consumerGroups: number
  pendingCount: number
  oldestPendingAgeSeconds: number | null
  redeliveryCount: number
  lag: number | null
  lastGeneratedId: string | null
  detail: string
}

export type OpsIngestTelemetry = {
  published: number
  decoded: number
  validated: number
  dispatched: number
  acknowledged: number
  rejected: number
  dlq: number
  pollFailures: number
  parityDelta: number
  redeliveries: number
  throughputPerMinute: number | null
  consumerHeartbeatAt: string | null
  lastProcessedAt: string | null
  latestEventAgeMs: number | null
  fullTrafficCoverage: boolean
  soakSeconds: number | null
  soakStatus: string
}

export type OpsIngestResponse = {
  generatedAt: string
  bus: OpsIngestBus
  telemetry: OpsIngestTelemetry
  dlq: OpsIngestDlq
  partitions: OpsIngestPartition[]
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
  activeWorkers: number
  availableComponents: number
  pipelineStatus: string
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
