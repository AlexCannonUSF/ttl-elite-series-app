import { type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  Activity,
  AlertTriangle,
  BarChart3,
  Brain,
  Clock3,
  FileSearch2,
  GitCompareArrows,
  RefreshCcw,
  Shield,
  TrendingUp,
  type LucideIcon,
} from 'lucide-react'
import { Link, useLocation, useParams } from 'react-router-dom'

import { V3Shell } from '@/components/layout/V3Shell'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { fetchLiveBoard, fetchMatchTimeline, fetchMatchupAnalysis } from '@/features/live-studio/api'
import { BettorMatchupPanel } from '@/features/live-studio/BettorMatchupPanel'
import {
  calculateBookMargin,
  calculateModelPriceAtBookMargin,
  calculateNoVigMarketProbability,
  probabilityToAmericanOdds,
} from '@/features/live-studio/marketMath'
import type {
  LiveOddsRecommendation,
  MatchupAnalysis,
  TrackedMatchObservation,
} from '@/features/live-studio/types'
import { fetchMarketIntelligence } from '@/features/market/api'
import type { MarketIntelligence } from '@/features/market/types'
import { fetchPredictionPanel, parseMatchKey, type ParsedMatchKey } from '@/features/prediction/api'
import type {
  PredictionContribution,
  PredictionPanelResponse,
  ReliabilityBin,
} from '@/features/prediction/types'
import { fetchScoreTruthEvidence } from '@/features/score-truth/api'
import type {
  JsonValue,
  ScoreTruthContradiction,
  ScoreTruthDecision,
  ScoreTruthEvidenceResponse,
} from '@/features/score-truth/types'
import { cn } from '@/lib/utils'

type TabKey = 'evidence' | 'prediction' | 'history' | 'market'

type TabDefinition = {
  description: string
  icon: LucideIcon
  key: TabKey
  label: string
}

type LoadErrors = Partial<Record<TabKey, string>>

const REFRESH_INTERVAL_MS = 15000

const detailTabs: TabDefinition[] = [
  {
    description: 'Score-truth bundle, contradictions, and decisions.',
    icon: FileSearch2,
    key: 'evidence',
    label: 'Evidence',
  },
  {
    description: 'Calibrated probability, conformal state, and SHAP drivers.',
    icon: Brain,
    key: 'prediction',
    label: 'Prediction',
  },
  {
    description: 'Tracked observations and score continuity.',
    icon: Clock3,
    key: 'history',
    label: 'History',
  },
  {
    description: 'Current odds, model edge, book identity, and signal state.',
    icon: BarChart3,
    key: 'market',
    label: 'Market',
  },
]

const userDetailTabs: TabDefinition[] = [
  {
    description: 'The probability, confidence range, and strongest drivers.',
    icon: Brain,
    key: 'prediction',
    label: 'Why this lean',
  },
  {
    description: 'Live observations, score changes, and market continuity.',
    icon: Clock3,
    key: 'history',
    label: 'Live timeline',
  },
  {
    description: 'Hard Rock line, our fair price, value gap, and signal state.',
    icon: BarChart3,
    key: 'market',
    label: 'Price detail',
  },
  {
    description: 'How much trustworthy data supports the current read.',
    icon: Shield,
    key: 'evidence',
    label: 'Data confidence',
  },
]

