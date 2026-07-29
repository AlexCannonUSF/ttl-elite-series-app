import {
  type Dispatch,
  type KeyboardEvent as ReactKeyboardEvent,
  type SetStateAction,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import {
  Activity,
  AlertTriangle,
  BarChart3,
  CheckCircle2,
  CircleDollarSign,
  Filter,
  RefreshCcw,
  Search,
  Star,
  TrendingDown,
  TrendingUp,
} from 'lucide-react'
import { Link } from 'react-router-dom'

import { V3Shell } from '@/components/layout/V3Shell'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { fetchLiveBoard, fetchLiveSession, syncLiveSession } from '@/features/live-studio/api'
import { FlashOnChange } from '@/features/live-studio/FlashOnChange'
import { OddsSparkChart } from '@/features/live-studio/OddsSparkChart'
import { SessionRibbon } from '@/features/live-studio/SessionRibbon'
import type {
  LiveBoardHistoryPoint,
  LiveOddsRecommendation,
  PaperTradeBet,
  PaperTradingSession,
} from '@/features/live-studio/types'
import { cn } from '@/lib/utils'

const REFRESH_INTERVAL_MS = 8000
const HISTORY_LIMIT = 80

const compactNumber = new Intl.NumberFormat('en-US', {
  maximumFractionDigits: 0,
})

export function LiveBoardRoute() {
  const [rows, setRows] = useState<LiveOddsRecommendation[]>([])
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [syncing, setSyncing] = useState(false)
  const [strategy, setStrategy] = useState<'CONSERVATIVE' | 'AGGRESSIVE'>('CONSERVATIVE')
  const [includeUnresolved, setIncludeUnresolved] = useState(true)
  const [search, setSearch] = useState('')
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  const [history, setHistory] = useState<Record<string, LiveBoardHistoryPoint[]>>({})
  const [session, setSession] = useState<PaperTradingSession | null>(null)
  const [myPicksOnly, setMyPicksOnly] = useState(false)
  const mountedRef = useRef(true)

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  const loadBoard = useCallback(async (background: boolean) => {
    const controller = new AbortController()
    if (mountedRef.current) {
      if (background) {
        setRefreshing(true)
      } else {
        setLoading(true)
      }
    }

    try {
      const [nextRows, nextSession] = await Promise.all([
        fetchLiveBoard({
          includeUnresolved,
          limit: 80,
          signal: controller.signal,
          strategy,
        }),
        fetchLiveSession(controller.signal).catch(() => null),
      ])
      if (!mountedRef.current) {
        return
      }
      setRows(nextRows)
      setSession(nextSession)
      setError(null)
      appendHistory(nextRows, setHistory)
      const myPickKeys = nextSession
        ? new Set(
            nextRows
              .filter((row) => matchOpenBet(row, nextSession.openBetsList) != null)
              .map((row) => rowKey(row)),
          )
        : new Set<string>()
      setSelectedKey((current) => {
        if (current && nextRows.some((row) => rowKey(row) === current)) {
          return current
        }
        const myLive = nextRows.find((row) => row.live && myPickKeys.has(rowKey(row)))
        const myAny = nextRows.find((row) => myPickKeys.has(rowKey(row)))
        const preferred =
          myLive
          ?? myAny
          ?? nextRows.find((row) => row.recommended)
          ?? nextRows.find((row) => row.live)
          ?? nextRows[0]
        return preferred ? rowKey(preferred) : null
      })
    } catch (nextError) {
      if (!mountedRef.current) {
        return
      }
      setError(nextError instanceof Error ? nextError.message : 'Unable to load the live board.')
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
  }, [includeUnresolved, strategy])

  useEffect(() => {
    void loadBoard(false)
    const interval = window.setInterval(() => {
      void loadBoard(true)
    }, REFRESH_INTERVAL_MS)

    return () => window.clearInterval(interval)
  }, [loadBoard])

  const syncBoard = useCallback(async () => {
    setSyncing(true)
    try {
      await syncLiveSession({ limit: 80, strategy })
      await loadBoard(true)
    } catch (nextError) {
      setError(nextError instanceof Error ? nextError.message : 'Unable to sync the live session.')
    } finally {
      setSyncing(false)
    }
  }, [loadBoard, strategy])

  const openBets = session?.openBetsList ?? []

  const myPickByRow = useMemo(() => {
    const map = new Map<string, PaperTradeBet>()
    if (openBets.length === 0) return map
    for (const row of rows) {
      const bet = matchOpenBet(row, openBets)
      if (bet) map.set(rowKey(row), bet)
    }
    return map
  }, [rows, openBets])

  const filteredRows = useMemo(() => {
    const term = search.trim().toLowerCase()
    let base = rows
    if (myPicksOnly) {
      base = base.filter((row) => myPickByRow.has(rowKey(row)))
    }
    if (term) {
      base = base.filter((row) => {
        const blob = `${row.eventName} ${row.competitionName} ${row.player1Name} ${row.player2Name} ${row.suggestedSide ?? ''} ${row.topTrigger ?? ''}`.toLowerCase()
        return blob.includes(term)
      })
    }
    // Pin "my picks" to the top, then live, then everything else.
    return [...base].sort((a, b) => {
      const aMine = myPickByRow.has(rowKey(a)) ? 0 : 1
      const bMine = myPickByRow.has(rowKey(b)) ? 0 : 1
      if (aMine !== bMine) return aMine - bMine
      const aLive = a.live ? 0 : 1
      const bLive = b.live ? 0 : 1
      if (aLive !== bLive) return aLive - bLive
      return 0
    })
  }, [rows, search, myPicksOnly, myPickByRow])

  const selectedRow = useMemo(() => {
    if (!selectedKey) {
      return filteredRows[0] ?? rows[0] ?? null
    }
    return rows.find((row) => rowKey(row) === selectedKey) ?? filteredRows[0] ?? rows[0] ?? null
  }, [filteredRows, rows, selectedKey])

  const diagnostics = useMemo(() => summarizeRows(rows), [rows])
  const selectedHistory = selectedRow ? history[rowKey(selectedRow)] ?? [] : []

  return (
    <V3Shell
      title="Live Board"
      description="Live odds, model edge, score state, and paper-pick readiness in the v3 operating shell."
      badges={
        <>
          <Badge variant="accent">Live</Badge>
          <Badge>{session ? `${session.openBets} open picks` : '—'}</Badge>
          <Badge>{`${rows.filter((r) => r.live).length} live`}</Badge>
          <Badge>Refresh 8s</Badge>
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
          <Button variant="secondary" onClick={() => void syncBoard()} disabled={syncing || loading}>
            <CircleDollarSign className={cn('size-4', syncing && 'animate-pulse')} />
            Sync
          </Button>
          <Button variant="secondary" onClick={() => void loadBoard(true)} disabled={loading || refreshing}>
            <RefreshCcw className={cn('size-4', refreshing && 'animate-spin')} />
            Refresh
          </Button>
        </>
      }
    >
      <SessionRibbon />

      {error ? (
        <div className="mt-5 inline-flex items-center gap-2 rounded-[18px] border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800" role="alert">
          <AlertTriangle aria-hidden="true" className="size-4" />
          <span>{error}</span>
        </div>
      ) : null}

      <section className="mt-5 grid gap-5 xl:grid-cols-[1fr_0.92fr]">
        <Card>
          <CardHeader>
            <Badge variant="accent" className="w-fit">
              Board State
            </Badge>
            <CardTitle>Value board with live movement</CardTitle>
            <CardDescription>
              Current sportsbook rows, model edge, recommendation gates, and local odds history from the polling stream.
            </CardDescription>
          </CardHeader>
          <CardContent className="grid gap-4">
            <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
              <DiagnosticTile icon={BarChart3} label="Rows" value={compactNumber.format(diagnostics.totalRows)} />
              <DiagnosticTile icon={Activity} label="Live" value={compactNumber.format(diagnostics.liveRows)} />
              <DiagnosticTile icon={CheckCircle2} label="Recommended" value={compactNumber.format(diagnostics.recommendedRows)} />
              <DiagnosticTile icon={TrendingUp} label="Avg Edge" value={formatSignedPct(diagnostics.averageSuggestedEdge)} />
            </div>

            <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
              <label className="flex min-h-12 flex-1 items-center gap-3 rounded-[18px] border border-[var(--line)] bg-[rgba(255,255,255,0.74)] px-4 text-sm text-[var(--ink-muted)]">
                <Search aria-hidden="true" className="size-4" />
                <span className="sr-only">Filter live board rows</span>
                <input
                  className="min-w-0 flex-1 bg-transparent text-[var(--ink-strong)] outline-none placeholder:text-[var(--ink-muted)]"
                  placeholder="Filter players, event, trigger"
                  value={search}
                  onChange={(event) => setSearch(event.target.value)}
                />
              </label>
              <Button
                aria-pressed={myPicksOnly}
                variant={myPicksOnly ? 'primary' : 'secondary'}
                onClick={() => setMyPicksOnly((value) => !value)}
                disabled={openBets.length === 0}
                title={openBets.length === 0 ? 'No open picks right now' : undefined}
              >
                <Star aria-hidden="true" className="size-4" />
                {myPicksOnly ? `My picks (${openBets.length})` : `My picks (${openBets.length}) only`}
              </Button>
              <Button
                aria-pressed={includeUnresolved}
                variant="secondary"
                onClick={() => setIncludeUnresolved((value) => !value)}
              >
                <Filter aria-hidden="true" className="size-4" />
                {includeUnresolved ? 'Unresolved included' : 'Unresolved hidden'}
              </Button>
            </div>

            <div className="overflow-x-auto">
              <table className="min-w-full border-separate border-spacing-y-3">
                <thead>
                  <tr className="text-left text-xs uppercase tracking-[0.2em] text-[var(--ink-muted)]">
                    <th className="px-3 pb-1 font-semibold">Match</th>
                    <th className="px-3 pb-1 text-right font-semibold">Odds</th>
                    <th className="px-3 pb-1 text-right font-semibold">Model</th>
                    <th className="px-3 pb-1 text-right font-semibold">Edge</th>
                    <th className="px-3 pb-1 font-semibold">Signal</th>
                  </tr>
                </thead>
                <tbody>
                  {filteredRows.map((row) => (
                    <BoardRow
                      key={rowKey(row)}
                      row={row}
                      myPick={myPickByRow.get(rowKey(row)) ?? null}
                      selected={selectedRow ? rowKey(selectedRow) === rowKey(row) : false}
                      onSelect={() => setSelectedKey(rowKey(row))}
                    />
                  ))}
                </tbody>
              </table>
            </div>

            {!loading && filteredRows.length === 0 ? (
              <div className="rounded-[20px] border border-dashed border-[var(--line-strong)] p-5 text-sm text-[var(--ink-muted)]">
                No live board rows match the current filters.
              </div>
            ) : null}
          </CardContent>
        </Card>

        <div className="grid content-start gap-5">
          {selectedRow && myPickByRow.has(rowKey(selectedRow)) ? (
            <YourLiveBetCard row={selectedRow} bet={myPickByRow.get(rowKey(selectedRow))!} />
          ) : null}

          <Card>
            <CardHeader>
              <Badge className="w-fit">Odds Chart</Badge>
              <CardTitle>{selectedRow ? selectedRow.eventName : 'Select a row'}</CardTitle>
              <CardDescription>
                {selectedRow
                  ? `${selectedRow.player1Name} vs ${selectedRow.player2Name}`
                  : 'The chart fills as the board refreshes.'}
              </CardDescription>
            </CardHeader>
            <CardContent>
              {selectedRow ? (
                <>
                  <OddsSparkChart
                    player1Name={selectedRow.player1Name}
                    player2Name={selectedRow.player2Name}
                    points={selectedHistory}
                  />
                  <div className="mt-4 grid gap-3 sm:grid-cols-3">
                    <Metric label="Score" value={selectedRow.liveScore ?? 'N/A'} />
                    <Metric label="Phase" value={selectedRow.matchPhase ?? (selectedRow.live ? 'Live' : 'Queued')} />
                    <Metric label="Source" value={selectedRow.sourceType ?? selectedRow.source} />
                  </div>
                </>
              ) : (
                <div className="rounded-[20px] border border-dashed border-[var(--line-strong)] p-6 text-sm text-[var(--ink-muted)]">
                  Waiting for board rows.
                </div>
              )}
            </CardContent>
          </Card>

          {selectedRow ? (
            <Card>
              <CardHeader>
                <Badge variant={selectedRow.recommended ? 'accent' : 'neutral'} className="w-fit">
                  {selectedRow.grade || 'Board Row'}
                </Badge>
                <CardTitle>{selectedRow.suggestedSide ?? 'No suggested side'}</CardTitle>
                <CardDescription>{selectedRow.rationale || 'No rationale returned for this row yet.'}</CardDescription>
              </CardHeader>
              <CardContent className="grid gap-3">
                <div className="grid gap-3 sm:grid-cols-2">
                  <Metric label="Suggested Edge" value={formatSignedPct(selectedRow.suggestedEdge)} />
                  <Metric label="Fair Odds" value={formatAmerican(selectedRow.suggestedFairAmericanOdds)} />
                  <Metric label="Reliability" value={formatPct(selectedRow.overallReliability)} />
                  <Metric label="Trigger" value={selectedRow.topTrigger ?? 'N/A'} />
                </div>
                <Button variant="secondary" asChild>
                  <Link to={`/matches/${encodeURIComponent(matchDetailKey(selectedRow))}/evidence`}>
                    Open Match Detail
                  </Link>
                </Button>
              </CardContent>
            </Card>
          ) : null}
        </div>
      </section>
    </V3Shell>
  )
}

function BoardRow({
  onSelect,
  row,
  myPick,
  selected,
}: {
  onSelect: () => void
  row: LiveOddsRecommendation
  myPick: PaperTradeBet | null
  selected: boolean
}) {
  const suggestedPlayer = row.suggestedSide === row.player1Name ? 'P1' : row.suggestedSide === row.player2Name ? 'P2' : null
  const p1Suggested = suggestedPlayer === 'P1'
  const p2Suggested = suggestedPlayer === 'P2'
  const myPickIsP1 = myPick != null && myPick.sideName === row.player1Name
  const myPickIsP2 = myPick != null && myPick.sideName === row.player2Name
  const myCurrentOdds = myPickIsP1 ? row.decimalOddsPlayer1 : myPickIsP2 ? row.decimalOddsPlayer2 : null
  const oddsDelta = myPick && myCurrentOdds != null ? myCurrentOdds - myPick.decimalOdds : null

  const handleKeyDown = (event: ReactKeyboardEvent<HTMLTableRowElement>) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      onSelect()
    }
  }

  return (
    <tr
      aria-label={`Select ${row.eventName}`}
      aria-selected={selected}
      className={cn(
        'cursor-pointer rounded-[20px] bg-[rgba(255,255,255,0.76)] shadow-[0_18px_48px_-42px_rgba(8,25,28,0.72)] transition-colors hover:bg-[rgba(255,255,255,0.92)]',
        selected && 'outline outline-2 outline-offset-2 outline-[var(--accent-soft)]',
        row.recommended && !myPick && 'bg-[rgba(236,253,245,0.82)]',
        myPick && 'bg-[rgba(254,243,199,0.78)] outline outline-2 -outline-offset-2 outline-amber-300',
      )}
      tabIndex={0}
      onClick={onSelect}
      onKeyDown={handleKeyDown}
    >
      <td className="rounded-l-[20px] px-3 py-4 align-top">
        <div className="flex flex-wrap items-center gap-2">
          {myPick ? (
            <Badge variant="accent" className="border-amber-300 bg-amber-100 text-amber-900">
              <Star aria-hidden="true" className="size-3" />
              MY PICK · {myPick.sideName}
            </Badge>
          ) : null}
          <StatusPill live={row.live} />
          <Scoreboard
            player1Name={row.player1Name}
            player2Name={row.player2Name}
            liveScore={row.liveScore}
            phase={row.matchPhase}
          />
          {row.recommended && !myPick ? <Badge variant="accent">Recommended</Badge> : null}
        </div>
        <p className="mt-3 font-medium text-[var(--ink-strong)]">{row.eventName}</p>
        <p className="mt-1 max-w-[320px] truncate text-sm text-[var(--ink-muted)]">
          {row.competitionName} | {formatStart(row.startTimeIso)}
        </p>
      </td>
      <td className="px-3 py-4 text-right align-top font-mono text-sm">
        <p className={cn('font-semibold', p1Suggested && 'text-emerald-700')}>
          <FlashOnChange value={row.decimalOddsPlayer1}>{formatAmerican(row.americanOddsPlayer1)}</FlashOnChange>
        </p>
        <p className={cn('mt-2 font-semibold', p2Suggested && 'text-emerald-700')}>
          <FlashOnChange value={row.decimalOddsPlayer2}>{formatAmerican(row.americanOddsPlayer2)}</FlashOnChange>
        </p>
      </td>
      <td className="px-3 py-4 text-right align-top text-sm">
        <p>{row.player1Name}: {formatPct(row.modelProbabilityPlayer1)}</p>
        <p className="mt-2">{row.player2Name}: {formatPct(row.modelProbabilityPlayer2)}</p>
      </td>
      <td className="px-3 py-4 text-right align-top font-mono text-sm">
        <p>
          <FlashOnChange value={row.edgePlayer1}>{formatSignedPct(row.edgePlayer1)}</FlashOnChange>
        </p>
        <p className="mt-2">
          <FlashOnChange value={row.edgePlayer2}>{formatSignedPct(row.edgePlayer2)}</FlashOnChange>
        </p>
      </td>
      <td className="rounded-r-[20px] px-3 py-4 align-top text-sm">
        {myPick ? (
          <>
            <p className="font-semibold text-amber-900">
              ${myPick.stake.toFixed(2)} on {myPick.sideName}
            </p>
            <p className="mt-1 text-xs text-[var(--ink-muted)]">
              Placed at {myPick.decimalOdds.toFixed(2)} · now{' '}
              {myCurrentOdds != null ? myCurrentOdds.toFixed(2) : '—'}
            </p>
            {oddsDelta != null ? <OddsDeltaPill delta={oddsDelta} /> : null}
            {myPick.potentialPayout != null ? (
              <p className="mt-1 font-mono text-xs text-[var(--ink-muted)]">
                Pays ${myPick.potentialPayout.toFixed(2)}
              </p>
            ) : null}
          </>
        ) : (
          <>
            <p className="font-semibold text-[var(--ink-strong)]">{row.suggestedSide ?? 'No pick'}</p>
            <p className="mt-1 text-[var(--ink-muted)]">{row.topTrigger ?? row.grade ?? 'Watching'}</p>
            <p className="mt-2 font-mono text-xs text-[var(--ink-muted)]">{formatSignedPct(row.suggestedEdge)}</p>
          </>
        )}
      </td>
    </tr>
  )
}

