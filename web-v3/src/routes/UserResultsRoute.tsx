import { useCallback, useEffect, useState } from 'react'
import { AlertTriangle, BarChart3, CircleCheck, CircleX, RefreshCcw, Target } from 'lucide-react'
import { Link } from 'react-router-dom'

import { V3Shell } from '@/components/layout/V3Shell'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { fetchLiveRunAnalytics, fetchModelCallScorecard } from '@/features/live-studio/api'
import type { LiveRunAnalytics, ModelCallScorecard } from '@/features/live-studio/types'
import type { ModelRun } from '@/features/ml-quality/types'
import { fetchResearchRun, fetchResearchRuns } from '@/features/research/api'
import { cn } from '@/lib/utils'

export function UserResultsRoute() {
  const [scorecard, setScorecard] = useState<ModelCallScorecard | null>(null)
  const [analytics, setAnalytics] = useState<LiveRunAnalytics | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [runs, setRuns] = useState<ModelRun[]>([])
  const [runId, setRunId] = useState<number | null>(null)
  const load = useCallback(async () => {
    try {
      if (runId) {
        const detail = await fetchResearchRun(runId)
        setScorecard(detail.scorecard); setAnalytics(detail.analytics)
      } else {
        const [nextScorecard, nextAnalytics] = await Promise.all([fetchModelCallScorecard(200), fetchLiveRunAnalytics(250)])
        setScorecard(nextScorecard); setAnalytics(nextAnalytics)
      }
      setError(null)
    } catch (nextError) { setError(nextError instanceof Error ? nextError.message : 'Unable to load model results.') }
    finally { setLoading(false) }
  }, [runId])
  useEffect(() => {
    const controller = new AbortController()
    void fetchResearchRuns(100, controller.signal).then((history) => {
      const withEvidence = history.runs.filter((run) => run.modelCalls > 0)
      setRuns(withEvidence)
      setRunId((current) => current ?? withEvidence[0]?.sessionId ?? null)
    }).catch(() => undefined)
    return () => controller.abort()
  }, [])
  useEffect(() => { void load() }, [load])

  return <V3Shell title="Results" description="Every finished winner call—whether or not it became an official paper pick—shown with the captured probability, price, score, and outcome." badges={<><Badge variant="accent">All model calls</Badge><Badge>{analytics?.evidenceLabel ?? 'Collecting'}</Badge></>} actions={<><select aria-label="Results run" className="h-10 max-w-72 rounded-xl border border-white/10 bg-white/[0.06] px-3 text-xs font-semibold text-slate-200" value={runId ?? ''} onChange={(event) => setRunId(Number(event.target.value) || null)}><option className="text-slate-950" value="">Current active run</option>{runs.map((run) => <option className="text-slate-950" key={run.sessionId} value={run.sessionId}>#{run.sessionId} {run.label}</option>)}</select><Button variant="secondary" onClick={() => void load()} disabled={loading}><RefreshCcw className={cn('size-4', loading && 'animate-spin')} />Refresh</Button></>}>
    {error ? <div className="mb-4 flex gap-2 rounded-2xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-950"><AlertTriangle className="size-4" />{error}</div> : null}
    <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-5"><Metric label="Winner record" value={`${scorecard?.correct ?? 0}–${scorecard?.incorrect ?? 0}`} detail={`${scorecard?.awaitingResult ?? 0} awaiting`} /><Metric label="Accuracy" value={pct(scorecard?.accuracyPct)} detail={`Pregame ${pct(scorecard?.pregameAccuracyPct)}`} /><Metric label="$1 record" value={`${scorecard?.flatStakeWins ?? 0}–${scorecard?.flatStakeLosses ?? 0}`} detail="Every priced lean" /><Metric label="$1 ROI" value={signedPct(scorecard?.flatStakeRoiPct)} detail={signedMoney(scorecard?.flatStakeNetProfit)} tone={(scorecard?.flatStakeRoiPct ?? 0) >= 0 ? 'good' : 'bad'} /><Metric label="Brier" value={scorecard?.brierScore?.toFixed(3) ?? '—'} detail={`${analytics?.settledCalls ?? 0}/${analytics?.readinessTarget ?? 100} evidence`} /></section>
    <Card className="mt-5"><CardHeader><Badge variant="accent" className="w-fit"><BarChart3 className="mr-1 size-3" /> Settled tape</Badge><CardTitle>What the model said, and what happened</CardTitle><CardDescription>Paper-pick status is included, but all eligible winner calls count toward model evaluation.</CardDescription></CardHeader><CardContent><div className="space-y-2">{(scorecard?.recentResults ?? []).map((result) => <Link className="grid gap-4 rounded-2xl border border-[var(--line)] bg-white/65 p-4 transition hover:border-emerald-300 hover:bg-white lg:grid-cols-[minmax(220px,1fr)_repeat(5,minmax(90px,auto))] lg:items-center" key={result.callId} to={`/user/tracking/${result.callId}`}><div className="min-w-0"><div className="flex items-center gap-2">{result.outcome === 'CORRECT' ? <CircleCheck className="size-4 text-emerald-600" /> : result.outcome === 'INCORRECT' ? <CircleX className="size-4 text-rose-600" /> : <Target className="size-4 text-slate-500" />}<p className="truncate text-sm font-bold">{result.eventName}</p></div><p className="mt-1 truncate text-xs text-[var(--ink-muted)]">{result.competitionName ?? 'TT Elite Series'} · {result.matchDateIso ?? result.capturedAt}</p></div><ResultCell label="Model lean" value={result.predictedWinnerName ?? 'No lean'} /><ResultCell label="Probability" value={result.modelProbability == null ? '—' : pct(result.modelProbability * 100)} /><ResultCell label="Hard Rock" value={american(result.hardRockAmericanOdds)} /><ResultCell label="Final" value={result.score || '—'} /><div><p className="text-[9px] font-bold uppercase tracking-[0.12em] text-[var(--ink-muted)]">Outcome</p><Badge className="mt-1" variant={result.outcome === 'CORRECT' ? 'accent' : 'neutral'}>{pretty(result.outcome)}</Badge></div></Link>)}{!scorecard?.recentResults.length ? <p className="py-12 text-center text-sm text-[var(--ink-muted)]">Resolved calls will appear here as trusted results arrive.</p> : null}</div></CardContent></Card>
  </V3Shell>
}

function Metric({ label, value, detail, tone }: { label: string; value: string; detail: string; tone?: 'good' | 'bad' }) { return <div className="rounded-[22px] border border-[var(--line)] bg-white/65 p-4"><p className="text-[10px] font-bold uppercase tracking-[0.16em] text-[var(--ink-muted)]">{label}</p><p className={cn('mt-3 font-mono text-2xl font-bold', tone === 'good' ? 'text-emerald-700' : tone === 'bad' ? 'text-rose-700' : 'text-[var(--ink-strong)]')}>{value}</p><p className="mt-2 text-[10px] text-[var(--ink-muted)]">{detail}</p></div> }
function ResultCell({ label, value }: { label: string; value: string }) { return <div><p className="text-[9px] font-bold uppercase tracking-[0.12em] text-[var(--ink-muted)]">{label}</p><p className="mt-1 text-xs font-semibold">{value}</p></div> }
function pct(value: number | null | undefined) { return value == null || !Number.isFinite(value) ? '—' : `${value.toFixed(1)}%` }
function signedPct(value: number | null | undefined) { return value == null || !Number.isFinite(value) ? '—' : `${value >= 0 ? '+' : ''}${value.toFixed(1)}%` }
function signedMoney(value: number | null | undefined) { const amount = value ?? 0; return `${amount >= 0 ? '+' : '−'}$${Math.abs(amount).toFixed(2)}` }
function american(value: number | null | undefined) { return value == null ? '—' : `${value > 0 ? '+' : ''}${value}` }
function pretty(value: string) { return value.replaceAll('_', ' ').replace(/\b\w/g, (char) => char.toUpperCase()) }
