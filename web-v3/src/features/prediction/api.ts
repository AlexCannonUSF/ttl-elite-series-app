import type { PredictionPanelQuery, PredictionPanelResponse } from '@/features/prediction/types'

export type ParsedMatchKey = {
  player1Id: number
  player2Id: number
  asOfDate?: string
}

/**
 * Match keys arriving on the URL have the shape ``low-high`` or
 * ``low-high@asOfDate``. We accept either order and let the backend
 * canonicalise. Returns null when the segment isn't a recognisable
 * matchup id (so the caller can render an explanatory error).
 */
export function parseMatchKey(matchKey: string | undefined): ParsedMatchKey | null {
  if (!matchKey) {
    return null
  }
  const parts = matchKey.split('@')
  const pair = parts[0] ?? ''
  const asOfDate = parts[1]
  const idParts = pair.split('-')
  const player1Id = Number.parseInt(idParts[0] ?? '', 10)
  const player2Id = Number.parseInt(idParts[1] ?? '', 10)
  if (!Number.isFinite(player1Id) || !Number.isFinite(player2Id)) {
    return null
  }
  return {
    player1Id,
    player2Id,
    asOfDate: asOfDate && /^\d{4}-\d{2}-\d{2}$/.test(asOfDate) ? asOfDate : undefined,
  }
}

export async function fetchPredictionPanel(
  query: PredictionPanelQuery,
  signal?: AbortSignal,
): Promise<PredictionPanelResponse> {
  const params = new URLSearchParams()
  params.set('player1Id', String(query.player1Id))
  params.set('player2Id', String(query.player2Id))
  if (query.asOfDate) {
    params.set('asOfDate', query.asOfDate)
  }
  if (query.modelFamily) {
    params.set('modelFamily', query.modelFamily)
  }
  if (typeof query.topK === 'number') {
    params.set('topK', String(query.topK))
  }
  const response = await fetch(`/api/v3/matches/prediction?${params.toString()}`, {
    headers: { Accept: 'application/json' },
    signal,
  })
  if (!response.ok) {
    throw new Error(`Prediction panel request failed with ${response.status}`)
  }
  return (await response.json()) as PredictionPanelResponse
}