function Scoreboard({
  player1Name,
  player2Name,
  liveScore,
  phase,
}: {
  player1Name: string
  player2Name: string
  liveScore: string | null
  phase: string | null
}) {
  if (!liveScore) {
    return phase ? <Badge>{formatPhase(phase)}</Badge> : null
  }
  // Server returns liveScore like "0-1 (1-0)" — sets · current game in points.
  const match = /^(\d+)-(\d+)(?:\s*\((\d+)-(\d+)\))?$/.exec(liveScore.trim())
  if (!match) {
    return <Badge>SCORE {liveScore}</Badge>
  }
  const p1Sets = match[1] ?? '0'
  const p2Sets = match[2] ?? '0'
  const p1Pts = match[3]
  const p2Pts = match[4]
  return (
    <span className="inline-flex items-center gap-2 rounded-[12px] border border-rose-200 bg-rose-50 px-2 py-1 font-mono text-xs text-rose-800">
      <ScoreCell label={shortName(player1Name)} sets={p1Sets} pts={p1Pts} />
      <span className="text-rose-300">·</span>
      <ScoreCell label={shortName(player2Name)} sets={p2Sets} pts={p2Pts} />
      {phase ? <span className="ml-1 rounded-full bg-rose-100 px-2 text-[10px] uppercase">{formatPhase(phase)}</span> : null}
    </span>
  )
}

