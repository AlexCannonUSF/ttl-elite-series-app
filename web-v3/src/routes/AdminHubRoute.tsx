import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import {
  Activity,
  AlertTriangle,
  ArrowRight,
  BarChart3,
  BrainCircuit,
  CheckCircle2,
  CircleDollarSign,
  Database,
  DatabaseZap,
  FlaskConical,
  Gauge,
  GitCompareArrows,
  RadioTower,
  RefreshCcw,
  Save,
  ShieldCheck,
  Target,
} from 'lucide-react'
import { Link } from 'react-router-dom'

import { V3Shell } from '@/components/layout/V3Shell'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { fetchLiveSession } from '@/features/live-studio/api'
import type { PaperTradingSession } from '@/features/live-studio/types'
import {
  fetchModelLearningAudit,
  fetchStakingPolicy,
  reloadStakingPolicy,
} from '@/features/ml-quality/api'
import type {
  LearningFactor,
  LearningSegment,
  ModelLearningAudit,
  StakingPolicy,
} from '@/features/ml-quality/types'
import { fetchOpsFeeds, fetchOpsIngest, fetchOpsStreams } from '@/features/ops-feeds/api'
import type { OpsFeedsResponse, OpsIngestResponse, OpsStreamsResponse } from '@/features/ops-feeds/types'
import { fetchScrapeStatus } from '@/features/scrape/api'
import type { ScrapeStatus } from '@/features/scrape/types'
import { cn } from '@/lib/utils'

const REFRESH_MS = 15_000
const CONSERVATIVE_EDGE = 0.055
const AGGRESSIVE_EDGE = 0.03

type AdminSnapshot = {
  audit: ModelLearningAudit | null
  feeds: OpsFeedsResponse | null
  ingest: OpsIngestResponse | null
  policy: StakingPolicy | null
  scrape: ScrapeStatus | null
  session: PaperTradingSession | null
  streams: OpsStreamsResponse | null
  errors: string[]
}

type Scenario = {
  edge: number
  reliability: number
  exposure: number
}

const defaultScenario: Scenario = {
  edge: CONSERVATIVE_EDGE,
  reliability: 0.5,
  exposure: 5,
}

