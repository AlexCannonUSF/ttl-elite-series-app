import { TrendingDown, TrendingUp } from 'lucide-react'

import type { PaperTradeBet } from '@/features/live-studio/types'
import { cn } from '@/lib/utils'

type BetTableProps = {
  /** Bets to render, already sorted by the caller. */
  bets: PaperTradeBet[]
  /** Maximum rows to show; the rest are summarised below. */
  limit?: number
  /** When true the start-time / "settled" column renders a settled-at relative; otherwise an upcoming relative. */
  variant: 'upcoming' | 'settled'
  /** Used only for the empty state caption. */
  emptyLabel: string
  /** Reference time for relative formatting, defaults to now(). Pass a fixed clock in tests. */
  now?: Date
}

export function BetTable({ bets, limit = 20, variant, emptyLabel, now = new Date() }: BetTableProps) {
  if (bets.length === 0) {
    return (
      <div className="rounded-[22px] border border-dashed border-[var(--line-strong)] bg-[rgba(255,255,255,0.58)] p-6 text-sm text-[var(--ink-muted)]">
        {emptyLabel}
      </div>
    )
  }

  const visible = bets.slice(0, limit)
  const overflow = bets.length - visible.length

  return (
    <div className="overflow-x-auto">
      <table className="min-w-full border-separate border-spacing-y-2">
        <thead>
          <tr className="text-left text-xs uppercase text-[var(--ink-muted)]">
            <th className="px-3 pb-1 font-semibold">Matchup</th>
            <th className="px-3 pb-1 font-semibold">Pick</th>
            <th className="px-3 pb-1 font-semibold">
              {variant === 'upcoming' ? 'Starts' : 'Settled'}
            </th>
            <th className="px-3 pb-1 font-semibold text-right">Stake</th>
            <th className="px-3 pb-1 font-semibold text-right">Odds</th>
            <th className="px-3 pb-1 font-semibold text-right">Edge</th>
            <th className="px-3 pb-1 font-semibold text-right">Model</th>
            <th className="px-3 pb-1 font-semibold">
              {variant === 'upcoming' ? 'Trigger' : 'Result'}
            </th>
          </tr>
        </thead>
        <tbody>
          {visible.map((bet) => (
            <BetRow key={bet.id} bet={bet} variant={variant} now={now} />
          ))}
        </tbody>
      </table>
      {overflow > 0 ? (
        <p className="mt-2 text-xs text-[var(--ink-muted)]">
          +{overflow} more not shown.
        </p>
      ) : null}
    </div>
  )
}

function BetRow({
  bet,
  variant,
  now,
}: {
  bet: PaperTradeBet
  variant: BetTableProps['variant']
  now: Date
}) {
  const isWin = bet.status?.toUpperCase() === 'WON'
  const isLoss = bet.status?.toUpperCase() === 'LOST'
  const isVoid = bet.status?.toUpperCase() === 'VOIDED' || bet.status?.toUpperCase() === 'PUSHED'

  return (
    <tr className="rounded-[18px] bg-[rgba(255,255,255,0.78)]">
      <td className="rounded-l-[18px] px-3 py-3 align-top">
        <p className="text-sm font-medium text-[var(--ink-strong)]">{bet.eventName ?? 'Match'}</p>
        <p className="mt-1 text-xs text-[var(--ink-muted)]">
          {bet.competitionName ?? '—'}
        </p>
      </td>
      <td className="px-3 py-3 align-top text-sm font-medium text-[var(--ink-strong)]">
        {bet.sideName ?? '—'}
      </td>
      <td className="px-3 py-3 align-top text-sm text-[var(--ink-muted)]">
        {variant === 'upcoming'
          ? relativeFuture(bet.startTimeIso, now)
          : relativePast(bet.settledAt ?? bet.placedAt, now)}
      </td>
      <td className="px-3 py-3 align-top text-right text-sm text-[var(--ink-strong)]">
        ${bet.stake.toFixed(2)}
      </td>
      <td className="px-3 py-3 align-top text-right text-sm text-[var(--ink)]">
        {Number.isFinite(bet.decimalOdds) ? bet.decimalOdds.toFixed(2) : '—'}
      </td>
      <td className={cn('px-3 py-3 align-top text-right text-sm font-medium', edgeTone(bet.edge))}>
        {Number.isFinite(bet.edge) ? `${(bet.edge * 100).toFixed(1)}%` : '—'}
      </td>
      <td className="px-3 py-3 align-top text-right text-sm text-[var(--ink-muted)]">
        {Number.isFinite(bet.modelProbability) ? `${(bet.modelProbability * 100).toFixed(0)}%` : '—'}
      </td>
      <td className="rounded-r-[18px] px-3 py-3 align-top text-sm">
        {variant === 'upcoming' ? (
          <span className="grid gap-1">
            <span className="text-[var(--ink-muted)]">{bet.topTrigger ?? '—'}</span>
            <ScoreEvidenceReadout bet={bet} />
          </span>
        ) : (
          <div className="grid gap-1.5">
            <BetResult
              status={bet.status}
              profitLoss={bet.profitLoss}
              isWin={isWin}
              isLoss={isLoss}
              isVoid={isVoid}
            />
            <SettlementTrust bet={bet} />
          </div>
        )}
      </td>
    </tr>
  )
}