function ScoreCell({ label, sets, pts }: { label: string; sets: string; pts?: string | undefined }) {
  return (
    <span className="inline-flex items-baseline gap-1">
      <span className="font-semibold">{label}</span>
      <span className="text-base font-bold">{sets}</span>
      {pts ? <span className="text-[var(--ink-muted)]">({pts})</span> : null}
    </span>
  )
}

function OddsDeltaPill({ delta }: { delta: number }) {
  if (!Number.isFinite(delta) || Math.abs(delta) < 0.005) {
    return <span className="mt-1 inline-flex items-center gap-1 rounded-full bg-slate-100 px-2 py-0.5 text-[11px] font-medium text-slate-700">no move</span>
  }
  const improved = delta > 0 // your price got longer → you're winning the price for under
  const tone = improved
    ? 'bg-emerald-100 text-emerald-800'
    : 'bg-rose-100 text-rose-800'
  const Icon = improved ? TrendingUp : TrendingDown
  return (
    <span className={cn('mt-1 inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold', tone)}>
      <Icon className="size-3" />
      {delta >= 0 ? '+' : ''}{delta.toFixed(2)} {improved ? 'better' : 'worse'}
    </span>
  )
}

function shortName(name: string) {
  if (!name) return ''
  const parts = name.trim().split(/\s+/)
  if (parts.length === 1) return (parts[0] ?? '').slice(0, 8)
  const first = (parts[0] ?? '')[0] ?? ''
  const last = parts[parts.length - 1] ?? ''
  return `${first}. ${last}`
}