export function MatchDetailRoute() {
  const location = useLocation()
  const { id, tab } = useParams()
  const userMode = location.pathname.startsWith('/user/')
  const detailBase = userMode ? '/user/matches' : '/matches'
  const visibleTabs = userMode ? userDetailTabs : detailTabs
  const matchId = useMemo(() => safeDecode(id), [id])
  const activeTab = toTabKey(tab)
  const [strategy, setStrategy] = useState<'CONSERVATIVE' | 'AGGRESSIVE'>('CONSERVATIVE')
  const [evidence, setEvidence] = useState<ScoreTruthEvidenceResponse | null>(null)
  const [prediction, setPrediction] = useState<PredictionPanelResponse | null>(null)
  const [matchupIntel, setMatchupIntel] = useState<MatchupAnalysis | null>(null)
  const [intelError, setIntelError] = useState<string | null>(null)
  const [marketRow, setMarketRow] = useState<LiveOddsRecommendation | null>(null)
  const [marketIntelligence, setMarketIntelligence] = useState<MarketIntelligence | null>(null)
  const [timeline, setTimeline] = useState<TrackedMatchObservation[]>([])
  const [errors, setErrors] = useState<LoadErrors>({})
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [loadedAt, setLoadedAt] = useState<string | null>(null)
  const mountedRef = useRef(true)

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  const loadDetail = useCallback(async (background: boolean) => {
    if (!matchId) {
      if (mountedRef.current) {
        setErrors({ evidence: 'Route is missing a match id.' })
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

    const nextErrors: LoadErrors = {}
    let nextMarketRow: LiveOddsRecommendation | null = null
    let nextMarketIntelligence: MarketIntelligence | null = null
    let nextEvidence: ScoreTruthEvidenceResponse | null = null
    let nextTimeline: TrackedMatchObservation[] = []
    let nextPrediction: PredictionPanelResponse | null = null
    let nextMatchupIntel: MatchupAnalysis | null = null
    let nextIntelError: string | null = null
    let boardRows: LiveOddsRecommendation[] = []

    try {
      boardRows = await fetchLiveBoard({ includeUnresolved: true, limit: 100, strategy })
      nextMarketRow = findMarketRow(matchId, boardRows)
    } catch (error) {
      nextErrors.market = error instanceof Error ? error.message : 'Unable to load the live market board.'
    }

    const evidenceCandidates = buildEvidenceCandidates(matchId, nextMarketRow)
    const evidenceResult = await fetchFirstEvidence(evidenceCandidates)
    nextEvidence = evidenceResult.data
    if (!nextEvidence && evidenceResult.error) {
      nextErrors.evidence = evidenceResult.error
    }

    if (!nextMarketRow && nextEvidence) {
      nextMarketRow = findMarketRow(nextEvidence.evidence.trackedEventId, boardRows)
    }

    const marketIdentity = nextMarketRow?.externalEventId
      ?? nextEvidence?.evidence.trackedEventId
      ?? nextMarketRow?.sourceFeedEventId
      ?? nextMarketRow?.matchupKey
    if (marketIdentity) {
      try {
        nextMarketIntelligence = await fetchMarketIntelligence(marketIdentity)
      } catch (error) {
        nextErrors.market = error instanceof Error ? error.message : 'Unable to load timestamped market intelligence.'
      }
    }

    const timelineKey = resolveTimelineKey(matchId, nextEvidence, nextMarketRow)
    if (timelineKey) {
      try {
        nextTimeline = await fetchMatchTimeline(timelineKey)
      } catch (error) {
        nextErrors.history = error instanceof Error ? error.message : 'Unable to load the match timeline.'
      }
    } else {
      nextErrors.history = 'No tracked event key is available for this match yet.'
    }

    const predictionIdentity = resolvePredictionIdentity(matchId, nextMarketRow, nextTimeline)
    if (predictionIdentity) {
      const [predictionResult, intelResult] = await Promise.allSettled([
        fetchPredictionPanel({
          player1Id: predictionIdentity.player1Id,
          player2Id: predictionIdentity.player2Id,
          asOfDate: predictionIdentity.asOfDate,
          topK: 6,
        }),
        fetchMatchupAnalysis(predictionIdentity.player1Id, predictionIdentity.player2Id),
      ])
      if (predictionResult.status === 'fulfilled') {
        nextPrediction = predictionResult.value
      } else {
        nextErrors.prediction = predictionResult.reason instanceof Error
          ? predictionResult.reason.message
          : 'Unable to load the prediction panel.'
      }
      if (intelResult.status === 'fulfilled') {
        nextMatchupIntel = intelResult.value
      } else {
        nextIntelError = intelResult.reason instanceof Error
          ? intelResult.reason.message
          : 'Unable to load matchup intelligence.'
      }
    } else {
      nextErrors.prediction = 'This match id does not expose player ids yet, so prediction cannot be resolved.'
      nextIntelError = 'This match does not expose a resolved player-id pair yet.'
    }

    if (!mountedRef.current) {
      return
    }
    setEvidence(nextEvidence)
    setPrediction(nextPrediction)
    setMatchupIntel(nextMatchupIntel)
    setIntelError(nextIntelError)
    setMarketRow(nextMarketRow)
    setMarketIntelligence(nextMarketIntelligence)
    setTimeline(nextTimeline)
    setErrors(nextErrors)
    setLoadedAt(new Date().toISOString())
    if (background) {
      setRefreshing(false)
    } else {
      setLoading(false)
    }
  }, [matchId, strategy])

  useEffect(() => {
    void loadDetail(false)
    const interval = window.setInterval(() => {
      void loadDetail(true)
    }, REFRESH_INTERVAL_MS)
    return () => window.clearInterval(interval)
  }, [loadDetail])

  const title = marketRow?.eventName
    ?? (matchupIntel ? `${matchupIntel.player1.fullName} vs. ${matchupIntel.player2.fullName}` : null)
    ?? evidence?.evidence.trackedEventId
    ?? fallbackMatchTitle(matchId)
  const subtitle = marketRow
    ? `${marketRow.competitionName} | ${formatStart(marketRow.startTimeIso)}`
    : evidence
      ? `Tracked event ${evidence.evidence.trackedEventId}`
      : 'Evidence, prediction, history, and market state in one v3 route.'
  const modelRead = currentModelRead(marketRow, prediction)

  return (
    <V3Shell
      eyebrow={userMode ? 'Sportsbook Intelligence' : 'TTLElite Series 3.0'}
      title={userMode ? 'Match Intelligence' : 'Match Detail'}
      description={subtitle}
      badges={
        <>
          <Badge variant="accent">{userMode ? 'Decision detail' : 'Match Detail'}</Badge>
          <Badge>{activeTab}</Badge>
        </>
      }
      actions={
        <>
          <Button
            aria-pressed={strategy === 'CONSERVATIVE'}
            variant={strategy === 'CONSERVATIVE' ? 'primary' : 'secondary'}
            onClick={() => setStrategy('CONSERVATIVE')}
          >
            Conservative
          </Button>
          <Button
            aria-pressed={strategy === 'AGGRESSIVE'}
            variant={strategy === 'AGGRESSIVE' ? 'primary' : 'secondary'}
            onClick={() => setStrategy('AGGRESSIVE')}
          >
            Aggressive
          </Button>
          <Button variant="ghost" asChild>
            <Link to="/user">Live Markets</Link>
          </Button>
          <Button variant="secondary" onClick={() => void loadDetail(true)} disabled={loading || refreshing}>
            <RefreshCcw className={cn('size-4', refreshing && 'animate-spin')} />
            Refresh
          </Button>
        </>
      }
    >
      <Card>
        <CardHeader>
          <Badge variant="accent" className="w-fit">
            Match Context
          </Badge>
          <CardTitle>{title}</CardTitle>
          <CardDescription>
            {loadedAt ? `Last refreshed ${formatDateTime(loadedAt)}.` : 'Loading the latest match context.'}
          </CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4">
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            <MetricTile
              icon={FileSearch2}
              label="Settlement evidence"
              value={evidence ? evidence.evidence.coverageState : 'Not opened'}
            />
            <MetricTile icon={Brain} label={modelRead.label} value={formatPct(modelRead.value)} />
            <MetricTile icon={Activity} label="Timeline points" value={String(timeline.length)} />
            <MetricTile icon={TrendingUp} label="Executable Edge" value={formatSignedPct(marketRow?.suggestedEdge)} />
          </div>

          <nav aria-label="Match detail tabs" className="grid gap-3 lg:grid-cols-4">
            {visibleTabs.map((item) => (
              <TabLink key={item.key} active={activeTab === item.key} detailBase={detailBase} matchId={matchId} tab={item} />
            ))}
          </nav>
        </CardContent>
      </Card>

      {marketRow ? (
        <div className="mt-5">
          <BettorMatchupPanel
            analysis={matchupIntel}
            intelError={intelError}
            intelLoading={loading && !matchupIntel}
            row={marketRow}
          />
        </div>
      ) : null}

      <div className="mt-5">
        {loading && !evidence && !prediction && !marketRow ? (
          <Placeholder label="Loading match detail..." />
        ) : null}
        {activeTab === 'evidence' ? <EvidenceTab data={evidence} error={errors.evidence} /> : null}
        {activeTab === 'prediction' ? <PredictionTab data={prediction} error={errors.prediction} /> : null}
        {activeTab === 'history' ? <HistoryTab data={timeline} error={errors.history} /> : null}
        {activeTab === 'market' ? <MarketTab row={marketRow} intelligence={marketIntelligence} error={errors.market} /> : null}
      </div>
    </V3Shell>
  )
}

function TabLink({
  active,
  detailBase,
  matchId,
  tab,
}: {
  active: boolean
  detailBase: string
  matchId: string
  tab: TabDefinition
}) {
  const Icon = tab.icon
  return (
    <Link
      aria-current={active ? 'page' : undefined}
      className={cn(
        'flex min-h-24 items-start gap-3 rounded-[20px] border p-4 transition-colors',
        active
          ? 'border-[var(--accent-soft)] bg-[var(--accent-fade)] text-[var(--accent-ink)]'
          : 'border-[var(--line)] bg-[rgba(255,255,255,0.72)] text-[var(--ink-muted)] hover:border-[var(--accent-soft)] hover:text-[var(--ink-strong)]',
      )}
      to={`${detailBase}/${encodeURIComponent(matchId)}/${tab.key}`}
    >
      <span className="inline-flex size-10 shrink-0 items-center justify-center rounded-2xl border border-[var(--line)] bg-[var(--panel)]">
        <Icon aria-hidden="true" className="size-4" />
      </span>
      <span>
        <span className="block font-semibold">{tab.label}</span>
        <span className="mt-1 block text-xs leading-5 text-[var(--ink-muted)]">{tab.description}</span>
      </span>
    </Link>
  )
}

function EvidenceTab({
  data,
  error,
}: {
  data: ScoreTruthEvidenceResponse | null
  error?: string
}) {
  const counts = useMemo(() => summarizeObservationCounts(data), [data])

  if (!data) {
    return <EmptyTab icon={FileSearch2} title="No evidence bundle" detail={error ?? 'No persisted score-truth bundle is attached yet.'} />
  }

  return (
    <section className="grid gap-5 xl:grid-cols-[1fr_0.9fr]">
      <Card>
        <CardHeader>
          <Badge variant="accent" className="w-fit">
            Evidence
          </Badge>
          <CardTitle>Score-truth bundle</CardTitle>
          <CardDescription>
            Coverage, ambiguity, confidence, and observation mix for the latest persisted snapshot.
          </CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4">
          {error ? <InlineAlert>{error}</InlineAlert> : null}
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            <MetricTile icon={Shield} label="Coverage" value={data.evidence.coverageState} />
            <MetricTile icon={AlertTriangle} label="Ambiguity" value={formatPct(data.evidence.ambiguityScore)} />
            <MetricTile icon={Clock3} label="Confidence" value={formatPct(data.evidence.confidence)} />
            <MetricTile icon={Activity} label="Rows" value={String(counts.total)} />
          </div>

          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            <SmallMetric label="Live" value={String(counts.live)} />
            <SmallMetric label="Mirror" value={String(counts.mirror)} />
            <SmallMetric label="Stream" value={String(counts.stream)} />
            <SmallMetric label="Confirm" value={String(counts.confirm)} />
          </div>

          <div className="grid gap-3 sm:grid-cols-2">
            <SmallMetric label="Evidence id" value={`#${data.evidence.evidenceId}`} />
            <SmallMetric label="Bet id" value={`#${data.evidence.betId}`} />
            <SmallMetric label="Tracked event" value={data.evidence.trackedEventId} />
            <SmallMetric label="Bundle as of" value={formatDateTime(data.evidence.bundleAsOf)} />
          </div>
        </CardContent>
      </Card>

      <div className="grid content-start gap-5">
        <Card>
          <CardHeader>
            <Badge className="w-fit">Contradictions</Badge>
            <CardTitle>Disagreement surface</CardTitle>
          </CardHeader>
          <CardContent className="grid gap-3">
            {data.contradictions.length === 0 ? <Placeholder label="No contradictions are attached to this bundle." /> : null}
            {data.contradictions.map((contradiction) => (
              <ContradictionCard key={contradiction.id} contradiction={contradiction} />
            ))}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <Badge className="w-fit">Decisions</Badge>
            <CardTitle>Settlement audit</CardTitle>
          </CardHeader>
          <CardContent className="grid gap-3">
            {data.decisions.length === 0 ? <Placeholder label="No decision audits are present yet." /> : null}
            {data.decisions.map((decision) => (
              <DecisionCard key={decision.id} decision={decision} />
            ))}
          </CardContent>
        </Card>
      </div>

      <Card className="xl:col-span-2">
        <CardHeader>
          <Badge variant="accent" className="w-fit">
            Raw Bundle
          </Badge>
          <CardTitle>Persisted payload</CardTitle>
        </CardHeader>
        <CardContent>
          <details className="rounded-[20px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4">
            <summary className="cursor-pointer list-none font-medium text-[var(--ink-strong)]">
              Open raw JSON payload
            </summary>
            <pre className="mt-4 max-h-[460px] overflow-auto rounded-[18px] bg-[var(--ink-strong)]/95 p-4 text-xs leading-6 text-[var(--canvas)]">
              {JSON.stringify(data.evidence.payload ?? {}, null, 2)}
            </pre>
          </details>
        </CardContent>
      </Card>
    </section>
  )
}

function PredictionTab({
  data,
  error,
}: {
  data: PredictionPanelResponse | null
  error?: string
}) {
  if (!data) {
    return <EmptyTab icon={Brain} title="No prediction panel" detail={error ?? 'No player-id pair is available for prediction yet.'} />
  }

  return (
    <section className="grid gap-5 xl:grid-cols-[1.05fr_0.95fr]">
      <Card>
        <CardHeader>
          <Badge variant="accent" className="w-fit">
            Probability
          </Badge>
          <CardTitle>Pre-match model with conformal interval</CardTitle>
          <CardDescription>
            This core matchup estimate excludes the live score. Use the live model above for the current in-match state.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {error ? <InlineAlert>{error}</InlineAlert> : null}
          <ProbabilityPanel panel={data} />
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <Badge className="w-fit">Conformal</Badge>
          <CardTitle>Uncertainty envelope</CardTitle>
        </CardHeader>
        <CardContent>
          <ConformalPanel panel={data} />
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <Badge variant="accent" className="w-fit">
            Drivers
          </Badge>
          <CardTitle>Top feature contributions</CardTitle>
        </CardHeader>
        <CardContent>
          {data.topContributions.length > 0 ? (
            <ShapBars contributions={data.topContributions} />
          ) : (
            <Placeholder label="No feature contributions returned for this matchup." />
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <Badge className="w-fit">Reliability</Badge>
          <CardTitle>Training calibration curve</CardTitle>
        </CardHeader>
        <CardContent>
          {data.reliabilityCurve.length > 0 ? (
            <ReliabilityCurve bins={data.reliabilityCurve} />
          ) : (
            <Placeholder label="No reliability curve is attached to the latest model run." />
          )}
        </CardContent>
      </Card>

      <Card className="xl:col-span-2">
        <CardHeader>
          <Badge variant="accent" className="w-fit">
            Trace
          </Badge>
          <CardTitle>Model identifiers</CardTitle>
        </CardHeader>
        <CardContent className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          <SmallMetric label="Model family" value={data.modelFamily} />
          <SmallMetric label="Model version" value={data.modelVersion} />
          <SmallMetric label="Calibration" value={data.calibrationMethod} />
          <SmallMetric label="Computed" value={formatDateTime(data.computedAtUtc)} />
        </CardContent>
      </Card>
    </section>
  )
}

function HistoryTab({
  data,
  error,
}: {
  data: TrackedMatchObservation[]
  error?: string
}) {
  const rows = [...data].sort((left, right) => (right.observedAt ?? '').localeCompare(left.observedAt ?? ''))

  if (rows.length === 0) {
    return <EmptyTab icon={Clock3} title="No tracked history" detail={error ?? 'No live-studio observations are recorded for this event key yet.'} />
  }

  return (
    <Card>
      <CardHeader>
        <Badge variant="accent" className="w-fit">
          History
        </Badge>
        <CardTitle>Tracked observations</CardTitle>
        <CardDescription>
          Live board, score feed, and after-close observations in newest-first order.
        </CardDescription>
      </CardHeader>
      <CardContent>
        {error ? <InlineAlert>{error}</InlineAlert> : null}
        <div className="overflow-x-auto">
          <table className="min-w-full border-separate border-spacing-y-3">
            <thead>
              <tr className="text-left text-xs uppercase tracking-[0.2em] text-[var(--ink-muted)]">
                <th className="px-3 pb-1 font-semibold">Observed</th>
                <th className="px-3 pb-1 font-semibold">Source</th>
                <th className="px-3 pb-1 font-semibold">Score</th>
                <th className="px-3 pb-1 font-semibold">State</th>
                <th className="px-3 pb-1 font-semibold">Identity</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row, index) => (
                <tr
                  key={`${row.id ?? row.observedAt ?? index}-${row.source ?? 'source'}`}
                  className="bg-[rgba(255,255,255,0.76)] text-sm text-[var(--ink)] shadow-[0_16px_42px_-36px_rgba(8,25,28,0.78)]"
                >
                  <td className="rounded-l-[20px] px-3 py-4 align-top text-[var(--ink-muted)]">
                    {formatDateTime(row.observedAt)}
                  </td>
                  <td className="px-3 py-4 align-top">
                    <p className="font-semibold text-[var(--ink-strong)]">{row.source ?? 'Unknown'}</p>
                    <p className="mt-1 text-xs text-[var(--ink-muted)]">{row.sourceKind ?? row.sourceFeedCode ?? 'SOURCE'}</p>
                  </td>
                  <td className="px-3 py-4 align-top">
                    <p className="font-semibold text-[var(--ink-strong)]">{row.liveScore ?? 'No score'}</p>
                    <p className="mt-1 text-xs text-[var(--ink-muted)]">{row.scoreDetail ?? row.matchPhase ?? 'No detail'}</p>
                  </td>
                  <td className="px-3 py-4 align-top">
                    <div className="flex flex-wrap gap-2">
                      <Badge variant={row.live ? 'accent' : 'neutral'}>{row.live ? 'Live' : 'Queued'}</Badge>
                      {row.matchCompleted ? <Badge variant="accent">Complete</Badge> : null}
                      {row.trackedAfterClose ? <Badge>After close</Badge> : null}
                    </div>
                  </td>
                  <td className="rounded-r-[20px] px-3 py-4 align-top text-[var(--ink-muted)]">
                    <p>{row.eventKey}</p>
                    <p className="mt-1 text-xs">{row.sourceFeedEventId ?? row.externalEventId ?? 'No external id'}</p>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </CardContent>
    </Card>
  )
}

function MarketTab({
  error,
  intelligence,
  row,
}: {
  error?: string
  intelligence: MarketIntelligence | null
  row: LiveOddsRecommendation | null
}) {
  if (!row) {
    return <EmptyTab icon={BarChart3} title="No market row" detail={error ?? 'This match is not on the current live board snapshot.'} />
  }

  const bookMargin = calculateBookMargin(row.decimalOddsPlayer1, row.decimalOddsPlayer2)

  return (
    <section className="grid gap-5 xl:grid-cols-[1.05fr_0.95fr]">
      <Card>
        <CardHeader>
          <Badge variant={row.recommended ? 'accent' : 'neutral'} className="w-fit">
            {row.recommended ? 'Recommended' : row.grade || 'Watching'}
          </Badge>
          <CardTitle>{row.eventName}</CardTitle>
          <CardDescription>{row.rationale || 'No market rationale returned for this row yet.'}</CardDescription>
          <CardDescription>
            Our fair price is no-vig. “Our @ HR hold” uses model probability × (1 + current Hard Rock hold) for a proportional retail-price comparison; executable edge always uses the actual offered price.
          </CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4">
          {error ? <InlineAlert>{error}</InlineAlert> : null}
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-5">
            <MetricTile icon={Activity} label="Phase" value={row.matchPhase ?? (row.live ? 'LIVE' : 'UPCOMING')} />
            <MetricTile icon={TrendingUp} label="Executable Edge" value={formatSignedPct(row.suggestedEdge)} />
            <MetricTile icon={GitCompareArrows} label="Hard Rock Margin" value={formatPct(bookMargin)} />
            <MetricTile icon={Shield} label="Reliability" value={formatPct(row.overallReliability)} />
            <MetricTile icon={GitCompareArrows} label="Trigger" value={row.topTrigger ?? 'N/A'} />
          </div>

          <div className="overflow-x-auto">
            <table className="min-w-full border-separate border-spacing-y-3">
              <thead>
                <tr className="text-left text-xs uppercase tracking-[0.2em] text-[var(--ink-muted)]">
                  <th className="px-3 pb-1 font-semibold">Side</th>
                  <th className="px-3 pb-1 text-right font-semibold">HR offered</th>
                  <th className="px-3 pb-1 text-right font-semibold">HR no-vig</th>
                  <th className="px-3 pb-1 text-right font-semibold">Offered BE</th>
                  <th className="px-3 pb-1 text-right font-semibold">Our model</th>
                  <th className="px-3 pb-1 text-right font-semibold">Bet edge</th>
                  <th className="px-3 pb-1 text-right font-semibold">Our fair · 0%</th>
                  <th className="px-3 pb-1 text-right font-semibold">Our @ HR hold</th>
                </tr>
              </thead>
              <tbody>
                <MarketSideRow
                  edge={row.edgePlayer1}
                  fairOdds={row.modelFairAmericanOddsPlayer1}
                  implied={row.impliedProbabilityPlayer1}
                  margin={bookMargin}
                  model={row.modelProbabilityPlayer1}
                  odds={row.americanOddsPlayer1}
                  selected={row.suggestedSide === row.player1Name}
                  side={row.player1Name}
                />
                <MarketSideRow
                  edge={row.edgePlayer2}
                  fairOdds={row.modelFairAmericanOddsPlayer2}
                  implied={row.impliedProbabilityPlayer2}
                  margin={bookMargin}
                  model={row.modelProbabilityPlayer2}
                  odds={row.americanOddsPlayer2}
                  selected={row.suggestedSide === row.player2Name}
                  side={row.player2Name}
                />
              </tbody>
            </table>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <Badge className="w-fit">Market Identity</Badge>
          <CardTitle>Source and lock fields</CardTitle>
        </CardHeader>
        <CardContent className="grid gap-3">
          <SmallMetric label="Competition" value={row.competitionName} />
          <SmallMetric label="Start" value={formatStart(row.startTimeIso)} />
          <SmallMetric label="Source" value={row.sourceType ?? row.source} />
          <SmallMetric label="Feed event" value={row.sourceFeedEventId ?? row.externalEventId ?? 'N/A'} />
          <SmallMetric label="Event key" value={buildEventKey(row)} />
          <SmallMetric label="Dedupe key" value={row.suggestedDedupeKey ?? 'N/A'} />
          <SmallMetric label="Confidence range" value={`${formatPct(row.confidenceLow)} - ${formatPct(row.confidenceHigh)}`} />
          <SmallMetric label="Score" value={row.liveScore ?? row.scoreDetail ?? 'N/A'} />
        </CardContent>
      </Card>
      <MarketIntelligencePanel intelligence={intelligence} row={row} />
    </section>
  )
}

function MarketIntelligencePanel({ intelligence, row }: { intelligence: MarketIntelligence | null; row: LiveOddsRecommendation }) {
  if (!intelligence) return <Card className="xl:col-span-2"><CardContent className="py-10 text-center text-sm text-[var(--ink-muted)]">No persisted market history is linked to this event yet.</CardContent></Card>
  return <Card className="xl:col-span-2"><CardHeader><div className="flex flex-wrap items-center justify-between gap-3"><Badge variant={intelligence.executionAvailable ? 'accent' : 'neutral'} className="w-fit">{intelligence.executionAvailable ? 'Hard Rock executable' : 'Reference only'}</Badge><span className="text-xs text-[var(--ink-muted)]">Freshest {formatAge(intelligence.freshestQuoteAgeSeconds)}</span></div><CardTitle>Odds ladder and consensus</CardTitle><CardDescription>Hard Rock is the executable Florida price. Other authorized books are timestamped references only; consensus never replaces the price a wager could actually receive.</CardDescription></CardHeader><CardContent className="space-y-4"><div className="grid gap-3 sm:grid-cols-3"><SmallMetric label="Consensus sources" value={String(intelligence.consensusSourceCount)} /><SmallMetric label={`${row.player1Name} consensus`} value={formatPct(intelligence.consensusPlayer1Probability)} /><SmallMetric label="Dispersion" value={intelligence.consensusDispersionPctPoints == null ? 'Needs 2 books' : `${intelligence.consensusDispersionPctPoints.toFixed(2)} pp`} /></div><div className="overflow-x-auto rounded-2xl border border-[var(--line)]"><table className="w-full min-w-[760px] text-left text-xs"><thead className="bg-slate-100/80 text-[10px] uppercase tracking-[0.14em] text-[var(--ink-muted)]"><tr><th className="px-3 py-3">Book</th><th className="px-3 py-3">Role</th><th className="px-3 py-3">State</th><th className="px-3 py-3 text-right">{row.player1Name}</th><th className="px-3 py-3 text-right">{row.player2Name}</th><th className="px-3 py-3 text-right">No-vig view</th><th className="px-3 py-3 text-right">Hold</th><th className="px-3 py-3 text-right">Age</th></tr></thead><tbody>{intelligence.books.map((book) => <tr className="border-t border-[var(--line)] bg-white/60" key={book.sourceCode}><td className="px-3 py-3 font-bold">{book.displayName}</td><td className="px-3 py-3"><Badge variant={book.executable ? 'accent' : 'neutral'}>{book.executable ? 'Executable' : 'Reference'}</Badge></td><td className="px-3 py-3">{book.marketState}{book.stale ? ' · stale' : ''}</td><td className="px-3 py-3 text-right font-mono">{formatAmerican(book.player1AmericanOdds)}</td><td className="px-3 py-3 text-right font-mono">{formatAmerican(book.player2AmericanOdds)}</td><td className="px-3 py-3 text-right font-mono">{formatPct(book.player1NoVigProbability)}</td><td className="px-3 py-3 text-right font-mono">{book.overroundPct == null ? '—' : `${book.overroundPct.toFixed(2)}%`}</td><td className="px-3 py-3 text-right">{formatAge(book.ageSeconds)}</td></tr>)}</tbody></table></div>{intelligence.warnings.map((warning) => <p className="flex gap-2 text-xs leading-5 text-amber-900" key={warning}><AlertTriangle className="mt-0.5 size-3 shrink-0" />{warning}</p>)}</CardContent></Card>
}

function MarketSideRow({
  edge,
  fairOdds,
  implied,
  margin,
  model,
  odds,
  selected,
  side,
}: {
  edge: number | null
  fairOdds: number | null
  implied: number
  margin: number | null
  model: number | null
  odds: number
  selected: boolean
  side: string
}) {
  const noVigMarketProbability = calculateNoVigMarketProbability(implied, margin)
  const noVigMarketOdds = probabilityToAmericanOdds(noVigMarketProbability)
  const modelAtBookMargin = calculateModelPriceAtBookMargin(model, margin)

  return (
    <tr className={cn('bg-[rgba(255,255,255,0.76)] text-sm', selected && 'bg-[rgba(236,253,245,0.9)]')}>
      <td className="rounded-l-[18px] px-3 py-4 font-semibold text-[var(--ink-strong)]">
        <div className="flex flex-wrap items-center gap-2">
          <span>{side}</span>
          {selected ? <Badge variant="accent">Pick</Badge> : null}
        </div>
      </td>
      <td className="px-3 py-4 text-right font-mono">{formatAmerican(odds)}</td>
      <td className="px-3 py-4 text-right font-mono">{formatAmerican(noVigMarketOdds)}</td>
      <td className="px-3 py-4 text-right">{formatPct(implied)}</td>
      <td className="px-3 py-4 text-right">{formatPct(model)}</td>
      <td className={cn('px-3 py-4 text-right font-mono', (edge ?? 0) >= 0 ? 'text-emerald-700' : 'text-rose-700')}>
        {formatSignedPct(edge)}
      </td>
      <td className="px-3 py-4 text-right font-mono">{formatAmerican(fairOdds)}</td>
      <td className="rounded-r-[18px] px-3 py-4 text-right font-mono">{formatAmerican(modelAtBookMargin)}</td>
    </tr>
  )
}

function ProbabilityPanel({ panel }: { panel: PredictionPanelResponse }) {
  return (
    <div className="rounded-[20px] border border-[var(--line)] bg-[rgba(255,255,255,0.76)] p-5">
      <div className="flex flex-wrap items-end gap-3">
        <Brain className="mb-2 size-5 text-[var(--accent-ink)]" />
        <p className="font-serif text-5xl font-semibold tracking-[-0.05em] text-[var(--ink-strong)]">
          {formatPct(panel.pTop.value)}
        </p>
        <p className="mb-2 text-sm text-[var(--ink-muted)]">Pre-match p_top | p_bot {formatPct(panel.pBot.value)}</p>
      </div>
      <IntervalBar legend="Model interval" low={panel.pTop.intervalLow} high={panel.pTop.intervalHigh} point={panel.pTop.value} />
      <IntervalBar legend={`Conformal ${panel.conformal.method}`} low={panel.conformal.intervalLow} high={panel.conformal.intervalHigh} point={panel.pTop.value} />
    </div>
  )
}

function IntervalBar({
  high,
  legend,
  low,
  point,
}: {
  high: number
  legend: string
  low: number
  point: number
}) {
  const safeLow = clamp01(low)
  const safeHigh = clamp01(high)
  const left = `${safeLow * 100}%`
  const width = `${Math.max(0, safeHigh - safeLow) * 100}%`
  const marker = `${clamp01(point) * 100}%`

  return (
    <div className="mt-5">
      <div className="flex items-center justify-between gap-3 text-xs uppercase tracking-[0.2em] text-[var(--ink-muted)]">
        <span>{legend}</span>
        <span>{formatPct(safeLow)} - {formatPct(safeHigh)}</span>
      </div>
      <div className="relative mt-2 h-3 rounded-full bg-[rgba(15,23,42,0.06)]">
        <div className="absolute inset-y-0 rounded-full bg-[var(--accent-fade)]" style={{ left, width }} />
        <div className="absolute inset-y-[-2px] w-[2px] rounded-full bg-[var(--accent-ink)]" style={{ left: marker }} />
      </div>
    </div>
  )
}

function ConformalPanel({ panel }: { panel: PredictionPanelResponse }) {
  const conformal = panel.conformal
  return (
    <div className="grid gap-3 sm:grid-cols-2">
      <SmallMetric label="Label" value={conformal.label.replaceAll('_', ' ')} />
      <SmallMetric label="Coverage" value={formatPct(conformal.coverage)} />
      <SmallMetric label="Alpha" value={conformal.alpha.toFixed(2)} />
      <SmallMetric label="Quantile qhat" value={conformal.quantile.toFixed(3)} />
      <SmallMetric label="Group" value={conformal.groupKey || 'N/A'} />
      <SmallMetric label="Prediction set" value={(conformal.predictionSet ?? []).join(', ') || 'Empty'} />
    </div>
  )
}

function ShapBars({ contributions }: { contributions: PredictionContribution[] }) {
  const max = Math.max(0.01, ...contributions.map((contribution) => Math.abs(contribution.contribution)))
  return (
    <ul className="grid gap-3">
      {contributions.map((contribution) => {
        const ratio = Math.min(1, Math.abs(contribution.contribution) / max)
        const positive = contribution.contribution >= 0
        return (
          <li key={contribution.feature} className="rounded-[18px] border border-[var(--line)] bg-[rgba(255,255,255,0.76)] p-4">
            <div className="flex items-center justify-between gap-3 text-sm">
              <span className="truncate font-semibold text-[var(--ink-strong)]">{contribution.feature}</span>
              <span className={positive ? 'text-emerald-700' : 'text-rose-700'}>
                {(positive ? '+' : '') + contribution.contribution.toFixed(3)}
              </span>
            </div>
            <div className="relative mt-2 h-3 rounded-full bg-[rgba(15,23,42,0.06)]">
              <div
                className={cn('absolute inset-y-0 rounded-full', positive ? 'left-1/2 bg-emerald-400/60' : 'right-1/2 bg-rose-400/60')}
                style={{ width: `${ratio * 50}%` }}
              />
              <div className="absolute inset-y-[-2px] left-1/2 w-[2px] rounded-full bg-[var(--ink-muted)]/40" />
            </div>
          </li>
        )
      })}
    </ul>
  )
}

function ReliabilityCurve({ bins }: { bins: ReliabilityBin[] }) {
  const width = 320
  const height = 220
  const pad = 28
  const xScale = (value: number) => pad + clamp01(value) * (width - 2 * pad)
  const yScale = (value: number) => height - pad - clamp01(value) * (height - 2 * pad)
  const total = bins.reduce((sum, bin) => sum + bin.count, 0) || 1
  const points = bins.map((bin) => ({
    bin,
    x: xScale(bin.meanPredicted),
    y: yScale(bin.observedRate),
  }))
  const path = points.map((point, index) => `${index === 0 ? 'M' : 'L'}${point.x.toFixed(1)},${point.y.toFixed(1)}`).join(' ')

  return (
    <svg viewBox={`0 0 ${width} ${height}`} className="w-full max-w-md text-[var(--ink-muted)]">
      <line x1={pad} y1={height - pad} x2={width - pad} y2={pad} stroke="currentColor" strokeDasharray="4 4" strokeWidth={1} />
      <line x1={pad} y1={height - pad} x2={width - pad} y2={height - pad} stroke="currentColor" strokeWidth={1} />
      <line x1={pad} y1={pad} x2={pad} y2={height - pad} stroke="currentColor" strokeWidth={1} />
      {points.length > 1 ? <path d={path} fill="none" stroke="rgb(37, 99, 235)" strokeWidth={2} /> : null}
      {points.map((point, index) => (
        <circle
          key={`${point.bin.lowerBound}-${index}`}
          cx={point.x}
          cy={point.y}
          fill="rgba(37, 99, 235, 0.65)"
          r={3 + (point.bin.count / total) * 8}
        />
      ))}
    </svg>
  )
}

function ContradictionCard({ contradiction }: { contradiction: ScoreTruthContradiction }) {
  return (
    <div className="rounded-[18px] border border-[var(--line)] bg-[rgba(255,255,255,0.76)] p-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="font-semibold text-[var(--ink-strong)]">{contradiction.kind.replaceAll('_', ' ')}</p>
          <p className="mt-1 text-sm text-[var(--ink-muted)]">Observed {formatDateTime(contradiction.observedAt)}</p>
        </div>
        <Badge variant={contradiction.resolved ? 'neutral' : 'accent'}>{contradiction.resolved ? 'Resolved' : 'Open'}</Badge>
      </div>
      <p className="mt-3 text-sm text-[var(--ink-muted)]">Severity {formatPct(contradiction.severity)}</p>
    </div>
  )
}

function DecisionCard({ decision }: { decision: ScoreTruthDecision }) {
  return (
    <div className="rounded-[18px] border border-[var(--line)] bg-[rgba(255,255,255,0.76)] p-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="font-semibold text-[var(--ink-strong)]">{decision.decision.replaceAll('_', ' ')}</p>
          <p className="mt-1 text-sm text-[var(--ink-muted)]">{decision.reason.replaceAll('_', ' ')}</p>
        </div>
        <Badge variant="accent">{formatPct(decision.confidence)}</Badge>
      </div>
      <p className="mt-3 text-sm text-[var(--ink-muted)]">Decided {formatDateTime(decision.decidedAt)}</p>
    </div>
  )
}

function EmptyTab({
  detail,
  icon: Icon,
  title,
}: {
  detail: string
  icon: LucideIcon
  title: string
}) {
  return (
    <Card>
      <CardContent className="flex min-h-64 flex-col items-start justify-center gap-4 p-8">
        <span className="inline-flex size-12 items-center justify-center rounded-2xl bg-[var(--panel-soft)] text-[var(--accent-ink)]">
          <Icon className="size-5" />
        </span>
        <div>
          <h2 className="font-serif text-3xl font-semibold tracking-[-0.04em] text-[var(--ink-strong)]">{title}</h2>
          <p className="mt-2 max-w-2xl text-sm leading-6 text-[var(--ink-muted)]">{detail}</p>
        </div>
      </CardContent>
    </Card>
  )
}

function MetricTile({
  icon: Icon,
  label,
  value,
}: {
  icon: LucideIcon
  label: string
  value: string
}) {
  return (
    <div className="rounded-[18px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4">
      <div className="flex items-center gap-3">
        <span className="inline-flex size-10 items-center justify-center rounded-2xl bg-[var(--panel-soft)] text-[var(--accent-ink)]">
          <Icon className="size-4" />
        </span>
        <div className="min-w-0">
          <p className="text-xs font-semibold uppercase tracking-[0.2em] text-[var(--ink-muted)]">{label}</p>
          <p className="mt-1 truncate font-serif text-2xl font-semibold tracking-[-0.04em] text-[var(--ink-strong)]">{value}</p>
        </div>
      </div>
    </div>
  )
}

function SmallMetric({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0 rounded-[18px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-3">
      <p className="text-[11px] font-semibold uppercase tracking-[0.2em] text-[var(--ink-muted)]">{label}</p>
      <p className="mt-1 truncate text-sm font-semibold text-[var(--ink-strong)]" title={value}>{value}</p>
    </div>
  )
}

function Placeholder({ label }: { label: string }) {
  return (
    <div className="rounded-[18px] border border-dashed border-[var(--line-strong)] bg-[rgba(255,255,255,0.58)] p-5 text-sm text-[var(--ink-muted)]">
      {label}
    </div>
  )
}

function InlineAlert({ children }: { children: ReactNode }) {
  return (
    <div className="flex items-center gap-2 rounded-[18px] border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800" role="alert">
      <AlertTriangle aria-hidden="true" className="size-4" />
      <span>{children}</span>
    </div>
  )
}

function fetchFirstEvidence(candidates: string[]): Promise<{ data: ScoreTruthEvidenceResponse | null; error?: string }> {
  return candidates.reduce<Promise<{ data: ScoreTruthEvidenceResponse | null; error?: string }>>(
    async (previous, candidate) => {
      const result = await previous
      if (result.data) {
        return result
      }
      try {
        return { data: await fetchScoreTruthEvidence(candidate) }
      } catch (error) {
        return {
          data: null,
          error: error instanceof Error ? error.message : 'Unable to load score-truth evidence.',
        }
      }
    },
    Promise.resolve({ data: null }),
  )
}

function buildEvidenceCandidates(matchId: string, row: LiveOddsRecommendation | null) {
  return uniqueStrings([
    matchId,
    row?.matchupKey,
    stripDedupeSide(row?.suggestedDedupeKey),
    row?.suggestedDedupeKey,
    row ? buildEventKey(row) : null,
    row?.externalEventId,
  ])
}

function resolveTimelineKey(
  matchId: string,
  evidence: ScoreTruthEvidenceResponse | null,
  row: LiveOddsRecommendation | null,
) {
  if (evidence?.evidence.trackedEventId) {
    return evidence.evidence.trackedEventId
  }
  if (row?.matchupKey) {
    return row.matchupKey
  }
  if (row?.suggestedDedupeKey) {
    return stripDedupeSide(row.suggestedDedupeKey) ?? row.suggestedDedupeKey
  }
  if (matchId.includes('|')) {
    return matchId
  }
  if (parseMatchKey(matchId)) {
    return null
  }
  return matchId
}

function resolvePredictionIdentity(
  matchId: string,
  row: LiveOddsRecommendation | null,
  timeline: TrackedMatchObservation[],
): ParsedMatchKey | null {
  const parsed = parseMatchKey(matchId)
  if (parsed) {
    return parsed
  }
  if (row?.player1Id && row.player2Id) {
    return {
      player1Id: row.player1Id,
      player2Id: row.player2Id,
    }
  }
  const observation = timeline.find((item) => item.player1Id && item.player2Id)
  if (observation?.player1Id && observation.player2Id) {
    return {
      player1Id: observation.player1Id,
      player2Id: observation.player2Id,
    }
  }
  return null
}

function findMarketRow(matchId: string, rows: LiveOddsRecommendation[]) {
  const target = normalizeIdentity(matchId)
  if (!target) {
    return null
  }
  return rows.find((row) => rowIdentityCandidates(row).some((candidate) => normalizeIdentity(candidate) === target)) ?? null
}

function rowIdentityCandidates(row: LiveOddsRecommendation) {
  const ids = row.player1Id && row.player2Id
    ? [`${row.player1Id}-${row.player2Id}`, `${row.player2Id}-${row.player1Id}`]
    : []
  return uniqueStrings([
    row.matchupKey,
    row.suggestedDedupeKey,
    stripDedupeSide(row.suggestedDedupeKey),
    row.externalEventId,
    row.sourceFeedEventId,
    buildEventKey(row),
    ...ids,
  ])
}

function summarizeObservationCounts(data: ScoreTruthEvidenceResponse | null) {
  const payload = asObject(data?.evidence.payload)
  const live = arrayLength(payload?.liveObservations)
  const mirror = arrayLength(payload?.mirrorObservations)
  const stream = arrayLength(payload?.streamObservations)
  const confirm = arrayLength(payload?.officialCandidates) + arrayLength(payload?.databaseCandidates)
  return {
    confirm,
    live,
    mirror,
    stream,
    total: live + mirror + stream + confirm,
  }
}

function buildEventKey(row: LiveOddsRecommendation) {
  const startBucket = row.startTimeIso?.trim() || new Date().toISOString().slice(0, 10)
  return [
    normalizeKey(row.competitionName),
    normalizeKey(row.eventName),
    normalizeKey(row.player1Name),
    normalizeKey(row.player2Name),
    normalizeKey(startBucket),
  ].join('|')
}

function stripDedupeSide(value: string | null | undefined) {
  if (!value) {
    return null
  }
  const parts = value.split('|').filter(Boolean)
  if (parts.length <= 1) {
    return value
  }
  return parts.slice(0, -1).join('|')
}

function uniqueStrings(values: Array<string | null | undefined>) {
  const seen = new Set<string>()
  const result: string[] = []
  for (const value of values) {
    const trimmed = value?.trim()
    if (!trimmed || seen.has(trimmed)) {
      continue
    }
    seen.add(trimmed)
    result.push(trimmed)
  }
  return result
}

function safeDecode(value: string | undefined) {
  if (!value) {
    return ''
  }
  try {
    return decodeURIComponent(value)
  } catch {
    return value
  }
}

function fallbackMatchTitle(matchId: string) {
  const parsed = parseMatchKey(matchId)
  return parsed ? `Player ${parsed.player1Id} vs. Player ${parsed.player2Id}` : matchId ? `Match ${matchId}` : 'Match Detail'
}

function toTabKey(value: string | undefined): TabKey {
  if (value === 'prediction' || value === 'history' || value === 'market') {
    return value
  }
  return 'evidence'
}

function normalizeIdentity(value: string | null | undefined) {
  return value?.trim().toLowerCase() ?? ''
}

function normalizeKey(value: string | null | undefined) {
  const normalized = value?.trim().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')
  return normalized || 'na'
}

function currentModelRead(
  row: LiveOddsRecommendation | null,
  prediction: PredictionPanelResponse | null,
) {
  if (row) {
    if (row.suggestedSide === row.player1Name) {
      return { label: `Live model · ${row.player1Name}`, value: row.modelProbabilityPlayer1 }
    }
    if (row.suggestedSide === row.player2Name) {
      return { label: `Live model · ${row.player2Name}`, value: row.modelProbabilityPlayer2 }
    }
  }
  return { label: 'Pregame model · p_top', value: prediction?.pTop.value ?? null }
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

function formatAmerican(value: number | null | undefined) {
  if (value == null || !Number.isFinite(value)) {
    return 'N/A'
  }
  return value > 0 ? `+${value}` : String(value)
}

function formatAge(seconds: number | null | undefined) {
  if (seconds == null || seconds < 0 || !Number.isFinite(seconds)) return 'Unknown'
  if (seconds < 60) return `${Math.round(seconds)}s ago`
  if (seconds < 3600) return `${Math.round(seconds / 60)}m ago`
  return `${Math.round(seconds / 3600)}h ago`
}

function formatPct(value: number | null | undefined) {
  if (value == null || !Number.isFinite(value)) {
    return 'N/A'
  }
  return `${(value * 100).toFixed(1)}%`
}

function formatSignedPct(value: number | null | undefined) {
  if (value == null || !Number.isFinite(value)) {
    return 'N/A'
  }
  const rawPct = value * 100
  const pct = Math.abs(rawPct) < 0.005 ? 0 : rawPct
  return `${pct >= 0 ? '+' : ''}${pct.toFixed(2)}%`
}

function formatDateTime(value: string | null | undefined) {
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

function formatStart(value: string | null) {
  if (!value) {
    return 'Time N/A'
  }
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) {
    return value
  }
  return new Intl.DateTimeFormat('en-US', {
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    month: 'short',
  }).format(parsed)
}

function clamp01(value: number) {
  if (!Number.isFinite(value)) {
    return 0
  }
  if (value < 0) {
    return 0
  }
  if (value > 1) {
    return 1
  }
  return value
}