export function AdminHubRoute() {
  const [snapshot, setSnapshot] = useState<AdminSnapshot | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [reloading, setReloading] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [scenario, setScenario] = useState<Scenario>(() => readScenario())
  const mounted = useRef(true)

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

  const load = useCallback(async (background: boolean) => {
    background ? setRefreshing(true) : setLoading(true)
    const results = await Promise.allSettled([
      fetchModelLearningAudit(),
      fetchStakingPolicy(),
      fetchLiveSession(),
      fetchOpsFeeds(),
      fetchOpsIngest(),
      fetchOpsStreams(),
      fetchScrapeStatus(),
    ])
    if (!mounted.current) return

    const labels = ['Learning audit', 'Staking policy', 'Live session', 'Feeds', 'Ingest', 'Streams', 'Scraper']
    const errors = results.flatMap((result, index) =>
      result.status === 'rejected' ? [`${labels[index]}: ${errorMessage(result.reason)}`] : [],
    )
    setSnapshot({
      audit: valueAt<ModelLearningAudit>(results, 0),
      policy: valueAt<StakingPolicy>(results, 1),
      session: valueAt<PaperTradingSession>(results, 2),
      feeds: valueAt<OpsFeedsResponse>(results, 3),
      ingest: valueAt<OpsIngestResponse>(results, 4),
      streams: valueAt<OpsStreamsResponse>(results, 5),
      scrape: valueAt<ScrapeStatus>(results, 6),
      errors,
    })
    setLoading(false)
    setRefreshing(false)
  }, [])

  useEffect(() => {
    void load(false)
    const interval = window.setInterval(() => void load(true), REFRESH_MS)
    return () => window.clearInterval(interval)
  }, [load])

  const audit = snapshot?.audit
  const calibration = audit?.calibrationEvidence
  const outcomeQuality = audit?.outcomeQuality
  const sampleProgress = Math.min(100, (outcomeQuality?.trustedSettledSamples ?? 0))
  const effectiveProgress = Math.min(100, ((calibration?.effectiveSampleSize ?? 0) / 50) * 100)
  const posture = useMemo(() => systemPosture(snapshot), [snapshot])
  const scorePosture = useMemo(() => scoreEvidencePosture(snapshot?.session), [snapshot?.session])
  const policy = snapshot?.policy?.config

  const saveScenario = () => {
    window.localStorage.setItem('ttle-admin-scenario', JSON.stringify(scenario))
    setNotice('Scenario saved locally. The active model and live session were not changed.')
    window.setTimeout(() => setNotice(null), 4200)
  }

  const reloadPolicy = async () => {
    setReloading(true)
    try {
      const next = await reloadStakingPolicy()
      setSnapshot((current) => current ? { ...current, policy: next } : current)
      setNotice('Production staking policy reloaded from its approved source file.')
    } catch (error) {
      setNotice(errorMessage(error))
    } finally {
      setReloading(false)
    }
  }

  return (
    <V3Shell
      title="Admin Command"
      description="Model learning, policy, exposure, data truth, and live operating health—kept separate from the bettor experience."
      badges={
        <>
          <Badge variant="accent">{posture.label}</Badge>
          <Badge>Auto 15s</Badge>
        </>
      }
      actions={
        <Button variant="secondary" onClick={() => void load(true)} disabled={loading || refreshing}>
          <RefreshCcw className={cn('size-4', refreshing && 'animate-spin')} />
          Refresh
        </Button>
      }
    >
      {notice ? <InlineNotice>{notice}</InlineNotice> : null}
      {snapshot?.errors.length ? (
        <InlineNotice warning>{snapshot.errors.join(' · ')}</InlineNotice>
      ) : null}

      <section className="admin-hero overflow-hidden rounded-[30px] border border-blue-300/15 p-5 text-white shadow-2xl shadow-black/20 sm:p-7">
        <div className="grid gap-6 xl:grid-cols-[1.2fr_0.8fr] xl:items-end">
          <div>
            <div className="flex flex-wrap items-center gap-2">
              <span className="inline-flex items-center gap-2 rounded-full border border-blue-300/20 bg-blue-300/10 px-3 py-1.5 text-[10px] font-semibold uppercase tracking-[0.22em] text-blue-200">
                <BrainCircuit className="size-3.5" aria-hidden="true" />
                Learning posture
              </span>
              <PostureDot tone={posture.tone} label={posture.detail} />
            </div>
            <h2 className="mt-5 max-w-3xl text-3xl font-semibold tracking-[-0.045em] sm:text-4xl">
              {calibration?.rawSampleSize
                ? `${calibration.rawSampleSize} trusted decisions are shaping the evidence.`
                : 'The learning system is instrumented and waiting for trusted outcomes.'}
            </h2>
            <p className="mt-4 max-w-3xl text-sm leading-6 text-slate-300">
              Calibration only learns from resolved, high-confidence binary outcomes. Provisional score guesses remain visible
              in the audit, but cannot contaminate model truth or automatically move production weights.
            </p>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <DarkMetric label="Trusted settled" value={String(outcomeQuality?.trustedSettledSamples ?? 0)} />
            <DarkMetric label="Excluded settled" value={String(outcomeQuality?.excludedSettledSamples ?? 0)} />
            <DarkMetric label="Effective sample" value={formatNumber(calibration?.effectiveSampleSize)} />
            <DarkMetric label="Calibration error" value={formatPct(calibration?.calibrationError)} />
            <DarkMetric label="Stake-weighted CLV" value={formatSignedPercentagePoints(audit?.clv.stakeWeightedClvPct)} />
            <DarkMetric label="Decision-grade scores" value={String(scorePosture.decisionGrade)} />
            <DarkMetric label="Score-backed closure" value={formatPct(scorePosture.scoreBackedShare)} />
          </div>
        </div>
      </section>

      <section className="mt-5 grid gap-5 xl:grid-cols-[1.12fr_0.88fr]">
        <Card>
          <CardHeader>
            <div className="flex flex-wrap items-center justify-between gap-3">
              <Badge variant="accent">Learning readiness</Badge>
              <Link className="text-xs font-semibold text-[var(--accent-ink)]" to="/admin/model-quality">
                Full model quality <ArrowRight className="ml-1 inline size-3.5" />
              </Link>
            </div>
            <CardTitle>When the evidence becomes decision-grade</CardTitle>
            <CardDescription>
              The first 100 trusted resolutions reveal direction; 50 effective samples are the minimum before adaptive
              conclusions deserve weight. Until then, changes remain evidence and scenarios—not automatic calibration.
            </CardDescription>
          </CardHeader>
          <CardContent className="grid gap-5">
            <ProgressMetric
              detail={`${outcomeQuality?.trustedSettledSamples ?? 0} of 100 trusted outcomes`}
              label="Directional sample"
              progress={sampleProgress}
              value={`${Math.round(sampleProgress)}%`}
            />
            <ProgressMetric
              detail={`${formatNumber(calibration?.effectiveSampleSize)} of 50 effective observations`}
              label="Adaptive confidence"
              progress={effectiveProgress}
              value={`${Math.round(effectiveProgress)}%`}
            />
            <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
              <Metric icon={Target} label="Eligible coverage" value={formatPercentagePoints(outcomeQuality?.eligibleCoveragePct)} />
              <Metric icon={BarChart3} label="Brier score" value={formatNumber(calibration?.brierScore, 3)} />
              <Metric icon={GitCompareArrows} label="CLV coverage" value={formatPercentagePoints(audit?.clv.coveragePct)} />
              <Metric icon={ShieldCheck} label="Excluded labels" value={String(outcomeQuality?.excludedSettledSamples ?? 0)} />
            </div>
            {outcomeQuality?.exclusionReasons?.length ? (
              <div className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.66)] p-4">
                <SectionLabel>Why labels were excluded</SectionLabel>
                <div className="mt-3 flex flex-wrap gap-2">
                  {(outcomeQuality.exclusionReasons ?? []).map((item) => (
                    <span
                      className="rounded-full border border-amber-200 bg-amber-50 px-3 py-1.5 text-xs font-semibold text-amber-900"
                      key={item.reason}
                    >
                      {pretty(item.reason)} · {item.count}
                    </span>
                  ))}
                </div>
              </div>
            ) : null}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <Badge className="w-fit">Live system posture</Badge>
            <CardTitle>Everything feeding the decision engine</CardTitle>
            <CardDescription>Fast health signals with direct paths into the operator surface that owns each one.</CardDescription>
          </CardHeader>
          <CardContent className="grid gap-2">
            <SystemRow
              href="/admin/feeds"
              icon={RadioTower}
              label="Market feeds"
              status={`${snapshot?.feeds?.summary.healthySources ?? 0}/${snapshot?.feeds?.summary.totalSources ?? 0} healthy`}
              warning={(snapshot?.feeds?.summary.degradedSources ?? 0) + (snapshot?.feeds?.summary.downSources ?? 0) > 0}
            />
            <SystemRow
              href="/admin/ingest"
              icon={DatabaseZap}
              label="Ingestion bus"
              status={snapshot?.ingest?.bus.status ?? 'Unknown'}
              warning={!isHealthy(snapshot?.ingest?.bus.status)}
            />
            <SystemRow
              href="/admin/streams"
              icon={Activity}
              label="Stream workers"
              status={`${snapshot?.streams?.summary.enabledWorkers ?? 0}/${snapshot?.streams?.summary.totalWorkers ?? 0} enabled`}
              warning={(snapshot?.streams?.summary.offWorkers ?? 0) > 0}
            />
            <SystemRow
              href="/admin/scrape"
              icon={Database}
              label="Scraper"
              status={snapshot?.scrape?.running ? `${snapshot.scrape.mode} running` : 'Idle / ready'}
              warning={Boolean(snapshot?.scrape?.error)}
            />
            <SystemRow
              href="/admin/ops"
              icon={CircleDollarSign}
              label="Paper session"
              status={`${snapshot?.session?.openBets ?? 0} open · ${formatCurrency(snapshot?.session?.realizedPnl)} P&L`}
              warning={false}
            />
          </CardContent>
        </Card>
      </section>

      <section className="mt-5 grid gap-5 xl:grid-cols-[1.12fr_0.88fr]">
        <Card>
          <CardHeader>
            <Badge variant="accent" className="w-fit">Signal ledger</Badge>
            <CardTitle>What is helping, hurting, and still unknown</CardTitle>
            <CardDescription>
              Trigger ROI and calibration stay beside factor directionality so a high-contribution feature cannot masquerade
              as a proven one.
            </CardDescription>
          </CardHeader>
          <CardContent className="grid gap-5">
            <div>
              <SectionLabel>Trigger performance</SectionLabel>
              <SignalTable segments={audit?.triggers ?? []} />
            </div>
            <div>
              <SectionLabel>Feature behavior</SectionLabel>
              <FactorTable factors={audit?.factors ?? []} />
            </div>
          </CardContent>
        </Card>

        <div className="grid content-start gap-5">
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between gap-3">
                <Badge className="w-fit">Policy control</Badge>
                <span className="text-[10px] font-semibold uppercase tracking-[0.18em] text-[var(--ink-muted)]">
                  Production
                </span>
              </div>
              <CardTitle>Active gates and risk sizing</CardTitle>
              <CardDescription>
                Live values are read from the approved staking policy. Reloading re-reads that file; it does not accept
                unreviewed browser values.
              </CardDescription>
            </CardHeader>
            <CardContent className="grid gap-3">
              <PolicyRow label="Conservative edge" value={formatPct(CONSERVATIVE_EDGE)} />
              <PolicyRow label="Aggressive edge" value={formatPct(AGGRESSIVE_EDGE)} />
              <PolicyRow label="Staking minimum edge" value={formatPct(policy?.minimumEdge)} />
              <PolicyRow label="Fractional Kelly" value={formatPct(policy?.fractionalKelly)} />
              <PolicyRow label="Max open exposure" value={`${formatNumber(policy?.maxOpenExposureUnits)}u`} />
              <PolicyRow label="Drawdown trigger" value={formatPct(policy?.drawdownTriggerRoi)} />
              <Button variant="secondary" onClick={() => void reloadPolicy()} disabled={reloading}>
                <RefreshCcw className={cn('size-4', reloading && 'animate-spin')} />
                Reload approved policy
              </Button>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <Badge variant="accent" className="w-fit">
                <FlaskConical className="mr-1 size-3" />
                Scenario lab
              </Badge>
              <CardTitle>Test gates without touching the run</CardTitle>
              <CardDescription>
                Explore potential thresholds here. Drafts persist in this browser and are intentionally isolated from the
                running model until promoted through the policy source.
              </CardDescription>
            </CardHeader>
            <CardContent className="grid gap-4">
              <ScenarioSlider
                label="Minimum displayed edge"
                max={0.15}
                min={0.01}
                step={0.005}
                value={scenario.edge}
                valueLabel={formatPct(scenario.edge)}
                onChange={(edge) => setScenario((current) => ({ ...current, edge }))}
              />
              <ScenarioSlider
                label="Reliability floor"
                max={1}
                min={0}
                step={0.05}
                value={scenario.reliability}
                valueLabel={formatPct(scenario.reliability)}
                onChange={(reliability) => setScenario((current) => ({ ...current, reliability }))}
              />
              <ScenarioSlider
                label="Open exposure cap"
                max={12}
                min={1}
                step={0.5}
                value={scenario.exposure}
                valueLabel={`${scenario.exposure.toFixed(1)}u`}
                onChange={(exposure) => setScenario((current) => ({ ...current, exposure }))}
              />
              <Button onClick={saveScenario}>
                <Save className="size-4" />
                Save scenario draft
              </Button>
            </CardContent>
          </Card>
        </div>
      </section>

      <section className="mt-5 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <AdminJump icon={BrainCircuit} label="Model quality" detail="Reliability, calibration, drift" href="/admin/model-quality" />
        <AdminJump icon={Gauge} label="Operations" detail="Feed, stream, settlement posture" href="/admin/ops" />
        <AdminJump icon={ShieldCheck} label="Truth review" detail="Resolve disputed outcomes" href="/admin/review" />
        <AdminJump icon={Database} label="Scrape control" detail="Run, stop, and audit scraping" href="/admin/scrape" />
      </section>
    </V3Shell>
  )
}

