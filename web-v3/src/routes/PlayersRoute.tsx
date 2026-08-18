import { useEffect, useMemo, useState } from 'react'
import { ArrowLeft, ArrowRight, Database, GitCompareArrows, Search, ShieldCheck, Star, Swords, TrendingUp, UserRound } from 'lucide-react'
import { Link, useParams, useSearchParams } from 'react-router-dom'

import { V3Shell } from '@/components/layout/V3Shell'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import type { MatchupAnalysis } from '@/features/live-studio/types'
import { fetchPlayerMatches, fetchPlayerMatchup, fetchPlayers, fetchPlayerStatistics } from '@/features/players/api'
import type { Player, PlayerMatch, PlayerStatistics } from '@/features/players/types'
import { useWatchlist } from '@/features/watchlist/store'
import { cn } from '@/lib/utils'

type PlayerRow = Player & { statistics: PlayerStatistics | null }

export function PlayersRoute() {
  const { playerId } = useParams()
  const selectedId = playerId ? Number(playerId) : null
  const [params] = useSearchParams()
  const compareId = numberParam(params.get('compare'))
  const [players, setPlayers] = useState<Player[]>([])
  const [statistics, setStatistics] = useState<PlayerStatistics[]>([])
  const [matches, setMatches] = useState<PlayerMatch[]>([])
  const [matchup, setMatchup] = useState<MatchupAnalysis | null>(null)
  const [query, setQuery] = useState('')
  const [sort, setSort] = useState<'MATCHES' | 'NAME' | 'WIN_PCT'>('MATCHES')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const { items: watchlist, toggle: toggleWatchlist } = useWatchlist()

  useEffect(() => {
    const controller = new AbortController()
    Promise.all([fetchPlayers(controller.signal), fetchPlayerStatistics(controller.signal)])
      .then(([nextPlayers, nextStatistics]) => { setPlayers(nextPlayers); setStatistics(nextStatistics); setError(null) })
      .catch((nextError) => { if (!controller.signal.aborted) setError(nextError instanceof Error ? nextError.message : 'Unable to load players.') })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [])

  useEffect(() => {
    if (!selectedId) { setMatches([]); return }
    const controller = new AbortController()
    fetchPlayerMatches(selectedId, 50, controller.signal)
      .then(setMatches)
      .catch((nextError) => { if (!controller.signal.aborted) setError(nextError instanceof Error ? nextError.message : 'Unable to load player matches.') })
    return () => controller.abort()
  }, [selectedId])

  useEffect(() => {
    if (!selectedId || !compareId || selectedId === compareId) { setMatchup(null); return }
    const controller = new AbortController()
    fetchPlayerMatchup(selectedId, compareId, controller.signal)
      .then(setMatchup)
      .catch((nextError) => { if (!controller.signal.aborted) setError(nextError instanceof Error ? nextError.message : 'Unable to compare players.') })
    return () => controller.abort()
  }, [compareId, selectedId])

  const rows = useMemo(() => {
    const stats = new Map(statistics.map((item) => [item.playerId, item]))
    const filtered: PlayerRow[] = players
      .filter((player) => player.fullName.toLowerCase().includes(query.trim().toLowerCase()))
      .map((player) => ({ ...player, statistics: stats.get(player.id) ?? null }))
    return filtered.sort((left, right) => sort === 'NAME'
      ? left.fullName.localeCompare(right.fullName)
      : sort === 'WIN_PCT'
        ? (right.statistics?.winPct ?? 0) - (left.statistics?.winPct ?? 0)
        : (right.statistics?.matches ?? 0) - (left.statistics?.matches ?? 0))
  }, [players, query, sort, statistics])

  const selected = players.find((player) => player.id === selectedId) ?? null
  const selectedStats = statistics.find((item) => item.playerId === selectedId) ?? null

  return <V3Shell title={selected ? selected.fullName : 'Players'} description={selected ? 'Complete form, ratings, matchup comparisons, and recent results with evidence depth always visible.' : 'Search the complete player database, inspect sample depth, and open any player for match-by-match intelligence.'} badges={<><Badge variant="accent"><Database className="mr-1 size-3" /> {loading ? 'Loading players' : `${players.length.toLocaleString()} players`}</Badge>{selected ? <Badge>{selectedStats?.matches ?? 0} matches</Badge> : null}</>}>
    {error ? <div className="mb-4 rounded-2xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-950">{error}</div> : null}
    {selected ? <PlayerProfile player={selected} statistics={selectedStats} matches={matches} matchup={matchup} allPlayers={players} compareId={compareId} watched={watchlist.some((item) => item.kind === 'PLAYER' && item.id === String(selected.id))} onToggleWatch={() => toggleWatchlist({ id: String(selected.id), kind: 'PLAYER', label: selected.fullName, detail: `${selectedStats?.matches ?? 0} recorded matches`, href: `/user/players/${selected.id}` })} /> : <PlayerExplorer loading={loading} rows={rows} query={query} setQuery={setQuery} sort={sort} setSort={setSort} />}
  </V3Shell>
}

function PlayerExplorer({ loading, rows, query, setQuery, sort, setSort }: { loading: boolean; rows: PlayerRow[]; query: string; setQuery: (value: string) => void; sort: 'MATCHES' | 'NAME' | 'WIN_PCT'; setSort: (value: 'MATCHES' | 'NAME' | 'WIN_PCT') => void }) {
  return <div className="grid gap-5 xl:grid-cols-[0.72fr_1.28fr]"><section className="user-hero rounded-[30px] border border-emerald-300/15 p-6 text-white"><Badge className="border-emerald-300/20 bg-emerald-300/10 text-emerald-100"><UserRound className="mr-1 size-3" /> Player intelligence</Badge><h2 className="mt-5 text-4xl font-semibold tracking-[-0.05em]">Know the player behind the price.</h2><p className="mt-4 max-w-xl text-sm leading-6 text-slate-300">Open form, schedule strength, H2H history, rating systems, and the model’s probability. Thin histories stay labeled instead of being presented as confidence.</p><div className="mt-7 grid grid-cols-3 gap-2"><HeroMini label="Database" value={loading ? '…' : rows.length.toLocaleString()} /><HeroMini label="With 50+" value={loading ? '…' : String(rows.filter((row) => (row.statistics?.matches ?? 0) >= 50).length)} /><HeroMini label="Search" value={loading ? 'Loading' : 'Instant'} /></div></section><Card><CardHeader><div className="grid gap-2 sm:grid-cols-[1fr_auto]"><label className="relative"><Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-[var(--ink-muted)]" /><input aria-label="Search players" className="h-11 w-full rounded-xl border border-[var(--line)] bg-white/70 pl-9 pr-3 text-sm outline-none focus:border-emerald-400" disabled={loading} onChange={(event) => setQuery(event.target.value)} placeholder={loading ? 'Loading player database…' : 'Search player name'} value={query} /></label><select aria-label="Sort players" className="h-11 rounded-xl border border-[var(--line)] bg-white/70 px-3 text-xs font-semibold" disabled={loading} onChange={(event) => setSort(event.target.value as typeof sort)} value={sort}><option value="MATCHES">Most matches</option><option value="WIN_PCT">Highest win rate</option><option value="NAME">Name</option></select></div></CardHeader><CardContent><div className="max-h-[680px] space-y-2 overflow-y-auto pr-1">{loading ? <div className="grid min-h-72 place-items-center rounded-2xl border border-dashed border-[var(--line-strong)] bg-white/45 text-sm text-[var(--ink-muted)]">Loading canonical players and match statistics…</div> : rows.slice(0, 300).map((row) => <Link className="grid grid-cols-[1fr_repeat(3,74px)_auto] items-center gap-3 rounded-2xl border border-[var(--line)] bg-white/60 p-3 transition hover:border-emerald-300 hover:bg-white" key={row.id} to={`/user/players/${row.id}`}><div className="min-w-0"><p className="truncate text-sm font-bold">{row.fullName}</p><p className="mt-0.5 text-[10px] text-[var(--ink-muted)]">Player #{row.id}</p></div><Mini label="Matches" value={integer(row.statistics?.matches)} /><Mini label="Record" value={`${row.statistics?.wins ?? 0}-${row.statistics?.losses ?? 0}`} /><Mini label="Win rate" value={pct(row.statistics?.winPct)} /><ArrowRight className="size-4 text-[var(--ink-muted)]" /></Link>)}</div></CardContent></Card></div>
}

function PlayerProfile({ player, statistics, matches, matchup, allPlayers, compareId, watched, onToggleWatch }: { player: Player; statistics: PlayerStatistics | null; matches: PlayerMatch[]; matchup: MatchupAnalysis | null; allPlayers: Player[]; compareId: number | null; watched: boolean; onToggleWatch: () => void }) {
  const last10 = matches.filter((match) => match.complete).slice(0, 10)
  const wins10 = last10.filter((match) => match.winnerPlayerId === player.id).length
  const opponent = allPlayers.find((item) => item.id === compareId)
  return <div className="space-y-5"><div className="flex items-center justify-between gap-3"><Link className="inline-flex items-center gap-2 text-xs font-bold text-[var(--ink-muted)] hover:text-[var(--ink-strong)]" to="/user/players"><ArrowLeft className="size-4" />All players</Link><button aria-pressed={watched} className={cn('inline-flex items-center gap-2 rounded-xl border px-3 py-2 text-xs font-bold transition', watched ? 'border-amber-300 bg-amber-50 text-amber-800' : 'border-[var(--line)] bg-white/70 text-[var(--ink-muted)] hover:border-amber-300')} onClick={onToggleWatch} type="button"><Star className={cn('size-4', watched && 'fill-current')} />{watched ? 'Watching' : 'Add to watchlist'}</button></div><section className="user-hero rounded-[30px] border border-emerald-300/15 p-6 text-white"><div className="grid gap-6 xl:grid-cols-[1fr_auto] xl:items-end"><div><Badge className="border-emerald-300/20 bg-emerald-300/10 text-emerald-100"><UserRound className="mr-1 size-3" /> Player #{player.id}</Badge><h2 className="mt-4 text-4xl font-semibold tracking-[-0.05em]">{player.fullName}</h2><p className="mt-3 text-sm text-slate-300">Historical database performance; ratings and model probabilities appear when you choose a comparison.</p></div><div className="grid grid-cols-4 gap-2"><HeroMini label="Record" value={`${statistics?.wins ?? 0}-${statistics?.losses ?? 0}`} /><HeroMini label="Win rate" value={pct(statistics?.winPct)} /><HeroMini label="Last 10" value={`${wins10}-${last10.length - wins10}`} /><HeroMini label="Sample" value={sampleLabel(statistics?.matches ?? 0)} /></div></div></section><Card><CardHeader><Badge variant="accent" className="w-fit"><GitCompareArrows className="mr-1 size-3" /> Matchup laboratory</Badge><CardTitle>Compare against any opponent</CardTitle><CardDescription>The comparison uses ratings, recent 10 and 50, H2H, uncertainty, and factor contributions already used by the model.</CardDescription></CardHeader><CardContent><label className="block"><span className="mb-2 block text-[10px] font-bold uppercase tracking-[0.14em] text-[var(--ink-muted)]">Opponent</span><select className="h-11 w-full max-w-lg rounded-xl border border-[var(--line)] bg-white/70 px-3 text-sm" defaultValue={compareId ?? ''} onChange={(event) => { const id = Number(event.target.value); window.location.assign(id ? `/user/players/${player.id}?compare=${id}` : `/user/players/${player.id}`) }}><option value="">Choose an opponent</option>{allPlayers.filter((item) => item.id !== player.id).map((item) => <option key={item.id} value={item.id}>{item.fullName}</option>)}</select></label>{matchup ? <MatchupPanel matchup={matchup} /> : opponent ? <p className="mt-4 text-sm text-[var(--ink-muted)]">Loading comparison with {opponent.fullName}…</p> : null}</CardContent></Card><section className="grid gap-5 xl:grid-cols-[1.25fr_0.75fr]"><Card><CardHeader><Badge className="w-fit"><Swords className="mr-1 size-3" /> Recent matches</Badge><CardTitle>Last 50 recorded results</CardTitle><CardDescription>Every row shows the opponent, score, date, and source-backed outcome.</CardDescription></CardHeader><CardContent><div className="space-y-2">{matches.map((match) => { const other = match.player1.id === player.id ? match.player2 : match.player1; const won = match.winnerPlayerId === player.id; return <div className="grid grid-cols-[auto_1fr_auto_auto] items-center gap-3 rounded-2xl border border-[var(--line)] bg-white/60 p-3" key={match.id}><span className={cn('grid size-8 place-items-center rounded-xl text-xs font-black', won ? 'bg-emerald-100 text-emerald-800' : 'bg-rose-100 text-rose-800')}>{won ? 'W' : 'L'}</span><div><p className="text-xs font-bold">{other.fullName}</p><p className="mt-0.5 text-[10px] text-[var(--ink-muted)]">{match.date}</p></div><span className="font-mono text-xs font-bold">{score(match)}</span><Link className="text-xs font-bold text-emerald-700" to={`/user/players/${other.id}?compare=${player.id}`}>Compare</Link></div> })}{!matches.length ? <p className="py-8 text-center text-sm text-[var(--ink-muted)]">No match history recorded yet.</p> : null}</div></CardContent></Card><Card><CardHeader><Badge className="w-fit"><ShieldCheck className="mr-1 size-3" /> Data confidence</Badge><CardTitle>What this profile can support</CardTitle></CardHeader><CardContent className="space-y-3"><Confidence label="Directional form" ready={(statistics?.matches ?? 0) >= 10} detail={`${Math.min(10, statistics?.matches ?? 0)} of 10 minimum`} /><Confidence label="Stable recent baseline" ready={(statistics?.matches ?? 0) >= 50} detail={`${Math.min(50, statistics?.matches ?? 0)} of 50 target`} /><Confidence label="Profile identity" ready detail="Canonical internal player ID" /><p className="rounded-xl border border-amber-200 bg-amber-50 p-3 text-xs leading-5 text-amber-950">Biographical facts are intentionally absent until a sourced profile provider is connected. Match-derived evidence is not mixed with unsourced metadata.</p></CardContent></Card></section></div>
}

function MatchupPanel({ matchup }: { matchup: MatchupAnalysis }) { return <div className="mt-5 space-y-4"><div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4"><ComparisonMetric label={matchup.player1.fullName} value={pct(matchup.player1Probability.probability * 100)} detail={`Fair ${american(matchup.player1Probability.americanOdds)}`} /><ComparisonMetric label={matchup.player2.fullName} value={pct(matchup.player2Probability.probability * 100)} detail={`Fair ${american(matchup.player2Probability.americanOdds)}`} /><ComparisonMetric label="H2H" value={`${matchup.headToHead.player1Wins}-${matchup.headToHead.player2Wins}`} detail={`${matchup.headToHead.totalMatches} matches`} /><ComparisonMetric label="Last 10 H2H" value={`${matchup.recentHeadToHead.player1Wins}-${matchup.recentHeadToHead.player2Wins}`} detail={`${matchup.recentHeadToHead.matches} recorded`} /></div><div className="grid gap-3 lg:grid-cols-2"><PlayerEvidence name={matchup.player1.fullName} form10={matchup.player1Form} form50={matchup.player1Last50} ratings={matchup.player1Ratings} /><PlayerEvidence name={matchup.player2.fullName} form10={matchup.player2Form} form50={matchup.player2Last50} ratings={matchup.player2Ratings} /></div><div className="rounded-2xl border border-emerald-200 bg-emerald-50 p-4 text-sm leading-6 text-emerald-950"><strong>Model read:</strong> {matchup.explanation}</div></div> }
function PlayerEvidence({ name, form10, form50, ratings }: { name: string; form10: MatchupAnalysis['player1Form']; form50: MatchupAnalysis['player1Last50']; ratings: MatchupAnalysis['player1Ratings'] }) { return <div className="rounded-2xl border border-[var(--line)] bg-white/60 p-4"><p className="text-sm font-bold">{name}</p><div className="mt-3 grid grid-cols-3 gap-3"><Mini label="Last 10" value={`${form10.recentWins}-${form10.recentMatches - form10.recentWins}`} /><Mini label="Last 50" value={`${form50.recentWins}-${form50.recentMatches - form50.recentWins}`} /><Mini label="Elo" value={ratings.elo.toFixed(0)} /><Mini label="Glicko" value={ratings.glicko.toFixed(0)} /><Mini label="TrueSkill" value={ratings.trueSkill2.toFixed(1)} /><Mini label="Agreement" value={pct(ratings.stability * 100)} /></div></div> }
function ComparisonMetric({ label, value, detail }: { label: string; value: string; detail: string }) { return <div className="rounded-2xl border border-[var(--line)] bg-white/60 p-3"><p className="truncate text-[10px] font-bold uppercase tracking-[0.12em] text-[var(--ink-muted)]">{label}</p><p className="mt-2 font-mono text-xl font-bold">{value}</p><p className="mt-1 text-[10px] text-[var(--ink-muted)]">{detail}</p></div> }
function Confidence({ label, ready, detail }: { label: string; ready: boolean; detail: string }) { return <div className="flex items-center justify-between gap-3 rounded-xl border border-[var(--line)] bg-white/60 p-3"><div><p className="text-xs font-bold">{label}</p><p className="mt-1 text-[10px] text-[var(--ink-muted)]">{detail}</p></div><Badge variant={ready ? 'accent' : 'neutral'}>{ready ? 'Ready' : 'Thin'}</Badge></div> }
function HeroMini({ label, value }: { label: string; value: string }) { return <div className="rounded-2xl border border-white/10 bg-white/[0.06] p-3"><p className="text-[9px] font-bold uppercase tracking-[0.15em] text-slate-400">{label}</p><p className="mt-2 font-mono text-lg font-bold text-white">{value}</p></div> }
function Mini({ label, value }: { label: string; value: string }) { return <div><p className="text-[9px] font-bold uppercase tracking-[0.12em] text-[var(--ink-muted)]">{label}</p><p className="mt-1 truncate font-mono text-xs font-bold">{value}</p></div> }
function score(match: PlayerMatch) { if (match.player1SetsWon != null && match.player2SetsWon != null) return `${match.player1SetsWon}-${match.player2SetsWon}`; return match.result ?? '—' }
function sampleLabel(matches: number) { return matches >= 100 ? 'Deep' : matches >= 50 ? 'Useful' : matches >= 10 ? 'Early' : 'Thin' }
function integer(value: number | null | undefined) { return Math.round(value ?? 0).toLocaleString() }
function pct(value: number | null | undefined) {
  if (value == null || !Number.isFinite(value)) return '—'
  const percentage = value > 0 && value <= 1 ? value * 100 : value
  return `${percentage.toFixed(1)}%`
}
function american(value: number | null | undefined) { return value == null ? '—' : `${value > 0 ? '+' : ''}${value}` }
function numberParam(value: string | null) { const number = Number(value); return Number.isFinite(number) && number > 0 ? number : null }
