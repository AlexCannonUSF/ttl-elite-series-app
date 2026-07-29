import {
  Activity,
  ArrowRight,
  CircleDollarSign,
  Gauge,
  LineChart,
  ShieldCheck,
  Star,
  TrendingDown,
  TrendingUp,
} from 'lucide-react'
import { Link } from 'react-router-dom'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import type {
  LiveBoardHistoryPoint,
  LiveOddsRecommendation,
  MatchupAnalysis,
  MatchupForm,
  PaperTradeBet,
} from '@/features/live-studio/types'
import { cn } from '@/lib/utils'

export function BettorMatchupPanel({
  analysis,
  bet = null,
  detailHref,
  history = [],
  intelError,
  intelLoading = false,
  row,
}: {
  analysis: MatchupAnalysis | null
  bet?: PaperTradeBet | null
  detailHref?: string
  history?: LiveBoardHistoryPoint[]
  intelError?: string | null
  intelLoading?: boolean
  row: LiveOddsRecommendation
}) {
  const p1Selected = row.suggestedSide === row.player1Name
  const p2Selected = row.suggestedSide === row.player2Name
  const firstPrice = history[0]
  const lastPrice = history.at(-1)

  return (
    <Card className={cn('overflow-hidden', row.live && 'border-rose-200')}>
      <CardHeader className="border-b border-[var(--line)] bg-[rgba(255,255,255,0.46)]">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap items-center gap-2">
            <StatusBadge live={row.live} />
            {bet ? (
              <Badge className="border-amber-300 bg-amber-100 text-amber-900">
                <Star aria-hidden="true" className="size-3" />
                Your position
              </Badge>
            ) : null}
            <Badge variant={row.recommended ? 'accent' : 'neutral'}>
              {row.recommended ? row.grade || 'Recommended' : 'Watching'}
            </Badge>
          </div>
          <span className="text-xs font-semibold uppercase tracking-[0.18em] text-[var(--ink-muted)]">
            {formatStart(row.startTimeIso)}
          </span>
        </div>
        <CardTitle>{row.eventName}</CardTitle>
        <CardDescription>{row.competitionName}</CardDescription>
        {row.liveScore ? (
          <div
            aria-live="polite"
            className="mt-1 flex items-center justify-between gap-4 rounded-[18px] border border-rose-200 bg-rose-50 px-4 py-3"
          >
            <span className="inline-flex items-center gap-2 text-sm font-semibold uppercase tracking-[0.14em] text-rose-700">
              <Activity aria-hidden="true" className="size-4" />
              {formatPhase(row.matchPhase ?? 'Live')}
            </span>
            <span className="font-mono text-2xl font-bold tracking-[-0.04em] text-rose-900">{row.liveScore}</span>
          </div>
        ) : null}
      </CardHeader>

      <CardContent className="grid gap-5 pt-5">
        {bet ? <PositionStrip bet={bet} row={row} /> : null}

        <section aria-labelledby="price-board-heading">
          <div className="flex items-end justify-between gap-3">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-[0.2em] text-[var(--ink-muted)]" id="price-board-heading">
                Price board
              </p>
              <h3 className="mt-1 text-lg font-semibold text-[var(--ink-strong)]">Hard Rock vs. TTLElite fair</h3>
            </div>
            <span className="text-right text-xs text-[var(--ink-muted)]">Book line is live<br />Fair line is our model</span>
          </div>

          <div className="mt-3 grid gap-3">
            <MarketSide
              bookOdds={row.americanOddsPlayer1}
              edge={row.edgePlayer1}
              fairOdds={row.modelFairAmericanOddsPlayer1}
              implied={row.impliedProbabilityPlayer1}
              model={row.modelProbabilityPlayer1}
              movement={decimalMovement(firstPrice?.player1Odds, lastPrice?.player1Odds)}
              name={row.player1Name}
              selected={p1Selected}
            />
            <MarketSide
              bookOdds={row.americanOddsPlayer2}
              edge={row.edgePlayer2}
              fairOdds={row.modelFairAmericanOddsPlayer2}
              implied={row.impliedProbabilityPlayer2}
              model={row.modelProbabilityPlayer2}
              movement={decimalMovement(firstPrice?.player2Odds, lastPrice?.player2Odds)}
              name={row.player2Name}
              selected={p2Selected}
            />
          </div>
        </section>

        <OddsFlow history={history} row={row} />

        <section aria-labelledby="confidence-heading">
          <div className="flex items-end justify-between gap-3">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-[0.2em] text-[var(--ink-muted)]" id="confidence-heading">
                Matchup confidence
              </p>
              <h3 className="mt-1 text-lg font-semibold text-[var(--ink-strong)]">Evidence at a glance</h3>
            </div>
            <ReliabilityBadge value={row.overallReliability} />
          </div>

          {intelLoading ? (
            <div className="mt-3 rounded-[18px] border border-dashed border-[var(--line-strong)] p-4 text-sm text-[var(--ink-muted)]">
              Loading form, ratings, and head-to-head…
            </div>
          ) : analysis ? (
            <ComparisonGrid analysis={analysis} row={row} />
          ) : (
            <div className="mt-3 rounded-[18px] border border-dashed border-[var(--line-strong)] p-4 text-sm text-[var(--ink-muted)]">
              {intelError ?? 'Form and rating evidence is unavailable for this market identity.'}
            </div>
          )}
        </section>

        <section className="rounded-[20px] border border-[var(--line)] bg-[var(--panel-soft)] p-4">
          <div className="flex items-start gap-3">
            <span className="inline-flex size-9 shrink-0 items-center justify-center rounded-2xl bg-[var(--accent-fade)] text-[var(--accent-ink)]">
              <Gauge aria-hidden="true" className="size-4" />
            </span>
            <div className="min-w-0">
              <p className="text-xs font-semibold uppercase tracking-[0.18em] text-[var(--ink-muted)]">
                Bettor read · {row.topTrigger ?? 'No dominant trigger'}
              </p>
              <p className="mt-1 text-sm leading-6 text-[var(--ink-strong)]">
                {row.rationale || 'The model is monitoring this matchup without a qualified recommendation.'}
              </p>
              <div className="mt-3 flex flex-wrap gap-x-5 gap-y-2 text-xs text-[var(--ink-muted)]">
                <span>Model agreement {formatPct(row.ratingAgreement)}</span>
                <span>Confidence {formatRange(row.confidenceLow, row.confidenceHigh)}</span>
                <span>Baseline stability {formatPct(row.suggestedSideBaselineStability)}</span>
              </div>
            </div>
          </div>
        </section>

        {detailHref ? (
          <Button variant="secondary" asChild>
            <Link to={detailHref}>
              Open complete match detail
              <ArrowRight aria-hidden="true" className="size-4" />
            </Link>
          </Button>
        ) : null}
      </CardContent>
    </Card>
  )
}