function formatPhase(phase: string) {
  return phase
    .replaceAll('_', ' ')
    .toLowerCase()
    .replace(/\b\w/g, (m) => m.toUpperCase())
}

/** Match a board row to one of the user's open paper bets. */
function matchOpenBet(row: LiveOddsRecommendation, openBets: PaperTradeBet[]): PaperTradeBet | null {
  if (!openBets || openBets.length === 0) return null
  const rowExternal = row.externalEventId ?? null
  const rowMatchup = row.matchupKey ?? null
  for (const bet of openBets) {
    const betExternal = bet.lockedExternalEventId ?? bet.externalEventId ?? null
    if (rowExternal && betExternal && rowExternal === betExternal) {
      return bet
    }
    if (rowMatchup && bet.matchupKey && rowMatchup === bet.matchupKey) {
      return bet
    }
    // Fallback: same matchup name + same picked side.
    if (
      bet.eventName === row.eventName
      && (bet.sideName === row.player1Name || bet.sideName === row.player2Name)
    ) {
      return bet
    }
  }
  return null
}

function YourLiveBetCard({ row, bet }: { row: LiveOddsRecommendation; bet: PaperTradeBet }) {
  const isP1 = bet.sideName === row.player1Name
  const currentDecimal: number = isP1 ? row.decimalOddsPlayer1 : row.decimalOddsPlayer2
  const currentAmerican: number = isP1 ? row.americanOddsPlayer1 : row.americanOddsPlayer2
  const currentModelProb: number | null = (isP1 ? row.modelProbabilityPlayer1 : row.modelProbabilityPlayer2) ?? null
  const currentEdge: number | null = (isP1 ? row.edgePlayer1 : row.edgePlayer2) ?? null
  const oddsDelta = Number.isFinite(currentDecimal) ? currentDecimal - bet.decimalOdds : null
  const edgeDelta = currentEdge != null && Number.isFinite(currentEdge) ? currentEdge - bet.edge : null
  const probDelta = currentModelProb != null && Number.isFinite(currentModelProb)
    ? currentModelProb - bet.modelProbability
    : null

  // Live "are we winning the bet?" hint based on the score string.
  const winSignal = inferWinSignal(row.liveScore, isP1 ? 'P1' : 'P2')

  return (
    <Card className="border-amber-300 bg-amber-50/60">
      <CardHeader>
        <Badge variant="accent" className="w-fit border-amber-300 bg-amber-100 text-amber-900">
          <Star className="size-3" />
          Your live bet
        </Badge>
        <CardTitle>
          ${bet.stake.toFixed(2)} on {bet.sideName}
        </CardTitle>
        <CardDescription>
          {row.player1Name} vs {row.player2Name} · {row.competitionName ?? ''}
          {row.liveScore ? ` · score ${row.liveScore}` : ''}
          {winSignal ? ` · ${winSignal}` : ''}
        </CardDescription>
      </CardHeader>
      <CardContent className="grid gap-3">
        <div className="grid gap-3 sm:grid-cols-3">
          <MovementTile
            label="Odds"
            placed={bet.decimalOdds.toFixed(2)}
            current={Number.isFinite(currentDecimal) ? currentDecimal.toFixed(2) : '—'}
            delta={oddsDelta}
            higherIsBetter
          />
          <MovementTile
            label="Edge"
            placed={`${(bet.edge * 100).toFixed(1)}%`}
            current={currentEdge != null && Number.isFinite(currentEdge) ? `${(currentEdge * 100).toFixed(1)}%` : '—'}
            delta={edgeDelta}
            higherIsBetter
            formatDelta={(d) => `${d >= 0 ? '+' : ''}${(d * 100).toFixed(1)}%`}
          />
          <MovementTile
            label="Model prob."
            placed={`${(bet.modelProbability * 100).toFixed(0)}%`}
            current={currentModelProb != null && Number.isFinite(currentModelProb) ? `${(currentModelProb * 100).toFixed(0)}%` : '—'}
            delta={probDelta}
            higherIsBetter
            formatDelta={(d) => `${d >= 0 ? '+' : ''}${(d * 100).toFixed(1)}%`}
          />
        </div>
        <div className="grid gap-3 sm:grid-cols-3">
          <Metric label="American odds (placed)" value={formatAmerican(bet.americanOdds ?? null)} />
          <Metric label="American odds (now)" value={formatAmerican(currentAmerican)} />
          <Metric
            label="Potential payout"
            value={bet.potentialPayout != null ? `$${bet.potentialPayout.toFixed(2)}` : `$${(bet.stake * bet.decimalOdds).toFixed(2)}`}
          />
        </div>
        <Button variant="secondary" asChild>
          <Link to={`/matches/${encodeURIComponent(matchDetailKey(row))}/evidence`}>
            Open match detail
          </Link>
        </Button>
      </CardContent>
    </Card>
  )
}

