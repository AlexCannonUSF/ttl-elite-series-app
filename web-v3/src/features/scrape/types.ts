// Mirrors com.ttl.tabletennis.scrape.TtSeriesScraper records.

export type ScrapeStatus = {
  running: boolean
  mode: string
  startedAt: string | null
  finishedAt: string | null
  savedMatches: number
  error: string | null
}

export type ScrapeRunRecord = {
  runId: number
  mode: string
  startedAt: string | null
  finishedAt: string | null
  status: string
  savedMatches: number
  error: string | null
}

export type ScrapeErrorRecord = {
  runId: number
  occurredAt: string | null
  mode: string
  message: string
  url: string | null
  context: string | null
  htmlSnippet: string | null
}