function OddsFlow({ history, row }: { history: LiveBoardHistoryPoint[]; row: LiveOddsRecommendation }) {
  const points = history.length >= 2
    ? history
    : [
        {
          time: Math.floor(Date.now() / 1000) - 1,
          player1Odds: row.decimalOddsPlayer1,
          player2Odds: row.decimalOddsPlayer2,
        },
        {
          time: Math.floor(Date.now() / 1000),
          player1Odds: row.decimalOddsPlayer1,
          player2Odds: row.decimalOddsPlayer2,
        },
      ]
  const values = points.flatMap((point) => [point.player1Odds, point.player2Odds]).filter(Number.isFinite)
  const min = Math.min(...values)
  const max = Math.max(...values)
  const spread = Math.max(max - min, 0.15)
  const x = (index: number) => 14 + (index / Math.max(1, points.length - 1)) * 292
  const y = (value: number) => 104 - ((value - min) / spread) * 74
  const pathFor = (side: 'player1Odds' | 'player2Odds') =>
    points.map((point, index) => `${index === 0 ? 'M' : 'L'} ${x(index).toFixed(1)} ${y(point[side]).toFixed(1)}`).join(' ')

  return (
    <section className="overflow-hidden rounded-[22px] border border-[var(--line)] bg-[linear-gradient(145deg,#0b2620,#0a1916)] p-4 text-white" aria-labelledby="odds-flow-heading">
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="flex items-center gap-2 text-[10px] font-semibold uppercase tracking-[0.2em] text-emerald-300" id="odds-flow-heading">
            <LineChart className="size-3.5" aria-hidden="true" />
            Market pulse
          </p>
          <h3 className="mt-1 text-base font-semibold">Tracked Hard Rock price flow</h3>
        </div>
        <span className="text-right font-mono text-[10px] text-slate-400">{points.length} samples<br />8s refresh</span>
      </div>
      <svg className="mt-3 w-full" viewBox="0 0 320 122" role="img" aria-label={`Odds movement for ${row.player1Name} and ${row.player2Name}`}>
        {[30, 67, 104].map((gridY) => <line key={gridY} x1="14" y1={gridY} x2="306" y2={gridY} stroke="rgba(148,163,184,.14)" strokeWidth="1" />)}
        <path d={pathFor('player1Odds')} fill="none" stroke="#5ee7bd" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2.5" />
        <path d={pathFor('player2Odds')} fill="none" stroke="#fbbf67" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2.5" />
        <circle cx={x(points.length - 1)} cy={y(points.at(-1)?.player1Odds ?? row.decimalOddsPlayer1)} r="4" fill="#5ee7bd" stroke="#0b2620" strokeWidth="2" />
        <circle cx={x(points.length - 1)} cy={y(points.at(-1)?.player2Odds ?? row.decimalOddsPlayer2)} r="4" fill="#fbbf67" stroke="#0b2620" strokeWidth="2" />
      </svg>
      <div className="grid grid-cols-2 gap-2 border-t border-white/10 pt-3 text-xs">
        <div className="min-w-0">
          <p className="flex items-center gap-2 truncate text-slate-300"><span className="size-2 rounded-full bg-emerald-300" />{row.player1Name}</p>
          <p className="mt-1 font-mono font-bold">{row.decimalOddsPlayer1.toFixed(2)} <span className="font-sans font-normal text-slate-500">· {formatAmerican(row.americanOddsPlayer1)}</span></p>
        </div>
        <div className="min-w-0 text-right">
          <p className="flex items-center justify-end gap-2 truncate text-slate-300">{row.player2Name}<span className="size-2 rounded-full bg-amber-300" /></p>
          <p className="mt-1 font-mono font-bold">{row.decimalOddsPlayer2.toFixed(2)} <span className="font-sans font-normal text-slate-500">· {formatAmerican(row.americanOddsPlayer2)}</span></p>
        </div>
      </div>
    </section>
  )
}

