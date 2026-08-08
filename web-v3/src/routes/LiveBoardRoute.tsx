import {
  type Dispatch,
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
  Clock3,
  CircleDollarSign,
  Filter,
  Flame,
  Layers3,
  RefreshCcw,
  Search,
  Star,
} from 'lucide-react'

import { V3Shell } from '@/components/layout/V3Shell'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { fetchLiveBoard, fetchLiveSession, fetchMatchupAnalysis, syncLiveSession } from '@/features/live-studio/api'
import { BettorMatchupPanel } from '@/features/live-studio/BettorMatchupPanel'
import { calculateBookMargin } from '@/features/live-studio/marketMath'
import { SessionRibbon } from '@/features/live-studio/SessionRibbon'
import type {
  LiveBoardHistoryPoint,
  LiveOddsRecommendation,
  MatchupAnalysis,
  PaperTradeBet,
  PaperTradingSession,
} from '@/features/live-studio/types'
import { cn } from '@/lib/utils'

const REFRESH_INTERVAL_MS = 8000
const HISTORY_LIMIT = 80

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
  const [marketFilter, setMarketFilter] = useState<'ALL' | 'LIVE' | 'VALUE' | 'UPCOMING'>('ALL')
  const [matchupIntel, setMatchupIntel] = useState<MatchupAnalysis | null>(null)
  const [intelError, setIntelError] = useState<string | null>(null)
  const [intelLoading, setIntelLoading] = useState(false)
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
        const firstLive = nextRows.find((row) => row.live)
        const myAny = nextRows.find((row) => myPickKeys.has(rowKey(row)))
        const preferred =
          myLive
          ?? firstLive
          ?? myAny
          ?? nextRows.find((row) => row.recommended)
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
    if (marketFilter === 'LIVE') base = base.filter((row) => row.live)
    if (marketFilter === 'VALUE') base = base.filter((row) => row.recommended || (row.suggestedEdge ?? 0) > 0)
    if (marketFilter === 'UPCOMING') base = base.filter((row) => !row.live)
    if (term) {
      base = base.filter((row) => {
        const blob = `${row.eventName} ${row.competitionName} ${row.player1Name} ${row.player2Name} ${row.suggestedSide ?? ''} ${row.topTrigger ?? ''}`.toLowerCase()
        return blob.includes(term)
      })
    }
    return [...base].sort((a, b) => {
      const aLive = a.live ? 0 : 1
      const bLive = b.live ? 0 : 1
      if (aLive !== bLive) return aLive - bLive
      const startDifference = startTimeValue(a.startTimeIso) - startTimeValue(b.startTimeIso)
      if (startDifference !== 0) return startDifference
      const aMine = myPickByRow.has(rowKey(a)) ? 0 : 1
      const bMine = myPickByRow.has(rowKey(b)) ? 0 : 1
      if (aMine !== bMine) return aMine - bMine
      return a.eventName.localeCompare(b.eventName)
    })
  }, [rows, search, myPicksOnly, myPickByRow, marketFilter])

  const selectedRow = useMemo(() => {
    if (!selectedKey) {
      return filteredRows[0] ?? rows[0] ?? null
    }
    return rows.find((row) => rowKey(row) === selectedKey) ?? filteredRows[0] ?? rows[0] ?? null
  }, [filteredRows, rows, selectedKey])

  const diagnostics = useMemo(() => summarizeRows(rows), [rows])
  const selectedHistory = selectedRow ? history[rowKey(selectedRow)] ?? [] : []

  useEffect(() => {
    const player1Id = selectedRow?.player1Id
    const player2Id = selectedRow?.player2Id
    const controller = new AbortController()
    if (!player1Id || !player2Id) {
      setMatchupIntel(null)
      setIntelError('Player identities are still resolving for this market.')
      setIntelLoading(false)
      return () => controller.abort()
    }

    setIntelLoading(true)
    setIntelError(null)
    void fetchMatchupAnalysis(player1Id, player2Id, controller.signal)
      .then((analysis) => {
        if (!controller.signal.aborted) {
          setMatchupIntel(analysis)
          setIntelLoading(false)
        }
      })
      .catch((nextError) => {
        if (!controller.signal.aborted) {
          setMatchupIntel(null)
          setIntelError(nextError instanceof Error ? nextError.message : 'Unable to load matchup intelligence.')
          setIntelLoading(false)
        }
      })
    return () => controller.abort()
  }, [selectedRow?.player1Id, selectedRow?.player2Id])

  return (
    <V3Shell
      title="Live Intelligence"
      description="Follow the action, compare the Hard Rock market with our fair price, and understand every model lean before you decide."
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
      <section className="user-board-banner overflow-hidden rounded-[30px] border border-emerald-300/15 p-5 text-white shadow-2xl shadow-black/20 sm:p-7">
        <div className="grid items-end gap-6 xl:grid-cols-[1.2fr_0.8fr]">
          <div>
            <div className="flex flex-wrap items-center gap-2">
              <span className="inline-flex items-center gap-2 rounded-full border border-rose-300/20 bg-rose-300/10 px-3 py-1.5 text-[10px] font-semibold uppercase tracking-[0.2em] text-rose-200">
                <span className="size-1.5 animate-pulse rounded-full bg-rose-400" />
                {diagnostics.liveRows} live now
              </span>
              <span className="rounded-full border border-emerald-300/20 bg-emerald-300/10 px-3 py-1.5 text-[10px] font-semibold uppercase tracking-[0.2em] text-emerald-200">
                Hard Rock + TTLElite fair
              </span>
            </div>
            <h2 className="mt-5 max-w-3xl text-3xl font-semibold tracking-[-0.05em] sm:text-4xl">
              The match room built for the decision, not the noise.
            </h2>
            <p className="mt-3 max-w-3xl text-sm leading-6 text-slate-300">
              Live matches stay first, then the schedule runs chronologically. Every market shows the price you can take,
              the price our model believes is fair, and the evidence that makes the difference credible—or says to pass.
            </p>
          </div>
          <div className="grid grid-cols-3 gap-2">
            <HeroMetric label="Markets" value={String(diagnostics.totalRows)} />
            <HeroMetric label="Value reads" value={String(diagnostics.recommendedRows)} />
            <HeroMetric label="Open picks" value={String(session?.openBets ?? 0)} />
          </div>
        </div>
      </section>

      <div className="mt-5">
        <SessionRibbon />
      </div>

      {error ? (
        <div className="mt-5 inline-flex items-center gap-2 rounded-[18px] border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800" role="alert">
          <AlertTriangle aria-hidden="true" className="size-4" />
          <span>{error}</span>
        </div>
      ) : null}

      <section className="mt-5 grid items-start gap-5 xl:grid-cols-[minmax(420px,0.78fr)_minmax(600px,1.22fr)]">
        <Card className="xl:max-h-[calc(100vh-2rem)] xl:overflow-hidden">
          <CardHeader>
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <Badge variant="accent" className="w-fit"><Layers3 className="mr-1 size-3" /> Market room</Badge>
                <CardTitle className="mt-2">Live first. Then by time.</CardTitle>
              </div>
              <span className="rounded-full bg-emerald-100 px-3 py-1 font-mono text-xs font-bold text-emerald-800">
                {refreshing ? 'Updating…' : 'Live data'}
              </span>
            </div>
            <CardDescription>Select any matchup to turn the market into a full bettor read.</CardDescription>
          </CardHeader>
          <CardContent className="grid gap-4 xl:min-h-0">
            <div className="grid grid-cols-4 gap-1 rounded-[16px] bg-slate-100 p-1">
              {(['ALL', 'LIVE', 'VALUE', 'UPCOMING'] as const).map((filter) => (
                <button
                  className={cn(
                    'rounded-xl px-2 py-2 text-[10px] font-bold uppercase tracking-[0.12em] transition',
                    marketFilter === filter ? 'bg-white text-emerald-800 shadow-sm' : 'text-slate-600 hover:text-slate-800',
                  )}
                  key={filter}
                  onClick={() => setMarketFilter(filter)}
                  type="button"
                >
                  {filter}
                </button>
              ))}
            </div>

            <div className="flex flex-col gap-2 sm:flex-row">
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
            </div>

            <button
              className="flex items-center gap-2 justify-self-start text-xs font-semibold text-[var(--ink-muted)]"
              type="button"
              onClick={() => setIncludeUnresolved((value) => !value)}
            >
              <Filter className="size-3.5" />
              {includeUnresolved ? 'Showing unresolved identities' : 'Only resolved identities'}
            </button>

            <div className="hide-scrollbar grid gap-2 xl:max-h-[calc(100vh-312px)] xl:overflow-y-auto xl:pr-1">
              {filteredRows.map((row) => (
                <MarketWatchCard
                  key={rowKey(row)}
                  row={row}
                  myPick={myPickByRow.get(rowKey(row)) ?? null}
                  selected={selectedRow ? rowKey(selectedRow) === rowKey(row) : false}
                  onSelect={() => setSelectedKey(rowKey(row))}
                />
              ))}
            </div>

            {!loading && filteredRows.length === 0 ? (
              <div className="rounded-[20px] border border-dashed border-[var(--line-strong)] p-5 text-sm text-[var(--ink-muted)]">
                No live board rows match the current filters.
              </div>
            ) : null}
          </CardContent>
        </Card>

        <div className="grid content-start gap-5 xl:sticky xl:top-5">
          {selectedRow ? (
            <BettorMatchupPanel
              analysis={matchupIntel}
              bet={myPickByRow.get(rowKey(selectedRow)) ?? null}
              detailHref={`/user/matches/${encodeURIComponent(matchDetailKey(selectedRow))}/prediction`}
              history={selectedHistory}
              intelError={intelError}
              intelLoading={intelLoading}
              row={selectedRow}
            />
          ) : (
            <Card>
              <CardContent className="p-6 text-sm text-[var(--ink-muted)]">Waiting for board rows.</CardContent>
            </Card>
          )}
        </div>
      </section>
    </V3Shell>
  )
}

