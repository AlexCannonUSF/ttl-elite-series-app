import { type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Activity, DollarSign, Gauge, RefreshCcw, Target, TrendingUp } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { LIVE_SESSION_REFRESH_EVENT } from '@/features/command-palette/CommandPalette'
import { fetchLiveSession } from '@/features/live-studio/api'
import type { EquityPoint, PaperTradingSession } from '@/features/live-studio/types'
import { cn } from '@/lib/utils'

const REFRESH_INTERVAL_MS = 5000

const currency = new Intl.NumberFormat('en-US', {
  currency: 'USD',
  maximumFractionDigits: 0,
  minimumFractionDigits: 0,
  style: 'currency',
})

const compactNumber = new Intl.NumberFormat('en-US', {
  maximumFractionDigits: 0,
})

export function SessionRibbon() {
  const [data, setData] = useState<PaperTradingSession | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const mountedRef = useRef(true)

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  const loadSession = useCallback(async (background: boolean) => {
    const controller = new AbortController()
    if (mountedRef.current) {
      if (background) {
        setRefreshing(true)
      } else {
        setLoading(true)
      }
    }

    try {
      const next = await fetchLiveSession(controller.signal)
      if (!mountedRef.current) {
        return
      }
      setData(next)
      setError(null)
    } catch (nextError) {
      if (!mountedRef.current) {
        return
      }
      setError(nextError instanceof Error ? nextError.message : 'Unable to load live session.')
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
  }, [])

  useEffect(() => {
    void loadSession(false)
    const interval = window.setInterval(() => {
      void loadSession(true)
    }, REFRESH_INTERVAL_MS)
    const handlePaletteRefresh = () => {
      void loadSession(true)
    }
    window.addEventListener(LIVE_SESSION_REFRESH_EVENT, handlePaletteRefresh)

    return () => {
      window.clearInterval(interval)
      window.removeEventListener(LIVE_SESSION_REFRESH_EVENT, handlePaletteRefresh)
    }
  }, [loadSession])

  const exposureUsage = data?.exposureMetrics.openExposureUsagePct ?? 0
  const concurrentUsage = data?.exposureMetrics.concurrentOpenBetUsagePct ?? 0
  const clv = data?.clvMetrics.avgClvPct ?? 0
  const pnl = data?.realizedPnl ?? 0

  const status = useMemo(() => {
    if (error) {
      return 'DEGRADED'
    }
    if (!data) {
      return 'LOADING'
    }
    return data.status
  }, [data, error])

  return (
    <section
      aria-label="Live paper-trading session status"
      className="rounded-[20px] border border-[var(--line-strong)] bg-[rgba(255,255,255,0.84)] shadow-[0_18px_60px_-44px_rgba(8,25,28,0.65)] backdrop-blur"
    >
      <div className="grid gap-0 divide-y divide-[var(--line)] lg:grid-cols-[1.1fr_1fr_1fr_1fr_auto] lg:divide-x lg:divide-y-0">
        <RibbonMetric
          icon={DollarSign}
          label="Live P&L"
          value={loading && !data ? 'Loading' : formatSignedCurrency(pnl)}
          detail={`${formatSignedPct(data?.roiPct ?? 0)} ROI | ${compactNumber.format(data?.wins ?? 0)}-${compactNumber.format(data?.losses ?? 0)}`}
          tone={pnl >= 0 ? 'positive' : 'negative'}
        >
          <Sparkline points={data?.equityCurve ?? []} tone={pnl >= 0 ? 'positive' : 'negative'} />
        </RibbonMetric>

        <RibbonMetric
          icon={TrendingUp}
          label="CLV"
          value={loading && !data ? 'Loading' : `${formatSignedNumber(clv)} pp`}
          detail={`${data?.clvMetrics.betsWithClosingSnapshot ?? 0}/${data?.clvMetrics.betsInWindow ?? 0} closes | ${formatRatioPct(data?.clvMetrics.coverageRatio ?? 0)} coverage`}
          tone={clv >= 0 ? 'positive' : 'negative'}
        >
          <div className="grid gap-1 text-right text-[11px] text-[var(--ink-muted)]">
            <span>Placed {formatSignedNumber(data?.clvMetrics.avgPlacedImpliedPct ?? 0, false)}%</span>
            <span>Close {formatSignedNumber(data?.clvMetrics.avgClosingImpliedPct ?? 0, false)}%</span>
          </div>
        </RibbonMetric>

        <RibbonMetric
          icon={Gauge}
          label="Exposure Utilisation"
          value={formatRatioPct(exposureUsage)}
          detail={`${formatCurrency(data?.exposureMetrics.openExposure ?? 0)} / ${formatCurrency(data?.exposureMetrics.openExposureCap ?? 0)}`}
          tone={exposureUsage >= 0.9 ? 'negative' : exposureUsage >= 0.7 ? 'warning' : 'neutral'}
        >
          <UsageBar value={exposureUsage} />
        </RibbonMetric>

        <RibbonMetric
          icon={Target}
          label="Open Bets"
          value={compactNumber.format(data?.openBets ?? 0)}
          detail={`${formatRatioPct(concurrentUsage)} slots | ${formatCurrency(data?.exposureMetrics.openExposureRemaining ?? 0)} room`}
          tone={concurrentUsage >= 0.9 ? 'negative' : concurrentUsage >= 0.7 ? 'warning' : 'neutral'}
        >
          <p className="max-w-[170px] truncate text-right text-[11px] text-[var(--ink-muted)]">
            {data?.exposureMetrics.mostExposedPlayerName ?? data?.exposureMetrics.mostExposedTrigger ?? 'No concentration'}
          </p>
        </RibbonMetric>

        <div className="flex items-center justify-between gap-3 p-4 lg:min-w-[180px] lg:flex-col lg:items-end lg:justify-center">
          <div className="text-left lg:text-right">
            <div
              className={cn(
                'inline-flex items-center gap-2 rounded-full border px-2.5 py-1 text-[11px] font-semibold uppercase tracking-[0.18em]',
                status === 'ACTIVE'
                  ? 'border-emerald-200 bg-emerald-50 text-emerald-800'
                  : status === 'DEGRADED'
                    ? 'border-amber-200 bg-amber-50 text-amber-800'
                    : 'border-slate-200 bg-slate-50 text-slate-700',
              )}
            >
              <Activity aria-hidden="true" className="size-3.5" />
              {status}
            </div>
            <p className="mt-2 text-xs text-[var(--ink-muted)]">{formatDateTime(data?.lastSyncAt)}</p>
          </div>
          <Button variant="secondary" size="sm" onClick={() => void loadSession(true)} disabled={loading || refreshing}>
            <RefreshCcw className={cn('size-3.5', refreshing && 'animate-spin')} />
            Refresh
          </Button>
        </div>
      </div>
      {error ? (
        <div className="border-t border-[var(--line)] px-4 py-2 text-xs text-amber-800" role="alert">
          {error}
        </div>
      ) : null}
    </section>
  )
}

type RibbonMetricProps = {
  children?: ReactNode
  detail: string
  icon: typeof DollarSign
  label: string
  tone: 'negative' | 'neutral' | 'positive' | 'warning'
  value: string
}

function RibbonMetric({ children, detail, icon: Icon, label, tone, value }: RibbonMetricProps) {
  return (
    <div className="flex min-h-[112px] items-center justify-between gap-4 p-4">
      <div className="min-w-0">
        <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.18em] text-[var(--ink-muted)]">
          <span
            className={cn(
              'inline-flex size-7 items-center justify-center rounded-lg border',
              toneClass(tone, 'icon'),
            )}
          >
            <Icon aria-hidden="true" className="size-3.5" />
          </span>
          <span>{label}</span>
        </div>
        <p className={cn('mt-3 font-mono text-2xl font-semibold tracking-normal', toneClass(tone, 'text'))}>{value}</p>
        <p className="mt-1 truncate text-xs text-[var(--ink-muted)]">{detail}</p>
      </div>
      <div className="shrink-0">{children}</div>
    </div>
  )
}