function MarketSide({
  bookOdds,
  edge,
  fairOdds,
  implied,
  model,
  movement,
  name,
  selected,
}: {
  bookOdds: number
  edge: number | null
  fairOdds: number | null
  implied: number
  model: number | null
  movement: number | null
  name: string
  selected: boolean
}) {
  return (
    <div className={cn(
      'rounded-[20px] border bg-[rgba(255,255,255,0.72)] p-4',
      selected ? 'border-emerald-300 shadow-[0_16px_36px_-30px_rgba(5,150,105,0.8)]' : 'border-[var(--line)]',
    )}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <p className="truncate font-semibold text-[var(--ink-strong)]">{name}</p>
            {selected ? <Badge variant="accent">Model side</Badge> : null}
          </div>
          <p className="mt-1 text-xs text-[var(--ink-muted)]">
            Hard Rock implies {formatPct(implied)} · our model {formatPct(model)}
          </p>
        </div>
        <span className={cn(
          'shrink-0 rounded-full px-2.5 py-1 font-mono text-xs font-semibold',
          (edge ?? 0) > 0 ? 'bg-emerald-100 text-emerald-800' : 'bg-slate-100 text-slate-700',
        )}>
          {formatSignedPct(edge)} edge
        </span>
      </div>

      <ValueRail book={implied} model={model} />

      <div className="mt-3 grid grid-cols-[1fr_auto_1fr] items-center gap-3">
        <Price label="Hard Rock live" value={formatAmerican(bookOdds)} />
        <ArrowRight aria-hidden="true" className="size-4 text-[var(--ink-muted)]" />
        <Price align="right" label="TTLElite fair" value={formatAmerican(fairOdds)} />
      </div>

      {movement != null ? (
        <p className="mt-2 flex items-center gap-1 text-xs text-[var(--ink-muted)]">
          {movement >= 0 ? <TrendingUp aria-hidden="true" className="size-3 text-emerald-700" /> : <TrendingDown aria-hidden="true" className="size-3 text-rose-700" />}
          Tracked price move {movement >= 0 ? '+' : ''}{movement.toFixed(2)} decimal
        </p>
      ) : null}
    </div>
  )
}

