import type {
  LiveOddsRecommendation,
  HardRockScoreStreamStatus,
  MatchupAnalysis,
  LiveRunAnalytics,
  ModelCallApproval,
  ModelCallMonitor,
  ModelCallScorecard,
  ModelCallTracking,
  PaperTradingSession,
  PaperTradingSyncResult,
  TrackedMatchObservation,
} from '@/features/live-studio/types'

export async function fetchHardRockScoreStreamStatus(signal?: AbortSignal): Promise<HardRockScoreStreamStatus> {
  const response = await fetch('/api/live-studio/score-stream', {
    headers: { Accept: 'application/json' },
    signal,
  })
  if (!response.ok) throw new Error(`Hard Rock score stream request failed with ${response.status}`)
  return (await response.json()) as HardRockScoreStreamStatus
}

export async function fetchModelCallScorecard(
  limit = 40,
  signal?: AbortSignal,
): Promise<ModelCallScorecard> {
  const query = new URLSearchParams({ limit: String(limit) })
  const response = await fetch(`/api/live-studio/model-scorecard?${query}`, {
    headers: { Accept: 'application/json' },
    signal,
  })

  if (!response.ok) {
    throw new Error(`Model scorecard request failed with ${response.status}`)
  }

  return (await response.json()) as ModelCallScorecard
}

export async function fetchLiveRunAnalytics(limit = 250, signal?: AbortSignal): Promise<LiveRunAnalytics> {
  const query = new URLSearchParams({ limit: String(limit) })
  const response = await fetch(`/api/live-studio/live-run-analytics?${query}`, {
    headers: { Accept: 'application/json' },
    signal,
  })
  if (!response.ok) throw new Error(`Live-run analytics request failed with ${response.status}`)
  return (await response.json()) as LiveRunAnalytics
}

export async function fetchModelCallMonitor(limit = 100, signal?: AbortSignal): Promise<ModelCallMonitor> {
  const query = new URLSearchParams({ limit: String(limit) })
  const response = await fetch(`/api/live-studio/model-calls?${query}`, {
    headers: { Accept: 'application/json' },
    signal,
  })
  if (!response.ok) throw new Error(`Model pipeline request failed with ${response.status}`)
  return (await response.json()) as ModelCallMonitor
}

export async function fetchModelCallTracking(callId: number, signal?: AbortSignal): Promise<ModelCallTracking> {
  const response = await fetch(`/api/live-studio/model-calls/${callId}`, {
    headers: { Accept: 'application/json' },
    signal,
  })
  if (!response.ok) throw new Error(`Model call request failed with ${response.status}`)
  return (await response.json()) as ModelCallTracking
}

export async function approveModelCall(callId: number, approval: ModelCallApproval): Promise<ModelCallTracking> {
  const response = await fetch(`/api/live-studio/model-calls/${callId}/approve`, {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify(approval),
  })
  if (!response.ok) {
    const body = await response.json().catch(() => null) as { message?: string } | null
    throw new Error(body?.message ?? `Viewer approval failed with ${response.status}`)
  }
  return (await response.json()) as ModelCallTracking
}

export async function fetchMatchupAnalysis(
  player1Id: number,
  player2Id: number,
  signal?: AbortSignal,
): Promise<MatchupAnalysis> {
  const query = new URLSearchParams({
    player1Id: String(player1Id),
    player2Id: String(player2Id),
  })
  const response = await fetch(`/api/analytics/matchup?${query}`, {
    headers: {
      Accept: 'application/json',
    },
    signal,
  })

  if (!response.ok) {
    throw new Error(`Matchup intelligence request failed with ${response.status}`)
  }

  return (await response.json()) as MatchupAnalysis
}

export async function fetchLiveSession(signal?: AbortSignal): Promise<PaperTradingSession> {
  const response = await fetch('/api/live-studio/session', {
    headers: {
      Accept: 'application/json',
    },
    signal,
  })

  if (!response.ok) {
    throw new Error(`Live session request failed with ${response.status}`)
  }

  return (await response.json()) as PaperTradingSession
}

export async function syncLiveSession({
  limit = 80,
  modelVersion,
  strategy = 'CONSERVATIVE',
}: {
  limit?: number
  modelVersion?: string
  strategy?: string
} = {}): Promise<PaperTradingSyncResult> {
  const query = new URLSearchParams()
  query.set('strategy', strategy)
  query.set('limit', String(limit))
  if (modelVersion) {
    query.set('modelVersion', modelVersion)
  }

  const response = await fetch(`/api/live-studio/sync?${query}`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
    },
  })

  if (!response.ok) {
    throw new Error(`Live session sync failed with ${response.status}`)
  }

  return (await response.json()) as PaperTradingSyncResult
}

export async function fetchLiveBoard({
  includeUnresolved = true,
  limit = 80,
  modelVersion,
  signal,
  strategy = 'CONSERVATIVE',
}: {
  includeUnresolved?: boolean
  limit?: number
  modelVersion?: string
  signal?: AbortSignal
  strategy?: string
} = {}): Promise<LiveOddsRecommendation[]> {
  const query = new URLSearchParams()
  query.set('strategy', strategy)
  query.set('limit', String(limit))
  query.set('includeUnresolved', String(includeUnresolved))
  if (modelVersion) {
    query.set('modelVersion', modelVersion)
  }

  const response = await fetch(`/api/live-studio/board?${query}`, {
    headers: {
      Accept: 'application/json',
    },
    signal,
  })

  if (!response.ok) {
    throw new Error(`Live board request failed with ${response.status}`)
  }

  return (await response.json()) as LiveOddsRecommendation[]
}

export async function fetchMatchTimeline(
  eventKey: string,
  signal?: AbortSignal,
): Promise<TrackedMatchObservation[]> {
  const response = await fetch(`/api/live-studio/match/${encodeURIComponent(eventKey)}/timeline`, {
    headers: {
      Accept: 'application/json',
    },
    signal,
  })

  if (!response.ok) {
    throw new Error(`Match timeline request failed with ${response.status}`)
  }

  return (await response.json()) as TrackedMatchObservation[]
}
