import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ArrowRight, History, Loader2, RefreshCcw, Target } from 'lucide-react'
import { Link } from 'react-router-dom'

import { V3Shell } from '@/components/layout/V3Shell'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { BetTable } from '@/features/live-studio/BetTable'
import { SessionRibbon } from '@/features/live-studio/SessionRibbon'
import { fetchLiveSession } from '@/features/live-studio/api'
import type { PaperTradeBet, PaperTradingSession } from '@/features/live-studio/types'
import { cn } from '@/lib/utils'

const REFRESH_INTERVAL_MS = 5000

export function HomeRoute() {
  const [session, setSession] = useState<PaperTradingSession | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [now, setNow] = useState(() => new Date())
  const mountedRef = useRef(true)

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  const load = useCallback(async (background: boolean) => {
    if (mountedRef.current) {
      if (background) setRefreshing(true)
      else setLoading(true)
    }
    try {
      const next = await fetchLiveSession()
      if (!mountedRef.current) return
      setSession(next)
      setError(null)
    } catch (next) {
      if (!mountedRef.current) return
      setError(next instanceof Error ? next.message : 'Unable to load the live session right now.')
    } finally {
      if (!mountedRef.current) return
      if (background) setRefreshing(false)
      else setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load(false)
    const interval = window.setInterval(() => {
      setNow(new Date())
      void load(true)
    }, REFRESH_INTERVAL_MS)
    return () => window.clearInterval(interval)
  }, [load])

  const upcomingPicks = useMemo(() => {
    const open = (session?.openBetsList ?? []).slice().sort(byStartTimeAsc)
    return open
  }, [session])

  const settledBets = useMemo(() => {
    const all = session?.recentBets ?? []
    return all.filter(isSettled).slice().sort(bySettledDesc)
  }, [session])

  return (
    <V3Shell
      title="Live Studio"
      description="Live picks, recent results, and session health. Every line on this page is live data from the running paper-trade session."
      badges={
        <>
          <Badge variant="accent">Live</Badge>
          <Badge>{session ? `${session.openBets} open` : '—'}</Badge>
          <Badge>{session ? `${session.wins}W / ${session.losses}L` : '—'}</Badge>
          <Badge>Refresh 5s</Badge>
        </>
      }
      actions={
        <Button variant="secondary" onClick={() => void load(true)} disabled={loading || refreshing}>
          <RefreshCcw className={cn('size-4', refreshing && 'animate-spin')} />
          Refresh now
        </Button>
      }
    >
      <SessionRibbon />

      {error ? (
        <div role="alert" className="mt-5 rounded-[18px] border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
          {error}
        </div>
      ) : null}

      <Card className="mt-5">
        <CardHeader>
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <Badge variant="accent" className="w-fit">
                <Target className="size-3" />
                Upcoming picks
              </Badge>
              <CardTitle className="mt-2">Live picks the model has placed</CardTitle>
              <CardDescription>
                Open paper-trade bets, sorted by start time. The row trigger column shows the dominant feature behind the pick.
              </CardDescription>
            </div>
            <Button variant="ghost" asChild>
              <Link to="/live-board">
                Open Live Board
                <ArrowRight className="size-4" />
              </Link>
            </Button>
          </div>
        </CardHeader>
        <CardContent>
          {loading && !session ? (
            <LoadingHint />
          ) : (
            <BetTable
              bets={upcomingPicks}
              variant="upcoming"
              emptyLabel="No open picks right now — the policy is waiting for the next qualifying matchup."
              now={now}
            />
          )}
        </CardContent>
      </Card>

      <Card className="mt-5">
        <CardHeader>
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <Badge className="w-fit">
                <History className="size-3" />
                Recent results
              </Badge>
              <CardTitle className="mt-2">Settled bets with outcome and P&L</CardTitle>
              <CardDescription>
                The last {settledBets.length} settled bets across this session. Voided bets are the Phase 06 Stream-CV gate refusing to close on shaky evidence.
              </CardDescription>
            </div>
            <Button variant="ghost" asChild>
              <Link to="/ops/diffs">
                Open Settlement Diffs
                <ArrowRight className="size-4" />
              </Link>
            </Button>
          </div>
        </CardHeader>
        <CardContent>
          {loading && !session ? (
            <LoadingHint />
          ) : (
            <BetTable
              bets={settledBets}
              variant="settled"
              emptyLabel="No settled bets yet — the session is still warming up."
              now={now}
            />
          )}
        </CardContent>
      </Card>

      <Card className="mt-5">
        <CardHeader>
          <Badge variant="accent" className="w-fit">
            Quick links
          </Badge>
          <CardTitle>Common next moves</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-wrap gap-3">
          <Button asChild>
            <Link to="/live-board">
              Live Board
              <ArrowRight className="size-4" />
            </Link>
          </Button>
          <Button variant="secondary" asChild>
            <Link to="/ops/scrape">
              Run a Scrape
              <ArrowRight className="size-4" />
            </Link>
          </Button>
          <Button variant="ghost" asChild>
            <Link to="/ops">
              Ops Console
              <ArrowRight className="size-4" />
            </Link>
          </Button>
          <Button variant="ghost" asChild>
            <Link to="/ops/diffs">
              Settlement Diffs
              <ArrowRight className="size-4" />
            </Link>
          </Button>
          <Button variant="ghost" asChild>
            <Link to="/review">
              Review Queue
              <ArrowRight className="size-4" />
            </Link>
          </Button>
          <Button variant="ghost" asChild>
            <Link to="/ml/quality">
              ML Quality
              <ArrowRight className="size-4" />
            </Link>
          </Button>
        </CardContent>
      </Card>
    </V3Shell>
  )
}

function LoadingHint() {
  return (
    <div className="flex items-center gap-3 rounded-[18px] border border-dashed border-[var(--line-strong)] bg-[rgba(255,255,255,0.58)] p-6 text-sm text-[var(--ink-muted)]">
      <Loader2 className="size-4 animate-spin" />
      Loading live session…
    </div>
  )
}

function isSettled(bet: PaperTradeBet) {
  const status = (bet.status ?? '').toUpperCase()
  return status === 'WON' || status === 'LOST' || status === 'VOIDED' || status === 'PUSHED'
}

function byStartTimeAsc(a: PaperTradeBet, b: PaperTradeBet) {
  const ax = a.startTimeIso ? new Date(a.startTimeIso).getTime() : Number.POSITIVE_INFINITY
  const bx = b.startTimeIso ? new Date(b.startTimeIso).getTime() : Number.POSITIVE_INFINITY
  return ax - bx
}

function bySettledDesc(a: PaperTradeBet, b: PaperTradeBet) {
  const ax = (a.settledAt ?? a.placedAt) ? new Date((a.settledAt ?? a.placedAt) as string).getTime() : 0
  const bx = (b.settledAt ?? b.placedAt) ? new Date((b.settledAt ?? b.placedAt) as string).getTime() : 0
  return bx - ax
}