function ValueRail({ book, model }: { book: number; model: number | null }) {
  const bookPct = clampPct(book)
  const modelPct = clampPct(model ?? book)
  const left = Math.min(bookPct, modelPct)
  const width = Math.abs(modelPct - bookPct)
  return (
    <div className="mt-4">
      <div className="relative h-2 rounded-full bg-slate-100" aria-label={`Book probability ${formatPct(book)}, model probability ${formatPct(model)}`}>
        <span
          className="absolute inset-y-0 rounded-full bg-emerald-200"
          style={{ left: `${left}%`, width: `${Math.max(width, 1)}%` }}
        />
        <span
          className="absolute top-1/2 size-3 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-white bg-slate-500 shadow"
          style={{ left: `${bookPct}%` }}
          title="Hard Rock implied probability"
        />
        <span
          className="absolute top-1/2 size-3 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-white bg-emerald-600 shadow"
          style={{ left: `${modelPct}%` }}
          title="TTLElite model probability"
        />
      </div>
      <div className="mt-1 flex justify-between text-[10px] font-semibold uppercase tracking-[0.12em] text-[var(--ink-muted)]">
        <span>Book</span>
        <span>Model</span>
      </div>
    </div>
  )
}

function ComparisonGrid({ analysis, row }: { analysis: MatchupAnalysis; row: LiveOddsRecommendation }) {
  return (
    <div className="mt-3 overflow-hidden rounded-[20px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)]">
      <div className="grid grid-cols-[1fr_92px_1fr] items-end gap-2 border-b border-[var(--line)] px-3 py-3">
        <p className="truncate text-sm font-semibold text-[var(--ink-strong)]">{row.player1Name}</p>
        <span className="text-center text-[10px] font-semibold uppercase tracking-[0.15em] text-[var(--ink-muted)]">Metric</span>
        <p className="truncate text-right text-sm font-semibold text-[var(--ink-strong)]">{row.player2Name}</p>
      </div>
      <ComparisonRow
        left={formatForm(analysis.player1Form)}
        label="Last 10"
        right={formatForm(analysis.player2Form)}
        leftWins={analysis.player1Form.recentWinPct > analysis.player2Form.recentWinPct}
        rightWins={analysis.player2Form.recentWinPct > analysis.player1Form.recentWinPct}
      />
      <ComparisonRow
        left={formatForm(analysis.player1Last50)}
        label="Last 50"
        right={formatForm(analysis.player2Last50)}
        leftWins={analysis.player1Last50.recentWinPct > analysis.player2Last50.recentWinPct}
        rightWins={analysis.player2Last50.recentWinPct > analysis.player1Last50.recentWinPct}
      />
      <ComparisonRow
        left={Math.round(analysis.player1Ratings.elo).toString()}
        label="Elo"
        right={Math.round(analysis.player2Ratings.elo).toString()}
        leftWins={analysis.player1Ratings.elo > analysis.player2Ratings.elo}
        rightWins={analysis.player2Ratings.elo > analysis.player1Ratings.elo}
      />
      <ComparisonRow
        left={`${analysis.recentHeadToHead.player1Wins}W`}
        label={`H2H · ${analysis.recentHeadToHead.matches}`}
        right={`${analysis.recentHeadToHead.player2Wins}W`}
        leftWins={analysis.recentHeadToHead.player1Wins > analysis.recentHeadToHead.player2Wins}
        rightWins={analysis.recentHeadToHead.player2Wins > analysis.recentHeadToHead.player1Wins}
      />
      <ComparisonRow
        left={`${analysis.headToHead.player1Wins}W`}
        label={`All H2H · ${analysis.headToHead.totalMatches}`}
        right={`${analysis.headToHead.player2Wins}W`}
        leftWins={analysis.headToHead.player1Wins > analysis.headToHead.player2Wins}
        rightWins={analysis.headToHead.player2Wins > analysis.headToHead.player1Wins}
      />
    </div>
  )
}

function ComparisonRow({
  label,
  left,
  leftWins,
  right,
  rightWins,
}: {
  label: string
  left: string
  leftWins: boolean
  right: string
  rightWins: boolean
}) {
  return (
    <div className="grid grid-cols-[1fr_92px_1fr] items-center gap-2 border-b border-[var(--line)] px-3 py-2.5 last:border-0">
      <span className={cn('font-mono text-sm font-semibold', leftWins ? 'text-emerald-700' : 'text-[var(--ink-strong)]')}>{left}</span>
      <span className="text-center text-[10px] font-semibold uppercase tracking-[0.12em] text-[var(--ink-muted)]">{label}</span>
      <span className={cn('text-right font-mono text-sm font-semibold', rightWins ? 'text-emerald-700' : 'text-[var(--ink-strong)]')}>{right}</span>
    </div>
  )
}