function UsageBar({ value }: { value: number }) {
  const pct = Math.max(0, Math.min(value, 1)) * 100
  return (
    <div className="h-2 w-28 overflow-hidden rounded-full bg-[rgba(17,37,40,0.10)]">
      <div
        aria-label={`Exposure usage ${Math.round(pct)} percent`}
        aria-valuemax={100}
        aria-valuemin={0}
        aria-valuenow={Math.round(pct)}
        className={cn(
          'h-full rounded-full',
          value >= 0.9 ? 'bg-rose-500' : value >= 0.7 ? 'bg-amber-500' : 'bg-emerald-500',
        )}
        role="progressbar"
        style={{ width: `${pct}%` }}
      />
    </div>
  )
}

function Sparkline({ points, tone }: { points: EquityPoint[]; tone: 'negative' | 'positive' }) {
  const values = points
    .map((point) => point.cumulativePnl)
    .filter((value) => Number.isFinite(value))

  if (values.length < 2) {
    return (
      <div
        aria-label="Session P&L sparkline unavailable until at least two equity points are recorded"
        className="h-9 w-32 rounded-lg border border-dashed border-[var(--line-strong)]"
        role="img"
      />
    )
  }

  const width = 128
  const height = 36
  const padding = 3
  const min = Math.min(...values)
  const max = Math.max(...values)
  const spread = Math.max(max - min, 1)
  const d = values
    .map((value, index) => {
      const x = padding + (index / Math.max(values.length - 1, 1)) * (width - padding * 2)
      const y = height - padding - ((value - min) / spread) * (height - padding * 2)
      return `${index === 0 ? 'M' : 'L'} ${x.toFixed(2)} ${y.toFixed(2)}`
    })
    .join(' ')

  return (
    <svg className="h-9 w-32" viewBox={`0 0 ${width} ${height}`} role="img" aria-label="Session P&L sparkline">
      <path d={d} fill="none" stroke={tone === 'positive' ? '#059669' : '#e11d48'} strokeLinecap="round" strokeWidth="2.5" />
    </svg>
  )
}

