import type { ScrapeErrorRecord, ScrapeRunRecord, ScrapeStatus } from '@/features/scrape/types'

export async function fetchScrapeStatus(signal?: AbortSignal): Promise<ScrapeStatus> {
  const response = await fetch('/api/scrape/status', {
    headers: { Accept: 'application/json' },
    signal,
  })
  if (!response.ok) {
    throw new Error(`Scrape status request failed with ${response.status}`)
  }
  return (await response.json()) as ScrapeStatus
}

export async function fetchScrapeRuns(limit = 25, signal?: AbortSignal): Promise<ScrapeRunRecord[]> {
  const response = await fetch(`/api/scrape/runs?limit=${limit}`, {
    headers: { Accept: 'application/json' },
    signal,
  })
  if (!response.ok) {
    throw new Error(`Scrape runs request failed with ${response.status}`)
  }
  return (await response.json()) as ScrapeRunRecord[]
}

export async function fetchScrapeErrors(limit = 25, signal?: AbortSignal): Promise<ScrapeErrorRecord[]> {
  const response = await fetch(`/api/scrape/errors?limit=${limit}`, {
    headers: { Accept: 'application/json' },
    signal,
  })
  if (!response.ok) {
    throw new Error(`Scrape errors request failed with ${response.status}`)
  }
  return (await response.json()) as ScrapeErrorRecord[]
}

export async function startScrapeRun(): Promise<string> {
  const response = await fetch('/api/scrape/run', { method: 'POST' })
  if (!response.ok) {
    throw new Error(`Scrape start failed with ${response.status}`)
  }
  return await response.text()
}

export async function startScrapeRange(fromPage: number, toPage: number): Promise<string> {
  const params = new URLSearchParams({ fromPage: String(fromPage), toPage: String(toPage) })
  const response = await fetch(`/api/scrape/range?${params.toString()}`, { method: 'POST' })
  if (!response.ok) {
    throw new Error(`Scrape range failed with ${response.status}`)
  }
  return await response.text()
}

export async function startScrapeById(id: number): Promise<string> {
  const response = await fetch(`/api/scrape/id/${id}`, { method: 'POST' })
  if (!response.ok) {
    throw new Error(`Scrape by id failed with ${response.status}`)
  }
  return await response.text()
}

export async function stopScrape(): Promise<string> {
  const response = await fetch('/api/scrape/stop', { method: 'POST' })
  if (!response.ok) {
    throw new Error(`Scrape stop failed with ${response.status}`)
  }
  return await response.text()
}
