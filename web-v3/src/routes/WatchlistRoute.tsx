import { useCallback, useEffect, useMemo, useState } from 'react'
import { Activity, BellRing, RefreshCcw, Star, Trash2, UserRound } from 'lucide-react'
import { Link } from 'react-router-dom'

import { V3Shell } from '@/components/layout/V3Shell'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { fetchLiveBoard } from '@/features/live-studio/api'
import type { LiveOddsRecommendation } from '@/features/live-studio/types'
import { useWatchlist } from '@/features/watchlist/store'
import { cn } from '@/lib/utils'

const REFRESH_MS = 10_000

export function WatchlistRoute() {
  const { items, remove } = useWatchlist()
  const [rows, setRows] = useState<LiveOddsRecommendation[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const load = useCallback(async () => {
    try { setRows(await fetchLiveBoard({ includeUnresolved: true, limit: 100, strategy: 'CONSERVATIVE' })); setError(null) }
    catch (nextError) { setError(nextError instanceof Error ? nextError.message : 'Unable to refresh watchlist markets.') }
    finally { setLoading(false) }
  }, [])
  useEffect(() => {
    void load()
    const interval = window.setInterval(() => void load(), REFRESH_MS)
    return () => window.clearInterval(interval)
  }, [load])
  const matches = items.filter((item) => item.kind === 'MATCH')
  const players = items.filter((item) => item.kind === 'PLAYER')
  const indexed = useMemo(() => new Map(rows.map((row) => [rowKey(row), row])), [rows])
  const activeAlerts = matches.filter((item) => { const row = indexed.get(item.id); return row?.live || row?.recommended }).length

  return <V3Shell title="Watchlist" description="Saved matchups and players stay in this browser. Live state and value alerts refresh without turning any item into a wager." badges={<><Badge variant="accent"><Star className="mr-1 size-3" />{items.length} saved</Badge><Badge>{activeAlerts} active alerts</Badge></>} actions={<Button variant="secondary" disabled={loading} onClick={() => void load()}><RefreshCcw className={cn('size-4', loading && 'animate-spin')} />Refresh</Button>}>
    {error ? <div className="mb-4 rounded-2xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-950">{error}</div> : null}
    <section className="grid gap-5 xl:grid-cols-[1.3fr_0.7fr]">
      <Card><CardHeader><Badge variant="accent" className="w-fit"><BellRing className="mr-1 size-3" /> Match alerts</Badge><CardTitle>The markets you chose to follow</CardTitle><CardDescription>LIVE and VALUE labels come from the current board. Missing means the match is outside the present market window—not that a final score was inferred.</CardDescription></CardHeader><CardContent className="space-y-2">{matches.map((item) => { const row = indexed.get(item.id); return <article className="grid gap-3 rounded-2xl border border-[var(--line)] bg-white/65 p-4 lg:grid-cols-[1fr_auto_auto] lg:items-center" key={item.id}><Link className="min-w-0" to={item.href}><div className="flex flex-wrap items-center gap-2">{row?.live ? <Badge className="border-rose-200 bg-rose-50 text-rose-700">Live</Badge> : <Badge>{row ? 'Upcoming' : 'Outside board'}</Badge>}{row?.recommended ? <Badge variant="accent">Value watch</Badge> : null}</div><p className="mt-2 truncate text-sm font-bold">{item.label}</p><p className="mt-1 truncate text-[10px] text-[var(--ink-muted)]">{item.detail}</p></Link><div className="grid grid-cols-3 gap-4"><Mini label="Score" value={row?.liveScore ?? '—'} /><Mini label="Hard Rock" value={row ? `${american(row.americanOddsPlayer1)} / ${american(row.americanOddsPlayer2)}` : '—'} /><Mini label="Model lean" value={row?.suggestedSide ?? '—'} /></div><button aria-label={`Remove ${item.label}`} className="grid size-9 place-items-center rounded-xl border border-[var(--line)] text-[var(--ink-muted)] hover:border-rose-300 hover:text-rose-700" onClick={() => remove('MATCH', item.id)} type="button"><Trash2 className="size-4" /></button></article> })}{!matches.length ? <Empty text="Star a match from Live to keep it here." /> : null}</CardContent></Card>
      <Card><CardHeader><Badge className="w-fit"><UserRound className="mr-1 size-3" /> Players</Badge><CardTitle>Saved player research</CardTitle><CardDescription>Jump directly into form, rating, matchup, and data-confidence profiles.</CardDescription></CardHeader><CardContent className="space-y-2">{players.map((item) => <div className="flex items-center gap-3 rounded-2xl border border-[var(--line)] bg-white/65 p-3" key={item.id}><Link className="min-w-0 flex-1" to={item.href}><p className="truncate text-sm font-bold">{item.label}</p><p className="mt-1 text-[10px] text-[var(--ink-muted)]">{item.detail}</p></Link><button aria-label={`Remove ${item.label}`} className="grid size-9 place-items-center rounded-xl border border-[var(--line)] text-[var(--ink-muted)] hover:border-rose-300 hover:text-rose-700" onClick={() => remove('PLAYER', item.id)} type="button"><Trash2 className="size-4" /></button></div>)}{!players.length ? <Empty text="Save players from their profiles." /> : null}</CardContent></Card>
    </section>
    <div className="mt-5 flex items-start gap-3 rounded-2xl border border-emerald-200 bg-emerald-50 p-4 text-xs leading-5 text-emerald-950"><Activity className="mt-0.5 size-4 shrink-0" /><p>Watchlist alerts are informational and local to this browser. They do not place wagers, change model policy, or count as research observations.</p></div>
  </V3Shell>
}

function Mini({ label, value }: { label: string; value: string }) { return <div><p className="text-[9px] font-bold uppercase tracking-[0.12em] text-[var(--ink-muted)]">{label}</p><p className="mt-1 max-w-32 truncate font-mono text-xs font-bold">{value}</p></div> }
function Empty({ text }: { text: string }) { return <p className="py-12 text-center text-sm text-[var(--ink-muted)]">{text}</p> }
function american(value: number | null | undefined) { return value == null ? '—' : `${value > 0 ? '+' : ''}${value}` }
function rowKey(row: LiveOddsRecommendation) { return row.suggestedDedupeKey ?? row.matchupKey ?? row.externalEventId ?? `${row.eventName}|${row.player1Name}|${row.player2Name}|${row.startTimeIso ?? ''}` }
