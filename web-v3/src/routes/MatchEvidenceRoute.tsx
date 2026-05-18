import { type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AlertTriangle, Clock3, FileSearch2, RefreshCcw, ShieldAlert, Waypoints } from 'lucide-react'
import { Link, useParams } from 'react-router-dom'

import { V3Shell } from '@/components/layout/V3Shell'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { fetchScoreTruthEvidence } from '@/features/score-truth/api'
import type {
  JsonValue,
  ScoreTruthContradiction,
  ScoreTruthDecision,
  ScoreTruthEvidenceResponse,
} from '@/features/score-truth/types'
import { cn } from '@/lib/utils'

type TimelineEntry = {
  id: string
  lane: string
  source: string
  observedAt: string
  confidence: number | null
  phase: string
  summary: string
  detail: string
}

const REFRESH_INTERVAL_MS = 5000

export function MatchEvidenceRoute() {
  const { id } = useParams()
  const [data, setData] = useState<ScoreTruthEvidenceResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const mountedRef = useRef(true)
  const evidenceId = id ?? ''

  useEffect(() => {
    return () => {
      mountedRef.current = false
    }
  }, [])

  const loadEvidence = useCallback(async (background: boolean) => {
    if (!evidenceId) {
      if (mountedRef.current) {
        setError('Route is missing an evidence id.')
        setLoading(false)
      }
      return
    }

    if (mountedRef.current) {
      if (background) {
        setRefreshing(true)
      } else {
        setLoading(true)
      }
    }

    try {
      const next = await fetchScoreTruthEvidence(evidenceId)
      if (!mountedRef.current) {
        return
      }
      setData(next)
      setError(null)
    } catch (nextError) {
      if (!mountedRef.current) {
        return
      }
      setError(nextError instanceof Error ? nextError.message : 'Unable to load score-truth evidence right now.')
    } finally {
      if (!mountedRef.current) {
        return
      }
      if (background) {
        setRefreshing(false)
      } else {
        setLoading(false)
      }
    }
  }, [evidenceId])

  useEffect(() => {
    void loadEvidence(false)
    const interval = window.setInterval(() => {
      void loadEvidence(true)
    }, REFRESH_INTERVAL_MS)
    return () => {
      window.clearInterval(interval)
    }
  }, [loadEvidence])

  const timeline = useMemo(() => buildTimeline(data), [data])
  const counts = useMemo(() => summarizeObservationCounts(data), [data])

  return (
    <V3Shell
      eyebrow="TTLElite Series 3.0"
      title="Match Evidence"
      description="This viewer renders the latest persisted Score Truth bundle, contradiction feed, and shadow decision history for one tracked match or bet."
      badges={
        <>
          <Badge variant="accent">Phase 02</Badge>
          <Badge>Evidence Viewer</Badge>
          <Badge>Auto Refresh 5s</Badge>
        </>
      }
      actions={
        <>
          <Button variant="ghost" asChild>
            <Link to="/ops/diffs">Back to Diffs</Link>
          </Button>
          <Button variant="secondary" onClick={() => void loadEvidence(true)} disabled={loading || refreshing}>
            <RefreshCcw className={cn('size-4', refreshing && 'animate-spin')} />
            Refresh now
          </Button>
        </>
      }
    >
      <section className="grid gap-5 xl:grid-cols-[1.08fr_0.92fr]">
        <Card>
          <CardHeader>
            <Badge variant="accent" className="w-fit">
              Evidence Summary
            </Badge>
            <CardTitle>Latest persisted bundle</CardTitle>
            <CardDescription>
              Coverage, ambiguity, confidence, and observation mix for the latest shadow evidence snapshot.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-5">
            {loading && !data ? (
              <div className="rounded-[24px] border border-dashed border-[var(--line-strong)] bg-[rgba(255,255,255,0.58)] p-6 text-sm text-[var(--ink-muted)]">
                Loading evidence snapshot…
              </div>
            ) : null}

            {data ? (
              <>
                <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
                  <MetricTile label="Coverage" value={data.evidence.coverageState} icon={ShieldAlert} />
                  <MetricTile label="Ambiguity" value={toPercent(data.evidence.ambiguityScore)} icon={AlertTriangle} />
                  <MetricTile label="Confidence" value={toPercent(data.evidence.confidence)} icon={Clock3} />
                  <MetricTile label="Timeline Rows" value={String(timeline.length)} icon={Waypoints} />
                </div>

                <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
                  <StatCard label="Live" value={String(counts.live)} />
                  <StatCard label="Mirror" value={String(counts.mirror)} />
                  <StatCard label="Stream" value={String(counts.stream)} />
                  <StatCard label="Confirm" value={String(counts.confirm)} />
                </div>

                <div className="rounded-[24px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4">
                  <div className="grid gap-3 text-sm text-[var(--ink-muted)] sm:grid-cols-2">
                    <div>
                      <p className="text-xs font-semibold uppercase tracking-[0.24em]">Route Id</p>
                      <p className="mt-2 font-medium text-[var(--ink-strong)]">{evidenceId}</p>
                    </div>
                    <div>
                      <p className="text-xs font-semibold uppercase tracking-[0.24em]">Tracked Event</p>
                      <p className="mt-2 font-medium text-[var(--ink-strong)]">{data.evidence.trackedEventId}</p>
                    </div>
                    <div>
                      <p className="text-xs font-semibold uppercase tracking-[0.24em]">Bet Id</p>
                      <p className="mt-2 font-medium text-[var(--ink-strong)]">#{data.evidence.betId}</p>
                    </div>
                    <div>
                      <p className="text-xs font-semibold uppercase tracking-[0.24em]">Bundle As Of</p>
                      <p className="mt-2 font-medium text-[var(--ink-strong)]">{formatDateTime(data.evidence.bundleAsOf)}</p>
                    </div>
                  </div>
                </div>
              </>
            ) : null}

            {error ? (
              <InlineAlert>
                <AlertTriangle className="size-4" />
                <span>{error}</span>
              </InlineAlert>
            ) : null}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <Badge className="w-fit">Contradictions</Badge>
            <CardTitle>Disagreement surface</CardTitle>
            <CardDescription>
              Contradiction entries are persisted alongside the evidence snapshot and are the first things to inspect when a shadow decision deviates.
            </CardDescription>
          </CardHeader>
          <CardContent className="grid gap-3">
            {data && data.contradictions.length === 0 ? (
              <div className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4 text-sm text-[var(--ink-muted)]">
                No contradictions are attached to this evidence bundle.
              </div>
            ) : null}

            {data?.contradictions.map((contradiction) => (
              <ContradictionCard key={contradiction.id} contradiction={contradiction} />
            ))}
          </CardContent>
        </Card>
      </section>

      <Card className="mt-5">
        <CardHeader>
          <Badge variant="accent" className="w-fit">
            Observation Timeline
          </Badge>
          <CardTitle>Merged observations with confidence</CardTitle>
          <CardDescription>
            All persisted observation lanes are merged here so we can see score continuity and source transitions in time order.
          </CardDescription>
        </CardHeader>
        <CardContent className="mt-5 overflow-x-auto">
          {data && timeline.length > 0 ? (
            <table className="min-w-full border-separate border-spacing-y-3">
              <thead>
                <tr className="text-left text-xs uppercase tracking-[0.22em] text-[var(--ink-muted)]">
                  <th className="px-3 pb-1 font-semibold">Lane</th>
                  <th className="px-3 pb-1 font-semibold">Source</th>
                  <th className="px-3 pb-1 font-semibold">Phase</th>
                  <th className="px-3 pb-1 font-semibold">Score</th>
                  <th className="px-3 pb-1 font-semibold">Confidence</th>
                  <th className="px-3 pb-1 font-semibold">Observed</th>
                </tr>
              </thead>
              <tbody>
                {timeline.map((entry) => (
                  <tr
                    key={entry.id}
                    className="rounded-[22px] bg-[rgba(255,255,255,0.74)] text-sm text-[var(--ink)] shadow-[0_12px_32px_-24px_rgba(15,23,42,0.45)]"
                  >
                    <td className="rounded-l-[22px] px-3 py-4 font-medium text-[var(--ink-strong)]">{entry.lane}</td>
                    <td className="px-3 py-4 text-[var(--ink-muted)]">{entry.source}</td>
                    <td className="px-3 py-4 text-[var(--ink-muted)]">{entry.phase}</td>
                    <td className="px-3 py-4">
                      <p className="font-medium text-[var(--ink-strong)]">{entry.summary}</p>
                      <p className="mt-1 text-xs text-[var(--ink-muted)]">{entry.detail}</p>
                    </td>
                    <td className="px-3 py-4 text-[var(--ink-muted)]">{entry.confidence == null ? 'N/A' : toPercent(entry.confidence)}</td>
                    <td className="rounded-r-[22px] px-3 py-4 text-[var(--ink-muted)]">{formatDateTime(entry.observedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <div className="rounded-[22px] border border-dashed border-[var(--line-strong)] bg-[rgba(255,255,255,0.58)] p-6 text-sm text-[var(--ink-muted)]">
              {data ? 'The latest evidence bundle does not contain any observation rows yet.' : 'No evidence timeline available yet.'}
            </div>
          )}
        </CardContent>
      </Card>

      <section className="mt-5 grid gap-5 xl:grid-cols-[0.96fr_1.04fr]">
        <Card>
          <CardHeader>
            <Badge className="w-fit">Decision Audit</Badge>
            <CardTitle>Shadow decision history</CardTitle>
            <CardDescription>
              Every shadow replay attempt is append-only, so repeated holds and manual-review outcomes stay visible.
            </CardDescription>
          </CardHeader>
          <CardContent className="grid gap-3">
            {data && data.decisions.length === 0 ? (
              <div className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4 text-sm text-[var(--ink-muted)]">
                No decision audits are present yet.
              </div>
            ) : null}

            {data?.decisions.map((decision) => (
              <DecisionCard key={decision.id} decision={decision} />
            ))}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <Badge variant="accent" className="w-fit">
              Raw Bundle
            </Badge>
            <CardTitle>Persisted payload</CardTitle>
            <CardDescription>
              The raw persisted evidence payload stays visible here so we can compare the summarized UI to the stored contract.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <details className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4">
              <summary className="cursor-pointer list-none font-medium text-[var(--ink-strong)]">
                Open raw JSON payload
              </summary>
              <pre className="mt-4 overflow-x-auto rounded-[18px] bg-[var(--ink-strong)]/95 p-4 text-xs leading-6 text-[var(--canvas)]">
                {JSON.stringify(data?.evidence.payload ?? {}, null, 2)}
              </pre>
            </details>
          </CardContent>
        </Card>
      </section>
    </V3Shell>
  )
}

function ContradictionCard({ contradiction }: { contradiction: ScoreTruthContradiction }) {
  return (
    <div className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4">
      <div className="flex items-start justify-between gap-4">
        <div>
          <p className="font-medium text-[var(--ink-strong)]">{contradiction.kind.replaceAll('_', ' ')}</p>
          <p className="mt-1 text-sm text-[var(--ink-muted)]">
            Severity {toPercent(contradiction.severity)} · {contradiction.resolved ? 'Resolved' : 'Open'}
          </p>
        </div>
        <Badge className={contradiction.resolved ? '' : 'border-rose-200 bg-rose-50 text-rose-800'}>
          {contradiction.resolved ? 'Resolved' : 'Open'}
        </Badge>
      </div>
      <div className="mt-3 grid gap-2 text-sm text-[var(--ink-muted)]">
        <p>Observed: {formatDateTime(contradiction.observedAt)}</p>
        <p>
          Sources: {readPayloadText(contradiction.payload, 'left')} vs. {readPayloadText(contradiction.payload, 'right')}
        </p>
        {contradiction.resolutionNote ? <p>Resolution: {contradiction.resolutionNote}</p> : null}
      </div>
    </div>
  )
}

function DecisionCard({ decision }: { decision: ScoreTruthDecision }) {
  return (
    <div className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4">
      <div className="flex items-start justify-between gap-4">
        <div>
          <p className="font-medium text-[var(--ink-strong)]">{decision.decision.replaceAll('_', ' ')}</p>
          <p className="mt-1 text-sm text-[var(--ink-muted)]">{decision.reason.replaceAll('_', ' ')}</p>
        </div>
        <Badge variant="accent">{decision.confidence == null ? 'N/A' : toPercent(decision.confidence)}</Badge>
      </div>
      <div className="mt-3 grid gap-2 text-sm text-[var(--ink-muted)]">
        <p>Decided: {formatDateTime(decision.decidedAt)}</p>
        <p>Evidence: {decision.evidenceId == null ? 'None' : `#${decision.evidenceId}`}</p>
      </div>
    </div>
  )
}

function MetricTile({
  label,
  value,
  icon: Icon,
}: {
  label: string
  value: string
  icon: typeof FileSearch2
}) {
  return (
    <div className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4">
      <div className="flex items-center gap-3 text-[var(--ink-muted)]">
        <span className="inline-flex size-10 items-center justify-center rounded-2xl bg-[var(--panel-soft)] text-[var(--accent-ink)]">
          <Icon className="size-4" />
        </span>
        <p className="text-xs font-semibold uppercase tracking-[0.24em]">{label}</p>
      </div>
      <p className="mt-4 font-serif text-3xl font-semibold tracking-[-0.05em] text-[var(--ink-strong)]">{value}</p>
    </div>
  )
}

function StatCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4">
      <p className="text-xs font-semibold uppercase tracking-[0.24em] text-[var(--ink-muted)]">{label}</p>
      <p className="mt-2 font-serif text-2xl font-semibold tracking-[-0.04em] text-[var(--ink-strong)]">{value}</p>
    </div>
  )
}

function InlineAlert({ children }: { children: ReactNode }) {
  return (
    <div className="flex items-center gap-2 rounded-[18px] border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800">
      {children}
    </div>
  )
}

function summarizeObservationCounts(data: ScoreTruthEvidenceResponse | null) {
  const payload = asObject(data?.evidence.payload)
  return {
    live: arrayLength(payload?.liveObservations),
    mirror: arrayLength(payload?.mirrorObservations),
    stream: arrayLength(payload?.streamObservations),
    confirm: arrayLength(payload?.officialCandidates) + arrayLength(payload?.databaseCandidates),
  }
}

function buildTimeline(data: ScoreTruthEvidenceResponse | null): TimelineEntry[] {
  const payload = asObject(data?.evidence.payload)
  if (!payload) {
    return []
  }

  const entries = [
    ...toTimelineEntries(payload.liveObservations, 'Live'),
    ...toTimelineEntries(payload.mirrorObservations, 'Mirror'),
    ...toTimelineEntries(payload.streamObservations, 'Stream'),
    ...toTimelineEntries(payload.officialCandidates, 'Official'),
    ...toTimelineEntries(payload.databaseCandidates, 'Database'),
  ]

  return entries.sort((left, right) => right.observedAt.localeCompare(left.observedAt))
}

function toTimelineEntries(value: JsonValue | undefined, lane: string): TimelineEntry[] {
  if (!Array.isArray(value)) {
    return []
  }
  return value.flatMap((item, index) => {
    const entry = asObject(item)
    if (!entry) {
      return []
    }
    const score = asObject(entry.score)
    const summary = formatScoreSummary(score, entry.winnerPlayerId)
    const detail = formatScoreDetail(entry)
    return [{
      id: `${lane}-${readText(entry.source) || readText(entry.routeId) || readText(entry.bookerEventId) || index}`,
      lane,
      source: readText(entry.source) || readText(entry.routeId) || lane.toUpperCase(),
      observedAt: readText(entry.observedAt) || '',
      confidence: readNumber(entry.confidence),
      phase: readText(entry.phase) || readText(entry.matchPhase) || 'UNKNOWN',
      summary,
      detail,
    }]
  })
}

function formatScoreSummary(score: Record<string, JsonValue> | null, winner: JsonValue | undefined) {
  if (score) {
    const gamesP1 = readNumber(score.gamesP1)
    const gamesP2 = readNumber(score.gamesP2)
    const pointsP1 = readNumber(score.pointsP1)
    const pointsP2 = readNumber(score.pointsP2)
    if (gamesP1 != null || gamesP2 != null) {
      const games = `${gamesP1 ?? '?'}-${gamesP2 ?? '?'}`
      if (pointsP1 != null || pointsP2 != null) {
        return `${games} (${pointsP1 ?? '?'}-${pointsP2 ?? '?'})`
      }
      return games
    }
  }
  const winnerId = readNumber(winner)
  if (winnerId != null) {
    return `Winner player ${winnerId}`
  }
  return 'No score snapshot'
}

function formatScoreDetail(entry: Record<string, JsonValue>) {
  const detailParts = [
    readBoolean(entry.completionSignal) ? 'completion signal' : null,
    readBoolean(entry.displayed) === false ? 'hidden on board' : null,
    readBoolean(entry.resulted) ? 'resulted' : null,
    readText(entry.bookerEventId),
    readText(entry.routeId),
  ].filter(Boolean)
  return detailParts.length > 0 ? detailParts.join(' · ') : 'No extra detail'
}

function readPayloadText(payload: { [key: string]: JsonValue } | null, key: string) {
  if (!payload) {
    return 'Unknown'
  }
  const value = payload[key]
  if (typeof value === 'string') {
    return value
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value)
  }
  if (value && typeof value === 'object') {
    return JSON.stringify(value)
  }
  return 'Unknown'
}

function asObject(value: JsonValue | null | undefined): Record<string, JsonValue> | null {
  if (!value || Array.isArray(value) || typeof value !== 'object') {
    return null
  }
  return value as Record<string, JsonValue>
}

function arrayLength(value: JsonValue | undefined) {
  return Array.isArray(value) ? value.length : 0
}

function readText(value: JsonValue | undefined) {
  return typeof value === 'string' ? value : null
}

function readNumber(value: JsonValue | undefined) {
  return typeof value === 'number' ? value : null
}

function readBoolean(value: JsonValue | undefined) {
  return typeof value === 'boolean' ? value : null
}

function toPercent(value: number) {
  return `${(value * 100).toFixed(1)}%`
}

function formatDateTime(value: string | null) {
  if (!value) {
    return 'N/A'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(date)
}