function SignalTable({ segments }: { segments: LearningSegment[] }) {
  if (!segments.length) return <EmptySignal label="No trusted trigger samples yet." />
  return (
    <div className="overflow-x-auto rounded-[20px] border border-[var(--line)]">
      <table className="w-full min-w-[620px] text-sm">
        <thead className="bg-[var(--panel-soft)] text-left text-[10px] uppercase tracking-[0.16em] text-[var(--ink-muted)]">
          <tr><th className="px-4 py-3">Trigger</th><th>Effective n</th><th>Win rate</th><th>Cal. gap</th><th>ROI</th></tr>
        </thead>
        <tbody>
          {segments.slice(0, 7).map((segment) => (
            <tr className="border-t border-[var(--line)]" key={segment.segment}>
              <td className="px-4 py-3 font-semibold text-[var(--ink-strong)]">{pretty(segment.segment)}</td>
              <td>{formatNumber(segment.effectiveSampleSize)}</td>
              <td>{formatPct(segment.winRate)}</td>
              <td>{formatSignedPct(segment.calibrationError)}</td>
              <td className={segment.roiPct >= 0 ? 'font-semibold text-emerald-700' : 'font-semibold text-rose-700'}>{formatSignedPercentagePoints(segment.roiPct)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function FactorTable({ factors }: { factors: LearningFactor[] }) {
  if (!factors.length) return <EmptySignal label="Factor attribution will appear after eligible settlements." />
  return (
    <div className="grid gap-2">
      {factors.slice(0, 6).map((factor) => {
        const strength = Math.min(100, Math.abs(factor.meanAbsoluteContribution) * 1000)
        return (
          <div className="rounded-[18px] border border-[var(--line)] bg-white/50 p-3" key={factor.factor}>
            <div className="flex items-center justify-between gap-3 text-sm">
              <span className="font-semibold text-[var(--ink-strong)]">{pretty(factor.factor)}</span>
              <span className="font-mono text-xs text-[var(--ink-muted)]">{formatPct(factor.directionalAccuracy)} direction · n {formatNumber(factor.effectiveSampleSize)}</span>
            </div>
            <div className="mt-2 h-1.5 rounded-full bg-slate-200">
              <div className="h-full rounded-full bg-blue-500" style={{ width: `${strength}%` }} />
            </div>
          </div>
        )
      })}
    </div>
  )
}

function scoreEvidencePosture(session: PaperTradingSession | null | undefined) {
  const bets = [...(session?.openBetsList ?? []), ...(session?.recentBets ?? [])]
  const decisionGrade = bets.filter((bet) => bet.scoreEvidenceQuality === 'DECISION_GRADE').length
  const settled = bets.filter((bet) => ['WON', 'LOST', 'PUSHED', 'VOIDED'].includes((bet.status ?? '').toUpperCase()))
  const scoreBacked = settled.filter((bet) => {
    const source = `${bet.settlementSource ?? ''} ${bet.settlementReason ?? ''}`.toUpperCase()
    return source.includes('SCORE_BACKED') || source.includes('TARGETED') || source.includes('STREAM_CV')
  }).length
  return {
    decisionGrade,
    scoreBackedShare: settled.length ? scoreBacked / settled.length : 0,
  }
}

function ScenarioSlider({ label, max, min, onChange, step, value, valueLabel }: {
  label: string
  max: number
  min: number
  onChange: (value: number) => void
  step: number
  value: number
  valueLabel: string
}) {
  return (
    <label className="block">
      <span className="flex items-center justify-between gap-3 text-xs font-semibold text-[var(--ink-strong)]">
        {label}<span className="font-mono text-[var(--accent-ink)]">{valueLabel}</span>
      </span>
      <input className="mt-2 w-full accent-blue-600" type="range" min={min} max={max} step={step} value={value} onChange={(event) => onChange(Number(event.target.value))} />
    </label>
  )
}

function ProgressMetric({ detail, label, progress, value }: { detail: string; label: string; progress: number; value: string }) {
  return (
    <div>
      <div className="flex items-end justify-between gap-3">
        <div><p className="text-sm font-semibold text-[var(--ink-strong)]">{label}</p><p className="text-xs text-[var(--ink-muted)]">{detail}</p></div>
        <span className="font-mono text-sm font-bold text-[var(--ink-strong)]">{value}</span>
      </div>
      <div className="mt-2 h-2 rounded-full bg-slate-200"><div className="h-full rounded-full bg-gradient-to-r from-blue-600 to-cyan-400" style={{ width: `${progress}%` }} /></div>
    </div>
  )
}

function SystemRow({ href, icon: Icon, label, status, warning }: { href: string; icon: typeof Activity; label: string; status: string; warning: boolean }) {
  return (
    <Link className="group flex items-center gap-3 rounded-[18px] border border-[var(--line)] bg-white/50 p-3 transition hover:border-blue-300" to={href}>
      <span className={cn('grid size-10 place-items-center rounded-2xl', warning ? 'bg-amber-100 text-amber-700' : 'bg-emerald-100 text-emerald-700')}><Icon className="size-4" /></span>
      <div className="min-w-0 flex-1"><p className="text-sm font-semibold text-[var(--ink-strong)]">{label}</p><p className="truncate text-xs text-[var(--ink-muted)]">{status}</p></div>
      <ArrowRight className="size-4 text-slate-400 transition group-hover:translate-x-0.5" />
    </Link>
  )
}

function AdminJump({ detail, href, icon: Icon, label }: { detail: string; href: string; icon: typeof Activity; label: string }) {
  return (
    <Link className="group rounded-[22px] border border-white/10 bg-white/[0.05] p-4 text-white transition hover:-translate-y-0.5 hover:border-blue-300/30 hover:bg-blue-300/10" to={href}>
      <Icon className="size-5 text-blue-300" /><p className="mt-4 font-semibold">{label}</p><p className="mt-1 text-xs text-slate-400">{detail}</p>
      <ArrowRight className="mt-4 size-4 text-slate-500 transition group-hover:translate-x-1" />
    </Link>
  )
}

function Metric({ icon: Icon, label, value }: { icon: typeof Activity; label: string; value: string }) {
  return <div className="rounded-[18px] border border-[var(--line)] bg-white/55 p-3"><Icon className="size-4 text-blue-600" /><p className="mt-3 font-mono text-lg font-bold text-[var(--ink-strong)]">{value}</p><p className="mt-1 text-[10px] uppercase tracking-[0.14em] text-[var(--ink-muted)]">{label}</p></div>
}

function DarkMetric({ label, value }: { label: string; value: string }) {
  return <div className="rounded-[18px] border border-white/10 bg-white/[0.05] p-3"><p className="font-mono text-xl font-bold">{value}</p><p className="mt-1 text-[10px] uppercase tracking-[0.15em] text-slate-400">{label}</p></div>
}

function PolicyRow({ label, value }: { label: string; value: string }) {
  return <div className="flex items-center justify-between gap-4 border-b border-[var(--line)] pb-2 text-sm"><span className="text-[var(--ink-muted)]">{label}</span><span className="font-mono font-bold text-[var(--ink-strong)]">{value}</span></div>
}

function SectionLabel({ children }: { children: ReactNode }) {
  return <p className="mb-2 text-[10px] font-semibold uppercase tracking-[0.2em] text-[var(--ink-muted)]">{children}</p>
}

function EmptySignal({ label }: { label: string }) {
  return <div className="rounded-[18px] border border-dashed border-[var(--line-strong)] p-4 text-sm text-[var(--ink-muted)]">{label}</div>
}

function InlineNotice({ children, warning = false }: { children: ReactNode; warning?: boolean }) {
  return (
    <div className={cn('mb-4 flex items-center gap-2 rounded-[18px] border px-4 py-3 text-sm', warning ? 'border-amber-300 bg-amber-50 text-amber-900' : 'border-blue-300 bg-blue-50 text-blue-900')} role="status">
      {warning ? <AlertTriangle className="size-4" /> : <CheckCircle2 className="size-4" />}{children}
    </div>
  )
}

function PostureDot({ label, tone }: { label: string; tone: 'ok' | 'warn' }) {
  return <span className={cn('inline-flex items-center gap-2 rounded-full border px-3 py-1.5 text-[10px] font-semibold uppercase tracking-[0.16em]', tone === 'ok' ? 'border-emerald-300/20 bg-emerald-300/10 text-emerald-200' : 'border-amber-300/20 bg-amber-300/10 text-amber-200')}><span className={cn('size-1.5 rounded-full', tone === 'ok' ? 'bg-emerald-400' : 'bg-amber-400')} />{label}</span>
}

function systemPosture(snapshot: AdminSnapshot | null) {
  const warnings = (snapshot?.feeds?.summary.degradedSources ?? 0)
    + (snapshot?.feeds?.summary.downSources ?? 0)
    + (snapshot?.streams?.summary.offWorkers ?? 0)
    + (snapshot?.ingest && !isHealthy(snapshot.ingest.bus.status) ? 1 : 0)
    + (snapshot?.errors.length ?? 0)
  return warnings > 0
    ? { label: 'Attention', detail: `${warnings} signal${warnings === 1 ? '' : 's'} to inspect`, tone: 'warn' as const }
    : { label: 'Systems nominal', detail: 'All observed systems ready', tone: 'ok' as const }
}

function valueAt<T>(results: PromiseSettledResult<unknown>[], index: number): T | null {
  const result = results[index]
  return result?.status === 'fulfilled' ? result.value as T : null
}

function readScenario(): Scenario {
  try {
    const parsed = JSON.parse(window.localStorage.getItem('ttle-admin-scenario') ?? '') as Partial<Scenario>
    return {
      edge: finiteOr(parsed.edge, defaultScenario.edge),
      reliability: finiteOr(parsed.reliability, defaultScenario.reliability),
      exposure: finiteOr(parsed.exposure, defaultScenario.exposure),
    }
  } catch {
    return defaultScenario
  }
}

function finiteOr(value: number | undefined, fallback: number) {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

function isHealthy(value: string | null | undefined) {
  return ['HEALTHY', 'READY', 'UP', 'ACTIVE'].includes((value ?? '').toUpperCase())
}

function pretty(value: string) {
  return value.replaceAll('_', ' ').toLowerCase().replace(/\b\w/g, (letter) => letter.toUpperCase())
}

function formatNumber(value: number | null | undefined, digits = 1) {
  if (value == null || !Number.isFinite(value)) return '—'
  return new Intl.NumberFormat('en-US', { maximumFractionDigits: digits }).format(value)
}

function formatPct(value: number | null | undefined) {
  if (value == null || !Number.isFinite(value)) return '—'
  return `${(value * 100).toFixed(1)}%`
}

function formatSignedPct(value: number | null | undefined) {
  if (value == null || !Number.isFinite(value)) return '—'
  const pct = value * 100
  return `${pct >= 0 ? '+' : ''}${pct.toFixed(1)}%`
}

function formatPercentagePoints(value: number | null | undefined) {
  if (value == null || !Number.isFinite(value)) return '—'
  return `${value.toFixed(1)}%`
}

function formatSignedPercentagePoints(value: number | null | undefined) {
  if (value == null || !Number.isFinite(value)) return '—'
  return `${value >= 0 ? '+' : ''}${value.toFixed(1)}%`
}

function formatCurrency(value: number | null | undefined) {
  if (value == null || !Number.isFinite(value)) return '—'
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 }).format(value)
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : 'Request failed'
}