function MovementTile({
  label,
  placed,
  current,
  delta,
  higherIsBetter,
  formatDelta,
}: {
  label: string
  placed: string
  current: string
  delta: number | null
  higherIsBetter?: boolean
  formatDelta?: (d: number) => string
}) {
  const showDelta = delta != null && Number.isFinite(delta) && Math.abs(delta) > 0.0001
  const goodWhenPositive = higherIsBetter ?? false
  const good = showDelta && (goodWhenPositive ? delta! > 0 : delta! < 0)
  const Icon = showDelta ? (good ? TrendingUp : TrendingDown) : null
  const tone = good ? 'text-emerald-700' : 'text-rose-700'
  return (
    <div className="rounded-[18px] border border-amber-200 bg-white/70 p-3">
      <p className="text-[11px] font-semibold uppercase tracking-[0.2em] text-[var(--ink-muted)]">{label}</p>
      <div className="mt-1 flex items-baseline gap-2">
        <span className="font-mono text-lg font-semibold text-[var(--ink-strong)]">{current}</span>
        {showDelta ? (
          <span className={cn('inline-flex items-center gap-1 text-xs font-semibold', tone)}>
            {Icon ? <Icon className="size-3" /> : null}
            {formatDelta ? formatDelta(delta!) : `${delta! >= 0 ? '+' : ''}${delta!.toFixed(2)}`}
          </span>
        ) : null}
      </div>
      <p className="mt-1 text-xs text-[var(--ink-muted)]">Placed @ {placed}</p>
    </div>
  )
}

