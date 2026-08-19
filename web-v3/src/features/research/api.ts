import type { ModelRunHistory } from '@/features/ml-quality/types'
import type { ExperimentCollection, ResearchRunAnnotation, ResearchRunComparison, ResearchRunDetail, ResearchRunFoundation } from '@/features/research/types'

async function readJson<T>(response: Response, label: string): Promise<T> {
  if (!response.ok) {
    const body = await response.json().catch(() => null) as { message?: string } | null
    throw new Error(body?.message ?? `${label} failed with ${response.status}`)
  }
  return (await response.json()) as T
}

export async function fetchResearchRuns(limit = 100, signal?: AbortSignal): Promise<ModelRunHistory> {
  const query = new URLSearchParams({ limit: String(limit) })
  const response = await fetch(`/api/v3/research/runs?${query}`, {
    headers: { Accept: 'application/json' },
    signal,
  })
  return readJson<ModelRunHistory>(response, 'Run history request')
}

export async function fetchResearchRun(runId: number, signal?: AbortSignal): Promise<ResearchRunDetail> {
  const response = await fetch(`/api/v3/research/runs/${runId}`, {
    headers: { Accept: 'application/json' },
    signal,
  })
  return readJson<ResearchRunDetail>(response, 'Run detail request')
}

export async function fetchResearchFoundation(runId: number, signal?: AbortSignal): Promise<ResearchRunFoundation> {
  const response = await fetch(`/api/v3/research/runs/${runId}/foundation`, {
    headers: { Accept: 'application/json' },
    signal,
  })
  return readJson<ResearchRunFoundation>(response, 'Run research foundation request')
}

export async function compareResearchRuns(
  runIds: number[],
  signal?: AbortSignal,
): Promise<ResearchRunComparison> {
  const response = await fetch('/api/v3/research/compare', {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ runIds, trendLimit: 250 }),
    signal,
  })
  return readJson<ResearchRunComparison>(response, 'Run comparison request')
}

export async function addRunAnnotation(
  runId: number,
  text: string,
  tags: string[],
): Promise<ResearchRunAnnotation> {
  const response = await fetch(`/api/v3/research/runs/${runId}/annotations`, {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ targetType: 'RUN', text, tags, author: 'OPERATOR' }),
  })
  return readJson<ResearchRunAnnotation>(response, 'Run annotation request')
}

export async function fetchExperiments(signal?: AbortSignal): Promise<ExperimentCollection[]> {
  const response = await fetch('/api/v3/research/experiments', {
    headers: { Accept: 'application/json' },
    signal,
  })
  return readJson<ExperimentCollection[]>(response, 'Experiment history request')
}

export async function createExperiment(input: {
  name: string
  description?: string
  hypothesis?: string
}): Promise<ExperimentCollection> {
  const response = await fetch('/api/v3/research/experiments', {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...input, createdBy: 'OPERATOR' }),
  })
  return readJson<ExperimentCollection>(response, 'Experiment creation request')
}

export async function linkExperimentRun(
  experimentId: number,
  runId: number,
  role: string,
  note?: string,
): Promise<ExperimentCollection> {
  const response = await fetch(`/api/v3/research/experiments/${experimentId}/runs`, {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ runId, role, note }),
  })
  return readJson<ExperimentCollection>(response, 'Experiment run link request')
}