function toneClass(tone: RibbonMetricProps['tone'], part: 'icon' | 'text') {
  if (tone === 'positive') {
    return part === 'icon' ? 'border-emerald-200 bg-emerald-50 text-emerald-700' : 'text-emerald-700'
  }
  if (tone === 'negative') {
    return part === 'icon' ? 'border-rose-200 bg-rose-50 text-rose-700' : 'text-rose-700'
  }
  if (tone === 'warning') {
    return part === 'icon' ? 'border-amber-200 bg-amber-50 text-amber-700' : 'text-amber-700'
  }
  return part === 'icon' ? 'border-slate-200 bg-slate-50 text-slate-700' : 'text-[var(--ink-strong)]'
}

function formatCurrency(value: number) {
  return currency.format(Number.isFinite(value) ? value : 0)
}

function formatSignedCurrency(value: number) {
  const safe = Number.isFinite(value) ? value : 0
  return `${safe >= 0 ? '+' : '-'}${currency.format(Math.abs(safe))}`
}

function formatRatioPct(value: number) {
  const safe = Number.isFinite(value) ? value : 0
  return `${Math.round(safe * 100)}%`
}

function formatSignedPct(value: number) {
  return `${formatSignedNumber(value)}%`
}

function formatSignedNumber(value: number, includeSign = true) {
  const safe = Number.isFinite(value) ? value : 0
  const prefix = includeSign && safe >= 0 ? '+' : ''
  return `${prefix}${safe.toFixed(2)}`
}

function formatDateTime(value?: string | null) {
  if (!value) {
    return 'No sync yet'
  }
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) {
    return value
  }
  return parsed.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}
