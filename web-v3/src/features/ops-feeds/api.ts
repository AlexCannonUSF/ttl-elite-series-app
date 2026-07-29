import type { OpsFeedsResponse, OpsIngestResponse, OpsStreamsResponse } from '@/features/ops-feeds/types'

export async function fetchOpsFeeds(signal?: AbortSignal): Promise<OpsFeedsResponse> {
  const response = await fetch('/api/v3/ops/feeds', {
    headers: {
      Accept: 'application/json',
    },
    signal,
  })

  if (!response.ok) {
    throw new Error(`Ops feeds request failed with ${response.status}`)
  }

  return (await response.json()) as OpsFeedsResponse
}

export async function fetchOpsIngest(signal?: AbortSignal): Promise<OpsIngestResponse> {
  const response = await fetch('/api/v3/ops/ingest', {
    headers: {
      Accept: 'application/json',
    },
    signal,
  })

  if (!response.ok) {
    throw new Error(`Ops ingest request failed with ${response.status}`)
  }

  return (await response.json()) as OpsIngestResponse
}

export async function fetchOpsStreams(signal?: AbortSignal): Promise<OpsStreamsResponse> {
  const response = await fetch('/api/v3/ops/feeds/streams', {
    headers: {
      Accept: 'application/json',
    },
    signal,
  })

  if (!response.ok) {
    throw new Error(`Ops streams request failed with ${response.status}`)
  }

  return (await response.json()) as OpsStreamsResponse
}
