import {
  AlertTriangle,
  CheckCircle2,
  ChevronRight,
  Clock3,
  Radio,
  ScanSearch,
  UserCheck,
} from 'lucide-react'
import { Link } from 'react-router-dom'

import type { ModelCallTracking } from '@/features/live-studio/types'
import { cn } from '@/lib/utils'

export function ModelCallPipelineRow({ call, to }: { call: ModelCallTracking; to: string }) {
  const status = stagePresentation(call.pipelineStage)
  const StatusIcon = status.icon
  return (
    <Link
      className="group grid gap-3 rounded-[20px] border border-[var(--line)] bg-white/75 p-4 transition hover:-translate-y-0.5 hover:border-[var(--accent-soft)] hover:bg-white hover:shadow-lg hover:shadow-slate-900/5 lg:grid-cols-[minmax(230px,1.35fr)_minmax(180px,0.8fr)_minmax(180px,0.85fr)_minmax(180px,1fr)_auto] lg:items-center"
      to={to}
    >
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-2">
          <span className={cn('inline-flex items-center gap-1 rounded-full px-2 py-1 text-[10px] font-bold uppercase tracking-[0.12em]', status.className)}>
            <StatusIcon className={cn('size-3', call.pipelineStage === 'LIVE_MONITORING' && 'animate-pulse')} />
            {call.pipelineLabel}
          </span>
          <span className="text-[10px] font-semibold uppercase tracking-[0.12em] text-[var(--ink-muted)]">
            {call.captureType === 'PREMATCH_CLOSE' ? 'Pregame close' : 'First live read'}
          </span>
        </div>
        <p className="mt-2 truncate font-semibold text-[var(--ink-strong)]">{call.player1Name} vs {call.player2Name}</p>
        <p className="mt-1 truncate text-xs text-[var(--ink-muted)]">{call.competitionName ?? 'Table Tennis'} · {formatTime(call.startTimeIso)}</p>
      </div>

      <div>
        <p className="text-[9px] font-semibold uppercase tracking-[0.14em] text-[var(--ink-muted)]">Latest match state</p>
        <p className="mt-1 font-mono text-sm font-bold text-[var(--ink-strong)]">{call.latestScore || 'No score yet'}</p>
        <p className="mt-1 truncate text-xs text-[var(--ink-muted)]">{pretty(call.latestPhase ?? call.pipelineStage)}</p>
      </div>

      <div>
        <p className="text-[9px] font-semibold uppercase tracking-[0.14em] text-[var(--ink-muted)]">Model winner</p>
        <p className="mt-1 truncate text-sm font-bold text-[var(--ink-strong)]">{call.predictedWinnerName ?? 'No lean'}</p>
        <p className="mt-1 font-mono text-xs text-[var(--ink-muted)]">{formatProbability(call.modelProbability)} · fair {formatAmerican(call.modelFairAmericanOdds)}</p>
      </div>

      <div>
        <p className="text-[9px] font-semibold uppercase tracking-[0.14em] text-[var(--ink-muted)]">Decision</p>
        <p className="mt-1 truncate text-sm font-bold text-[var(--ink-strong)]">{pretty(call.decisionStatus ?? 'RECORDED')}</p>
        <p className="mt-1 truncate text-xs text-[var(--ink-muted)]">{pretty(call.decisionReason ?? 'Awaiting decision detail')}</p>
      </div>

      <div className="flex items-center justify-end gap-2 text-xs font-semibold text-[var(--accent-ink)]">
        {call.canApprove ? 'Review' : 'Inspect'}
        <ChevronRight className="size-4 transition group-hover:translate-x-0.5" />
      </div>
    </Link>
  )
}

export function stagePresentation(stage: string) {
  switch (stage) {
    case 'LIVE_MONITORING':
      return { className: 'bg-rose-100 text-rose-800', icon: Radio }
    case 'SETTLEMENT_REVIEW':
      return { className: 'bg-amber-100 text-amber-900', icon: ScanSearch }
    case 'VIEWER_APPROVED':
      return { className: 'bg-violet-100 text-violet-800', icon: UserCheck }
    case 'SYSTEM_CONFIRMED':
      return { className: 'bg-emerald-100 text-emerald-800', icon: CheckCircle2 }
    case 'RESULT_CONFLICT':
      return { className: 'bg-rose-100 text-rose-900', icon: AlertTriangle }
    default:
      return { className: 'bg-slate-100 text-slate-700', icon: Clock3 }
  }
}

export function pretty(value: string) {
  return value.replaceAll('_', ' ').toLowerCase().replace(/\b\w/g, (letter) => letter.toUpperCase())
}

export function formatAmerican(value: number | null | undefined) {
  if (value == null || !Number.isFinite(value)) return 'N/A'
  return value > 0 ? `+${value}` : String(value)
}

export function formatProbability(value: number | null | undefined) {
  return value == null || !Number.isFinite(value) ? 'N/A' : `${(value * 100).toFixed(1)}%`
}

export function formatTime(value: string | null) {
  if (!value) return 'Time unavailable'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return value
  return new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' }).format(parsed)
}
