import type { MatchupAnalysis } from '@/features/live-studio/types'
import type { Player, PlayerMatch, PlayerStatistics } from '@/features/players/types'

async function request<T>(url: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(url, { headers: { Accept: 'application/json' }, signal })
  if (!response.ok) throw new Error(`Player intelligence request failed with ${response.status}`)
  return (await response.json()) as T
}

export function fetchPlayers(signal?: AbortSignal) {
  return request<Player[]>('/api/players', signal)
}

export function fetchPlayerStatistics(signal?: AbortSignal) {
  return request<PlayerStatistics[]>('/api/statistics/players', signal)
}

export function fetchPlayerMatches(playerId: number, limit = 50, signal?: AbortSignal) {
  const query = new URLSearchParams({ limit: String(limit) })
  return request<PlayerMatch[]>(`/api/matches/recent/player/${playerId}?${query}`, signal)
}

export function fetchPlayerMatchup(player1Id: number, player2Id: number, signal?: AbortSignal) {
  const query = new URLSearchParams({ player1Id: String(player1Id), player2Id: String(player2Id) })
  return request<MatchupAnalysis>(`/api/analytics/matchup?${query}`, signal)
}
