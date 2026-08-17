import type { MetricDefinition } from '@/features/glossary/types'

export async function fetchMetricDefinitions(signal?: AbortSignal): Promise<MetricDefinition[]> {
  const response = await fetch('/api/v3/definitions/metrics', { headers: { Accept: 'application/json' }, signal })
  if (!response.ok) throw new Error(`Metric glossary request failed with ${response.status}`)
  return (await response.json()) as MetricDefinition[]
}