function inferWinSignal(score: string | null, side: 'P1' | 'P2'): string | null {
  if (!score) return null
  const match = /^(\d+)-(\d+)/.exec(score.trim())
  if (!match) return null
  const p1 = Number.parseInt(match[1] ?? '', 10)
  const p2 = Number.parseInt(match[2] ?? '', 10)
  if (Number.isNaN(p1) || Number.isNaN(p2)) return null
  if (p1 === p2) return 'tied'
  const mine = side === 'P1' ? p1 : p2
  const theirs = side === 'P1' ? p2 : p1
  return mine > theirs ? 'leading' : 'trailing'
}

function DiagnosticTile({ icon: Icon, label, value }: { icon: typeof BarChart3; label: string; value: string }) {
  return (
    <div className="rounded-[18px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4">
      <div className="flex items-center gap-3">
        <span className="inline-flex size-10 items-center justify-center rounded-2xl bg-[var(--panel-soft)] text-[var(--accent-ink)]">
          <Icon className="size-4" />
        </span>
        <div>
          <p className="text-xs font-semibold uppercase tracking-[0.22em] text-[var(--ink-muted)]">{label}</p>
          <p className="mt-1 font-serif text-2xl font-semibold tracking-[-0.04em] text-[var(--ink-strong)]">{value}</p>
        </div>
      </div>
    </div>
  )
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-[18px] border border-[var(--line)] bg-[rgba(255,255,255,0.70)] p-3">
      <p className="text-[11px] font-semibold uppercase tracking-[0.2em] text-[var(--ink-muted)]">{label}</p>
      <p className="mt-1 truncate text-sm font-semibold text-[var(--ink-strong)]">{value}</p>
    </div>
  )
}

