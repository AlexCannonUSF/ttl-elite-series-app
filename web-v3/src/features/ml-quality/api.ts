import type { MlQualityResponse, ModelLearningAudit, ModelRegistryEntry, ModelRunHistory, StakingPolicy } from '@/features/ml-quality/types'

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

export async function fetchModelLearningAudit(windowDays = 180, signal?: AbortSignal): Promise<ModelLearningAudit> {
  const response = await fetch(`/api/v3/ml/learning-audit?windowDays=${windowDays}`, {
    headers: { Accept: 'application/json' },
    signal,
  })
  if (!response.ok) {
    throw new Error(`Model learning audit request failed with ${response.status}`)
  }
  return (await response.json()) as ModelLearningAudit
}

export async function fetchModelRunHistory(limit = 25, signal?: AbortSignal): Promise<ModelRunHistory> {
  const response = await fetch(`/api/v3/ml/runs?limit=${limit}`, {
    headers: { Accept: 'application/json' },
    signal,
  })
  if (!response.ok) throw new Error(`Model run history request failed with ${response.status}`)
  return (await response.json()) as ModelRunHistory
}

export async function fetchModelRegistry(limit = 30, signal?: AbortSignal): Promise<ModelRegistryEntry[]> {
  const response = await fetch(`/api/analytics/models/registry?limit=${limit}`, {
    headers: { Accept: 'application/json' },
    signal,
  })
  if (!response.ok) throw new Error(`Model registry request failed with ${response.status}`)
  return (await response.json()) as ModelRegistryEntry[]
}

export async function fetchStakingPolicy(signal?: AbortSignal): Promise<StakingPolicy> {
  const response = await fetch('/api/v3/ops/staking/policy', {
    headers: { Accept: 'application/json' },
    signal,
  })
  if (!response.ok) {
    throw new Error(`Staking policy request failed with ${response.status}`)
  }
  return (await response.json()) as StakingPolicy
}

export async function reloadStakingPolicy(): Promise<StakingPolicy> {
  const response = await fetch('/api/v3/ops/staking/policy/reload', {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ triggeredBy: 'admin-ui' }),
  })
  if (!response.ok) {
    throw new Error(`Staking policy reload failed with ${response.status}`)
  }
  return (await response.json()) as StakingPolicy
}