function PositionStrip({ bet, row }: { bet: PaperTradeBet; row: LiveOddsRecommendation }) {
  const isP1 = bet.sideName === row.player1Name
  const currentDecimal = isP1 ? row.decimalOddsPlayer1 : row.decimalOddsPlayer2
  const currentEdge = isP1 ? row.edgePlayer1 : row.edgePlayer2
  const scoreRead = inferScoreRead(row.liveScore, isP1)
  return (
    <div className="grid gap-3 rounded-[20px] border border-amber-300 bg-amber-50/80 p-4 sm:grid-cols-[1.4fr_repeat(3,1fr)]">
      <div>
        <p className="flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.18em] text-amber-800">
          <CircleDollarSign aria-hidden="true" className="size-4" />
          Your open bet
        </p>
        <p className="mt-1 font-semibold text-amber-950">${bet.stake.toFixed(2)} on {bet.sideName}</p>
        <p className="mt-1 text-xs text-amber-800">{scoreRead ?? 'Awaiting score advantage'}</p>
      </div>
      <MiniMetric label="Placed" value={bet.decimalOdds.toFixed(2)} />
      <MiniMetric label="Now" value={Number.isFinite(currentDecimal) ? currentDecimal.toFixed(2) : '—'} />
      <MiniMetric label="Edge now" value={formatSignedPct(currentEdge)} />
    </div>
  )
}

function Price({ align, label, value }: { align?: 'right'; label: string; value: string }) {
  return (
    <div className={align === 'right' ? 'text-right' : undefined}>
      <p className="text-[10px] font-semibold uppercase tracking-[0.16em] text-[var(--ink-muted)]">{label}</p>
      <p className="mt-1 font-mono text-xl font-bold text-[var(--ink-strong)]">{value}</p>
    </div>
  )
}

function MiniMetric({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="text-[10px] font-semibold uppercase tracking-[0.16em] text-amber-700">{label}</p>
      <p className="mt-1 font-mono text-base font-bold text-amber-950">{value}</p>
    </div>
  )
}

function ReliabilityBadge({ value }: { value: number | null }) {
  const pct = value == null ? null : value * 100
  const label = pct == null ? 'Unknown depth' : pct >= 70 ? 'Strong depth' : pct >= 40 ? 'Moderate depth' : 'Thin depth'
  return (
    <span className="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2.5 py-1 text-xs font-semibold text-slate-700">
      <ShieldCheck aria-hidden="true" className="size-3" />
      {label}
    </span>
  )
}

function StatusBadge({ live }: { live: boolean }) {
  return (
    <Badge className={cn(live && 'border-rose-200 bg-rose-50 text-rose-700')}>
      <span aria-hidden="true" className={cn('size-2 rounded-full bg-slate-400', live && 'animate-pulse bg-rose-500')} />
      {live ? 'Live now' : 'Upcoming'}
    </Badge>
  )
}

function formatForm(form: MatchupForm) {
  return `${form.recentWins}-${Math.max(0, form.recentMatches - form.recentWins)}`
}

function decimalMovement(first: number | undefined, last: number | undefined) {
  if (first == null || last == null || !Number.isFinite(first) || !Number.isFinite(last)) return null
  const delta = last - first
  return Math.abs(delta) < 0.005 ? null : delta
}

function inferScoreRead(score: string | null, isP1: boolean) {
  if (!score) return null
  const match = /^(\d+)-(\d+)/.exec(score.trim())
  if (!match) return null
  const mine = Number(isP1 ? match[1] : match[2])
  const theirs = Number(isP1 ? match[2] : match[1])
  if (mine === theirs) return 'Position is tied'
  return mine > theirs ? 'Position is leading' : 'Position is trailing'
}

function formatAmerican(value: number | null | undefined) {
  if (value == null || !Number.isFinite(value)) return 'N/A'
  return value > 0 ? `+${Math.round(value)}` : String(Math.round(value))
}

function formatPct(value: number | null | undefined) {
  if (value == null || !Number.isFinite(value)) return 'N/A'
  return `${(value * 100).toFixed(1)}%`
}

function formatSignedPct(value: number | null | undefined) {
  if (value == null || !Number.isFinite(value)) return 'N/A'
  const pct = value * 100
  return `${pct >= 0 ? '+' : ''}${pct.toFixed(1)}%`
}

function formatRange(low: number | null, high: number | null) {
  if (low == null || high == null) return 'N/A'
  return `${formatPct(low)}–${formatPct(high)}`
}

function formatStart(value: string | null) {
  if (!value) return 'Start time pending'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat(undefined, {
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    month: 'short',
  }).format(date)
}

function formatPhase(value: string) {
  return value.replaceAll('_', ' ').toLowerCase().replace(/\b\w/g, (letter) => letter.toUpperCase())
}

function clampPct(value: number) {
  return Math.min(96, Math.max(4, value * 100))
}