function StatusPill({ live }: { live: boolean }) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-2 rounded-full border px-2.5 py-1 text-[11px] font-semibold uppercase tracking-[0.18em]',
        live ? 'border-rose-200 bg-rose-50 text-rose-700' : 'border-slate-200 bg-slate-50 text-slate-700',
      )}
    >
      <span aria-hidden="true" className={cn('size-2 rounded-full', live ? 'bg-rose-500 animate-pulse' : 'bg-slate-400')} />
      {live ? 'Live' : 'Upcoming'}
    </span>
  )
}

function appendHistory(
  rows: LiveOddsRecommendation[],
  setHistory: Dispatch<SetStateAction<Record<string, LiveBoardHistoryPoint[]>>>,
) {
  const sampleTime = Math.floor(Date.now() / 1000)
  setHistory((current) => {
    const next = { ...current }
    for (const row of rows) {
      if (!Number.isFinite(row.decimalOddsPlayer1) || !Number.isFinite(row.decimalOddsPlayer2)) {
        continue
      }
      const key = rowKey(row)
      const existing = next[key] ?? []
      const last = existing.at(-1)
      if (
        last
        && last.time === sampleTime
        && last.player1Odds === row.decimalOddsPlayer1
        && last.player2Odds === row.decimalOddsPlayer2
      ) {
        continue
      }
      next[key] = [
        ...existing,
        {
          player1Odds: row.decimalOddsPlayer1,
          player2Odds: row.decimalOddsPlayer2,
          time: sampleTime,
        },
      ].slice(-HISTORY_LIMIT)
    }
    return next
  })
}

