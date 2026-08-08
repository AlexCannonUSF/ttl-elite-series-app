import type {
  LiveOddsRecommendation,
  MatchupAnalysis,
  ModelCallScorecard,
  PaperTradingSession,
  PaperTradingSyncResult,
  TrackedMatchObservation,
} from '@/features/live-studio/types'

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