function MarketWatchCard({
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
  const sideP1 = row.suggestedSide === row.player1Name
  const sideP2 = row.suggestedSide === row.player2Name
  const suggestedBook = sideP1 ? row.americanOddsPlayer1 : sideP2 ? row.americanOddsPlayer2 : null
  const suggestedFair = sideP1 ? row.modelFairAmericanOddsPlayer1 : sideP2 ? row.modelFairAmericanOddsPlayer2 : null
  const bookMargin = calculateBookMargin(row.decimalOddsPlayer1, row.decimalOddsPlayer2)

  return (
    <button
      aria-pressed={selected}
      className={cn(
        'group w-full rounded-[22px] border p-4 text-left transition',
        selected
          ? 'border-emerald-400 bg-emerald-50 shadow-[0_16px_36px_-28px_rgba(5,150,105,0.8)]'
          : 'border-[var(--line)] bg-white/60 hover:border-emerald-300 hover:bg-white',
      )}
      onClick={onSelect}
      type="button"
    >
      <div className="flex items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <StatusPill live={row.live} />
          {row.recommended ? <span className="inline-flex items-center gap-1 rounded-full bg-emerald-100 px-2 py-1 text-[10px] font-bold uppercase tracking-[0.14em] text-emerald-800"><Flame className="size-3" />Value</span> : null}
          {myPick ? <span className="inline-flex items-center gap-1 text-[10px] font-bold uppercase tracking-[0.14em] text-amber-700"><Star className="size-3" />My pick</span> : null}
        </div>
        <span className="inline-flex items-center gap-1 text-[10px] font-semibold uppercase tracking-[0.12em] text-[var(--ink-muted)]">
          <Clock3 className="size-3" />{formatTimeOnly(row.startTimeIso)}
        </span>
      </div>

      <div className="mt-3">
        <div className="grid grid-cols-[1fr_auto] items-center gap-3">
          <div className="min-w-0">
            <p className="truncate font-semibold text-[var(--ink-strong)]">{row.player1Name}</p>
            <p className="mt-1 truncate font-semibold text-[var(--ink-strong)]">{row.player2Name}</p>
          </div>
          {row.liveScore ? (
            <span className="rounded-xl border border-rose-200 bg-rose-50 px-3 py-2 font-mono text-base font-bold text-rose-800">{row.liveScore}</span>
          ) : (
            <span className="font-mono text-xs text-[var(--ink-muted)]">{formatStart(row.startTimeIso)}</span>
          )}
        </div>
        <div className="mt-2 flex items-center justify-between gap-3 text-xs text-[var(--ink-muted)]">
          <p className="truncate">{row.competitionName}</p>
          <span
            className="shrink-0 font-mono font-semibold"
            title="Hard Rock's two-way pricing margin across both sides"
          >
            HR margin {formatPct(bookMargin)}
          </span>
        </div>
      </div>

      <div className="mt-3 grid grid-cols-[1.15fr_0.7fr_0.7fr_0.7fr] items-center gap-2 border-t border-[var(--line)] pt-3">
        <div className="min-w-0">
          <p className="text-[9px] font-semibold uppercase tracking-[0.15em] text-[var(--ink-muted)]">{row.recommended ? 'Model lean' : 'Model status'}</p>
          <p className="mt-1 truncate text-xs font-bold text-[var(--ink-strong)]">{row.suggestedSide ?? 'Pass / watch'}</p>
        </div>
        <CompactPrice label="Hard Rock" value={formatAmerican(suggestedBook)} />
        <CompactPrice label="Our fair" value={formatAmerican(suggestedFair)} />
        <CompactPrice label="Bet edge" value={formatSignedPct(row.suggestedEdge)} accent={(row.suggestedEdge ?? 0) > 0} />
      </div>
    </button>
  )
}

function CompactPrice({ accent = false, label, value }: { accent?: boolean; label: string; value: string }) {
  return (
    <div className="text-right">
      <p className="text-[8px] font-semibold uppercase tracking-[0.12em] text-[var(--ink-muted)]">{label}</p>
      <p className={cn('mt-1 font-mono text-xs font-bold', accent ? 'text-emerald-700' : 'text-[var(--ink-strong)]')}>{value}</p>
    </div>
  )
}

function HeroMetric({ label, value }: { label: string; value: string }) {
  return <div className="rounded-[18px] border border-white/10 bg-white/[0.05] p-3"><p className="font-mono text-xl font-bold">{value}</p><p className="mt-1 text-[9px] uppercase tracking-[0.15em] text-slate-400">{label}</p></div>
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

function startTimeValue(value: string | null) {
  if (!value) return Number.POSITIVE_INFINITY
  const timestamp = new Date(value).getTime()
  return Number.isFinite(timestamp) ? timestamp : Number.POSITIVE_INFINITY
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

function formatTimeOnly(value: string | null) {
  if (!value) return 'TBD'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return 'TBD'
  return new Intl.DateTimeFormat('en-US', { hour: 'numeric', minute: '2-digit' }).format(parsed)
}
