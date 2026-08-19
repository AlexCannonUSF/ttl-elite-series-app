import type { MarketIntelligence } from '@/features/market/types'

export async function fetchMarketIntelligence(identity: string, signal?: AbortSignal): Promise<MarketIntelligence> {
  const query = new URLSearchParams({ identity, historyLimit: '400' })
  const response = await fetch(`/api/v3/market?${query}`, { headers: { Accept: 'application/json' }, signal })
  if (!response.ok) throw new Error(`Market intelligence request failed with ${response.status}`)
  return (await response.json()) as MarketIntelligence
}