function summarizeRows(rows: LiveOddsRecommendation[]) {
  const suggestedEdges = rows
    .map((row) => row.suggestedEdge)
    .filter((value): value is number => typeof value === 'number' && Number.isFinite(value))
  return {
    averageSuggestedEdge: suggestedEdges.length
      ? suggestedEdges.reduce((sum, edge) => sum + edge, 0) / suggestedEdges.length
      : null,
    liveRows: rows.filter((row) => row.live).length,
    recommendedRows: rows.filter((row) => row.recommended).length,
    totalRows: rows.length,
  }
}

function rowKey(row: LiveOddsRecommendation) {
  return row.suggestedDedupeKey
    ?? row.matchupKey
    ?? row.externalEventId
    ?? `${row.eventName}|${row.player1Name}|${row.player2Name}|${row.startTimeIso ?? ''}`
}

function matchDetailKey(row: LiveOddsRecommendation) {
  return row.matchupKey
    ?? stripDedupeSide(row.suggestedDedupeKey)
    ?? buildEventKey(row)
    ?? row.externalEventId
    ?? rowKey(row)
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

function normalizeKey(value: string | null | undefined) {
  const normalized = value?.trim().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')
  return normalized || 'na'
}

function formatAmerican(value: number | null | undefined) {
  if (value == null || !Number.isFinite(value)) {
    return 'N/A'
  }
  return value > 0 ? `+${value}` : String(value)
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
  const pct = value * 100
  return `${pct >= 0 ? '+' : ''}${pct.toFixed(2)}%`
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
    hour: 'numeric',
    minute: '2-digit',
    month: 'short',
    day: 'numeric',
  }).format(parsed)
}