function ScoreEvidenceReadout({ bet }: { bet: PaperTradeBet }) {
  if (!bet.scoreEvidenceQuality || bet.scoreEvidenceQuality === 'NONE') {
    return null
  }
  const confidence = bet.scoreEvidenceConfidence == null
    ? null
    : `${Math.round(bet.scoreEvidenceConfidence * 100)}%`
  const sources = bet.scoreEvidenceSourceCount
    ? `${bet.scoreEvidenceSourceCount} ${bet.scoreEvidenceSourceCount === 1 ? 'feed' : 'feeds'}`
    : null
  return (
    <span className={cn(
      'text-[10px] leading-4',
      bet.scoreEvidenceContradictory
        ? 'text-rose-700'
        : bet.scoreEvidenceQuality === 'DECISION_GRADE'
          ? 'text-emerald-700'
          : 'text-[var(--ink-muted)]',
    )}>
      {prettyEvidence(bet.scoreEvidenceQuality)}
      {confidence ? ` · ${confidence}` : ''}
      {sources ? ` · ${sources}` : ''}
      {bet.scoreEvidenceLatestScore ? <span className="block font-mono">{bet.scoreEvidenceLatestScore}</span> : null}
    </span>
  )
}

function SettlementTrust({ bet }: { bet: PaperTradeBet }) {
  if (bet.settlementConfidence == null && !bet.settlementSource && bet.closingDecimalOdds == null) {
    return null
  }
  const sourceCount = bet.settlementEvidenceSourceCount ?? 0
  const evidence = [
    settlementLabel(bet.settlementSource, bet.settlementReason),
    bet.settlementConfidence == null ? null : `${Math.round(bet.settlementConfidence * 100)}% trust`,
    sourceCount > 0 ? `${sourceCount} ${sourceCount === 1 ? 'source' : 'sources'}` : null,
  ].filter(Boolean).join(' · ')
  const close = bet.closingDecimalOdds == null
    ? null
    : `Close ${bet.closingDecimalOdds.toFixed(2)}${bet.closingSource ? ` · ${bet.closingSource}` : ''}`

  return (
    <span className="block max-w-[260px] text-[10px] leading-4 text-[var(--ink-muted)]">
      {evidence || 'Settlement evidence recorded'}
      {close ? <span className="block">{close}</span> : null}
    </span>
  )
}

function prettyEvidence(value: string) {
  return value.replaceAll('_', ' ').toLowerCase().replace(/\b\w/g, (letter) => letter.toUpperCase())
}

function settlementLabel(source: string | null | undefined, reason: string | null | undefined) {
  const value = `${source ?? ''} ${reason ?? ''}`.toUpperCase()
  if (value.includes('TARGETED')) return 'Targeted completion'
  if (value.includes('SCORE_BACKED') || value.includes('DECISIVE')) return 'Score backed'
  if (value.includes('OFFICIAL')) return 'Official result'
  if (value.includes('DATABASE')) return 'Database result'
  if (value.includes('HEURISTIC')) return 'Heuristic'
  if (value.includes('VOID')) return 'No trusted result'
  return source ? source.replaceAll('_', ' ').toLowerCase() : null
}

function BetResult({
  status,
  profitLoss,
  isWin,
  isLoss,
  isVoid,
}: {
  status: string
  profitLoss: number | null
  isWin: boolean
  isLoss: boolean
  isVoid: boolean
}) {
  const pnl = profitLoss ?? 0
  const tone = isWin
    ? 'border-emerald-200 bg-emerald-50 text-emerald-800'
    : isLoss
      ? 'border-rose-200 bg-rose-50 text-rose-800'
      : isVoid
        ? 'border-slate-200 bg-slate-50 text-slate-700'
        : 'border-sky-200 bg-sky-50 text-sky-800'
  const Icon = isWin ? TrendingUp : isLoss ? TrendingDown : null
  const formattedPnl = isVoid
    ? '$0.00'
    : `${pnl >= 0 ? '+' : ''}$${pnl.toFixed(2)}`

  return (
    <span
      className={cn(
        'inline-flex items-center gap-2 rounded-full border px-3 py-1 text-xs font-semibold uppercase',
        tone,
      )}
    >
      {Icon ? <Icon className="size-3" /> : null}
      <span>{status?.toUpperCase() ?? 'UNKNOWN'}</span>
      <span className="font-normal normal-case">{formattedPnl}</span>
    </span>
  )
}

function edgeTone(edge: number) {
  if (!Number.isFinite(edge)) return 'text-[var(--ink-muted)]'
  if (edge >= 0.05) return 'text-emerald-700'
  if (edge >= 0) return 'text-[var(--ink-strong)]'
  return 'text-rose-700'
}

function relativeFuture(iso: string | null | undefined, now: Date) {
  if (!iso) return '—'
  const then = new Date(iso)
  if (Number.isNaN(then.getTime())) return iso
  const diffMs = then.getTime() - now.getTime()
  if (diffMs <= 0) {
    return 'live now'
  }
  return `in ${humanDuration(diffMs)}`
}

function relativePast(iso: string | null | undefined, now: Date) {
  if (!iso) return '—'
  const then = new Date(iso)
  if (Number.isNaN(then.getTime())) return iso
  const diffMs = now.getTime() - then.getTime()
  if (diffMs < 0) {
    return 'just now'
  }
  return `${humanDuration(diffMs)} ago`
}

function humanDuration(ms: number) {
  const seconds = Math.round(ms / 1000)
  if (seconds < 60) return `${seconds}s`
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${minutes}m`
  const hours = minutes / 60
  if (hours < 24) return `${hours.toFixed(hours < 10 ? 1 : 0)}h`
  const days = Math.floor(hours / 24)
  return `${days}d`
}
