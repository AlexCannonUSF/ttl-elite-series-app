import { useCallback, useEffect, useState } from 'react'
import { Activity, AlertTriangle, CircleDollarSign, Layers3, RefreshCcw, ShieldCheck, Target, TrendingUp } from 'lucide-react'

import { V3Shell } from '@/components/layout/V3Shell'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { fetchLiveSession } from '@/features/live-studio/api'
import type { PaperTradingSession } from '@/features/live-studio/types'
import { fetchResearchFoundation } from '@/features/research/api'
import type { ResearchRunFoundation } from '@/features/research/types'
import { cn } from '@/lib/utils'

const REFRESH_MS = 15_000

export function UserSimulationRoute() {
  const [session, setSession] = useState<PaperTradingSession | null>(null)
  const [foundation, setFoundation] = useState<ResearchRunFoundation | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const load = useCallback(async () => {
    try {
      const nextSession = await fetchLiveSession()
      setSession(nextSession)
      if (nextSession.sessionId) {
        setFoundation(await fetchResearchFoundation(nextSession.sessionId).catch(() => null))
      } else {
        setFoundation(null)
      }
      setError(null)
    } catch (nextError) {
      setError(nextError instanceof Error ? nextError.message : 'Unable to load the simulation.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
    const interval = window.setInterval(() => void load(), REFRESH_MS)
    return () => window.clearInterval(interval)
  }, [load])

  const exposure = session?.exposureMetrics
  return (
    <V3Shell
      title="Simulation"
      description="The official virtual portfolio, open positions, price quality, and risk—all clearly separated from the model’s every-match research benchmark."
      badges={<><Badge variant="accent">No real wagers</Badge><Badge>{session?.status ?? 'Loading'}</Badge></>}
      actions={<Button variant="secondary" onClick={() => void load()} disabled={loading}><RefreshCcw className={cn('size-4', loading && 'animate-spin')} />Refresh</Button>}
    >
      {error ? <div className="mb-4 flex gap-2 rounded-2xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-950"><AlertTriangle className="size-4" />{error}</div> : null}
      <section className="user-hero overflow-hidden rounded-[30px] border border-emerald-300/15 p-5 text-white shadow-2xl shadow-black/20 sm:p-7">
        <div className="grid gap-6 xl:grid-cols-[1.1fr_0.9fr] xl:items-end">
          <div>
            <Badge className="border-emerald-300/20 bg-emerald-300/10 text-emerald-100"><CircleDollarSign className="mr-1 size-3" /> Champion Strict</Badge>
            <h2 className="mt-5 text-4xl font-semibold tracking-[-0.05em] sm:text-5xl">{money(session?.currentBankroll)}</h2>
            <p className="mt-2 text-sm text-slate-300">Virtual bankroll · started at {money(session?.startingBankroll)}</p>
            <div className="mt-6 max-w-2xl">
              <div className="flex justify-between text-xs"><span className="text-slate-400">Open exposure</span><strong>{money(exposure?.openExposure)} of {money(exposure?.openExposureCap)}</strong></div>
              <div className="mt-2 h-2 overflow-hidden rounded-full bg-white/10"><div className="h-full rounded-full bg-emerald-400" style={{ width: `${Math.min(100, exposure?.openExposureUsagePct ?? 0)}%` }} /></div>
            </div>
          </div>
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4 xl:grid-cols-2">
            <DarkMetric label="Realized P&L" value={signedMoney(session?.realizedPnl)} tone={(session?.realizedPnl ?? 0) >= 0 ? 'good' : 'bad'} />
            <DarkMetric label="Official ROI" value={signedPct(session?.roiPct)} tone={(session?.roiPct ?? 0) >= 0 ? 'good' : 'bad'} />
            <DarkMetric label="W–L–Open" value={`${session?.wins ?? 0}–${session?.losses ?? 0}–${session?.openBets ?? 0}`} />
            <DarkMetric label="CLV coverage" value={pct((session?.clvMetrics.coverageRatio ?? 0) * 100)} />
          </div>
        </div>
      </section>

      <Card className="mt-5">
        <CardHeader><Badge variant="accent" className="w-fit"><Layers3 className="mr-1 size-3" /> Parallel simulation</Badge><CardTitle>One match, several accountable strategies</CardTitle><CardDescription>Champion Strict is the official virtual bankroll. Challenger, Discovery, All Model Leans, and Hard Rock are research cohorts on the same underlying matches—not extra independent samples.</CardDescription></CardHeader>
        <CardContent>
          <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
            {(foundation?.portfolios ?? []).map((portfolio) => <PortfolioCard key={portfolio.id} label={portfolio.displayName} role={portfolio.primary ? 'Official simulation' : pretty(portfolio.type)} actioned={portfolio.actioned} resolved={portfolio.resolved} correct={portfolio.correct} accuracy={portfolio.accuracyPct} roi={portfolio.flatStakeRoiPct} pnl={portfolio.flatStakePnl} priced={portfolio.pricedResolved} />)}
            {(foundation?.benchmarks ?? []).map((benchmark) => <PortfolioCard key={benchmark.benchmarkKey} label={pretty(benchmark.benchmarkKey)} role="Research benchmark" actioned={benchmark.evaluations} resolved={benchmark.resolved} correct={benchmark.correct} accuracy={benchmark.accuracyPct} roi={benchmark.flatStakeRoiPct} pnl={benchmark.flatStakePnl} priced={benchmark.pricedResolved} />)}
          </div>
          {!foundation?.portfolios.length ? <Empty text="Parallel portfolios begin with the next frozen decision opportunity. The official simulation above remains available." /> : null}
        </CardContent>
      </Card>

      <section className="mt-5 grid gap-5 xl:grid-cols-[1.25fr_0.75fr]">
        <Card>
          <CardHeader><Badge variant="accent" className="w-fit"><Activity className="mr-1 size-3" /> Open positions</Badge><CardTitle>What the policy is currently risking</CardTitle><CardDescription>Each row uses the captured Hard Rock price and frozen model probability. Stakes are virtual.</CardDescription></CardHeader>
          <CardContent>
            <div className="space-y-2">{(session?.openBetsList ?? []).map((bet) => <div className="rounded-2xl border border-[var(--line)] bg-white/65 p-4" key={bet.id}><div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between"><div className="min-w-0"><div className="flex flex-wrap items-center gap-2"><Badge variant="accent">Open</Badge>{bet.topTrigger ? <Badge>{pretty(bet.topTrigger)}</Badge> : null}</div><p className="mt-2 truncate text-sm font-bold text-[var(--ink-strong)]">{bet.sideName}</p><p className="mt-1 truncate text-xs text-[var(--ink-muted)]">{bet.eventName}</p></div><div className="grid grid-cols-4 gap-4 text-right"><Mini label="Stake" value={money(bet.stake)} /><Mini label="Price" value={american(bet.americanOdds)} /><Mini label="Model" value={pct(bet.modelProbability * 100)} /><Mini label="Edge" value={signedPct(bet.edge * 100)} /></div></div></div>)}{!session?.openBetsList.length ? <Empty text="No open virtual positions. The model can still be grading every match in Results." /> : null}</div>
          </CardContent>
        </Card>
        <div className="space-y-5">
          <Card><CardHeader><Badge className="w-fit"><ShieldCheck className="mr-1 size-3" /> Risk posture</Badge><CardTitle>Portfolio guardrails</CardTitle></CardHeader><CardContent className="space-y-3"><RiskRow label="Exposure remaining" value={money(exposure?.openExposureRemaining)} /><RiskRow label="Open slots used" value={pct(exposure?.concurrentOpenBetUsagePct)} /><RiskRow label="Most exposed player" value={exposure?.mostExposedPlayerName ?? 'None'} /><RiskRow label="Player cap usage" value={pct(exposure?.mostExposedPlayerCapUsagePct)} /><RiskRow label="Trigger near-cap count" value={String(exposure?.triggerNearCapCount ?? 0)} /></CardContent></Card>
          <Card><CardHeader><Badge className="w-fit"><Target className="mr-1 size-3" /> Selection funnel</Badge><CardTitle>Why sample is growing slowly or quickly</CardTitle></CardHeader><CardContent className="space-y-3"><RiskRow label="Rows evaluated" value={integer(session?.decisionTelemetry?.consideredCount)} /><RiskRow label="Official positions" value={integer(session?.decisionTelemetry?.placedCount)} /><RiskRow label="Discovery positions" value={integer(session?.decisionTelemetry?.fallbackPlacedCount)} /><RiskRow label="Placement rate" value={pct(session?.decisionTelemetry?.placementRatePct)} /></CardContent></Card>
        </div>
      </section>
    </V3Shell>
  )
}

function DarkMetric({ label, value, tone }: { label: string; value: string; tone?: 'good' | 'bad' }) { return <div className="rounded-2xl border border-white/10 bg-white/[0.06] p-3"><p className="text-[9px] font-bold uppercase tracking-[0.15em] text-slate-400">{label}</p><p className={cn('mt-2 font-mono text-lg font-bold', tone === 'good' ? 'text-emerald-300' : tone === 'bad' ? 'text-rose-300' : 'text-white')}>{value}</p></div> }
function Mini({ label, value }: { label: string; value: string }) { return <div><p className="text-[9px] font-bold uppercase tracking-[0.12em] text-[var(--ink-muted)]">{label}</p><p className="mt-1 whitespace-nowrap font-mono text-xs font-bold">{value}</p></div> }
function RiskRow({ label, value }: { label: string; value: string }) { return <div className="flex items-center justify-between rounded-xl border border-[var(--line)] bg-white/60 px-3 py-2 text-xs"><span className="text-[var(--ink-muted)]">{label}</span><strong>{value}</strong></div> }
function PortfolioCard({ label, role, actioned, resolved, correct, accuracy, roi, pnl, priced }: { label: string; role: string; actioned: number; resolved: number; correct: number; accuracy: number; roi: number; pnl: number; priced: number }) { return <article className="rounded-2xl border border-[var(--line)] bg-white/65 p-4"><div className="flex items-start justify-between gap-2"><div><p className="text-sm font-bold">{label}</p><p className="mt-1 text-[10px] text-[var(--ink-muted)]">{role}</p></div><Badge variant={role === 'Official simulation' ? 'accent' : 'neutral'}>{actioned} tracked</Badge></div><div className="mt-4 grid grid-cols-2 gap-3"><Mini label="Record" value={`${correct}–${Math.max(0, resolved - correct)}`} /><Mini label="Accuracy" value={pct(accuracy)} /><Mini label="$1 ROI" value={signedPct(roi)} /><Mini label="$1 P&L" value={signedMoney(pnl)} /></div><p className="mt-3 text-[10px] text-[var(--ink-muted)]">{resolved} trusted winners · {priced} with captured price</p></article> }
function Empty({ text }: { text: string }) { return <p className="py-10 text-center text-sm text-[var(--ink-muted)]">{text}</p> }
function money(value: number | null | undefined) { return (value ?? 0).toLocaleString(undefined, { style: 'currency', currency: 'USD' }) }
function signedMoney(value: number | null | undefined) { const amount = value ?? 0; return `${amount >= 0 ? '+' : '−'}${money(Math.abs(amount))}` }
function pct(value: number | null | undefined) { return value == null || !Number.isFinite(value) ? '—' : `${value.toFixed(1)}%` }
function signedPct(value: number | null | undefined) { return value == null || !Number.isFinite(value) ? '—' : `${value >= 0 ? '+' : ''}${value.toFixed(1)}%` }
function american(value: number | null | undefined) { return value == null ? '—' : `${value > 0 ? '+' : ''}${value}` }
function integer(value: number | null | undefined) { return Math.round(value ?? 0).toLocaleString() }
function pretty(value: string) { return value.replaceAll('_', ' ').replace(/\b\w/g, (char) => char.toUpperCase()) }
