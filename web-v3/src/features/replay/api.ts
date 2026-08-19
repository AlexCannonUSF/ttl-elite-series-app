import type { Replay, ReplayDefinitionInput } from '@/features/replay/types'

async function readJson<T>(response: Response, label: string): Promise<T> {
  if (!response.ok) {
    const body = await response.json().catch(() => null) as { message?: string } | null
    throw new Error(body?.message ?? `${label} failed with ${response.status}`)
  }
  return (await response.json()) as T
}

export async function fetchReplays(signal?: AbortSignal): Promise<Replay[]> {
  return readJson<Replay[]>(await fetch('/api/v3/replay', { headers: { Accept: 'application/json' }, signal }), 'Replay history request')
}

export async function fetchReplay(id: number, signal?: AbortSignal): Promise<Replay> {
  return readJson<Replay>(await fetch(`/api/v3/replay/${id}`, { headers: { Accept: 'application/json' }, signal }), 'Replay detail request')
}

export async function createReplay(input: ReplayDefinitionInput): Promise<Replay> {
  return readJson<Replay>(await fetch('/api/v3/replay/definitions', {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  }), 'Replay definition request')
}

export async function startReplay(id: number): Promise<Replay> {
  return readJson<Replay>(await fetch(`/api/v3/replay/${id}/start`, {
    method: 'POST',
    headers: { Accept: 'application/json' },
  }), 'Replay start request')
}

export async function branchReplay(id: number): Promise<Replay> {
  return readJson<Replay>(await fetch(`/api/v3/replay/${id}/branch`, {
    method: 'POST',
    headers: { Accept: 'application/json' },
  }), 'Replay branch request')
}
