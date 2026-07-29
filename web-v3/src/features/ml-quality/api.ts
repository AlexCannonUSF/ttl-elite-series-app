import type { MlQualityResponse } from '@/features/ml-quality/types'

export type MlQualityQuery = {
  windowDays?: number
  binCount?: number
}

export async function fetchMlQuality(query: MlQualityQuery = {}, signal?: AbortSignal): Promise<MlQualityResponse> {
  const params = new URLSearchParams()
  if (typeof query.windowDays === 'number') {
    params.set('windowDays', String(query.windowDays))
  }
  if (typeof query.binCount === 'number') {
    params.set('binCount', String(query.binCount))
  }
  const search = params.toString()
  const response = await fetch(`/api/v3/ml/quality${search ? `?${search}` : ''}`, {
    headers: { Accept: 'application/json' },
    signal,
  })
  if (!response.ok) {
    throw new Error(`ML quality request failed with ${response.status}`)
  }
  return (await response.json()) as MlQualityResponse
}
