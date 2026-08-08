import { type KeyboardEvent as ReactKeyboardEvent, useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import {
  Activity,
  CircleDollarSign,
  Database,
  FileSearch2,
  GitCompareArrows,
  Gauge,
  Home,
  type LucideIcon,
  RadioTower,
  RefreshCcw,
  RotateCcw,
  Search,
  ShieldCheck,
  Waypoints,
  X,
  Zap,
} from 'lucide-react'
import { type NavigateFunction, useNavigate } from 'react-router-dom'

import { Button } from '@/components/ui/button'
import { fetchLiveSession, syncLiveSession } from '@/features/live-studio/api'
import type { PaperTradeBet, PaperTradingSession } from '@/features/live-studio/types'
import { fetchOpsFeeds } from '@/features/ops-feeds/api'
import type { OpsFeedStatus, OpsFeedsResponse } from '@/features/ops-feeds/types'
import { cn } from '@/lib/utils'

export const LIVE_SESSION_REFRESH_EVENT = 'ttlelite:live-session-refresh'
export const OPS_FEEDS_REFRESH_EVENT = 'ttlelite:ops-feeds-refresh'

type Notice = {
  message: string
  tone: 'error' | 'info' | 'success'
}

type PaletteCommand = {
  closeOnRun?: boolean
  description: string
  group: string
  icon: LucideIcon
  id: string
  keywords?: string[]
  label: string
  run: () => Promise<void> | void
}

const navigationCommands = [
  {
    description: 'Live session ribbon, risk posture, and phase status.',
    group: 'Navigation',
    icon: Home,
    id: 'nav.home',
    keywords: ['overview', 'session', 'home'],
    label: 'Open Overview',
    to: '/',
  },
  {
    description: 'Live odds, model edge, paper-pick readiness, and odds movement.',
    group: 'Navigation',
    icon: Activity,
    id: 'nav.live-board',
    keywords: ['live', 'board', 'odds', 'edge'],
    label: 'Open Live Board',
    to: '/live-board',
  },
  {
    description: 'Feed health, ingestion pressure, streams, settlement diffs, and review backlog.',
    group: 'Navigation',
    icon: Gauge,
    id: 'nav.ops-console',
    keywords: ['ops', 'console', 'health', 'dlq', 'review'],
    label: 'Open Ops Console',
    to: '/ops',
  },
  {
    description: 'Unified health, latency, DLQ, and feed freshness.',
    group: 'Navigation',
    icon: RadioTower,
    id: 'nav.feeds',
    keywords: ['feeds', 'health', 'latency'],
    label: 'Open Ops Feeds',
    to: '/ops/feeds',
  },
  {
    description: 'Redis stream posture, bus mode, partitions, and DLQ depth.',
    group: 'Navigation',
    icon: Zap,
    id: 'nav.ingest',
    keywords: ['ingest', 'bus', 'redis', 'dlq'],
    label: 'Open Ops Ingest',
    to: '/ops/ingest',
  },
  {
    description: 'Stream workers, route overrides, and VLM usage.',
    group: 'Navigation',
    icon: Activity,
    id: 'nav.streams',
    keywords: ['stream', 'workers', 'vlm'],
    label: 'Open Stream Workers',
    to: '/ops/feeds/streams',
  },
  {
    description: 'Primary settlement outcomes versus shadow-path diffs.',
    group: 'Navigation',
    icon: GitCompareArrows,
    id: 'nav.diffs',
    keywords: ['settlement', 'diffs', 'shadow'],
    label: 'Open Settlement Diffs',
    to: '/ops/diffs',
  },
  {
    description: 'Manual review queue for Score Truth decisions.',
    group: 'Navigation',
    icon: ShieldCheck,
    id: 'nav.review',
    keywords: ['review', 'score truth'],
    label: 'Open Review Queue',
    to: '/review',
  },
  {
    description: 'Reliability, drift, calibration, and model quality.',
    group: 'Navigation',
    icon: Waypoints,
    id: 'nav.ml-quality',
    keywords: ['ml', 'quality', 'drift', 'reliability'],
    label: 'Open ML Quality',
    to: '/ml/quality',
  },
  {
    description: 'Trigger a tt-series.com refresh and watch matches land.',
    group: 'Navigation',
    icon: Database,
    id: 'nav.scrape',
    keywords: ['scrape', 'scraper', 'matches', 'tt-series', 'data', 'refresh'],
    label: 'Open Scraper',
    to: '/ops/scrape',
  },
] satisfies Array<{
  description: string
  group: string
  icon: LucideIcon
  id: string
  keywords: string[]
  label: string
  to: string
}>

const formatter = new Intl.NumberFormat('en-US', {
  currency: 'USD',
  maximumFractionDigits: 0,
  minimumFractionDigits: 0,
  style: 'currency',
})

export function CommandPalette() {
  const navigate = useNavigate()
  const dialogRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const descriptionId = useId()
  const inputId = useId()
  const listboxId = useId()
  const noticeId = useId()
  const titleId = useId()
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [selectedIndex, setSelectedIndex] = useState(0)
  const [session, setSession] = useState<PaperTradingSession | null>(null)
  const [feeds, setFeeds] = useState<OpsFeedsResponse | null>(null)
  const [loadingContext, setLoadingContext] = useState(false)
  const [executingId, setExecutingId] = useState<string | null>(null)
  const [notice, setNotice] = useState<Notice | null>(null)

  const close = useCallback(() => {
    setOpen(false)
    setQuery('')
    setSelectedIndex(0)
    window.setTimeout(() => triggerRef.current?.focus(), 0)
  }, [])

  useEffect(() => {
    const handleKeyDown = (event: WindowEventMap['keydown']) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        setOpen((current) => !current)
        return
      }
      if (event.key === 'Escape') {
        setOpen(false)
      }
    }

    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [])

  useEffect(() => {
    if (!open) {
      return
    }
    window.setTimeout(() => inputRef.current?.focus(), 0)
  }, [open])

  useEffect(() => {
    if (!open) {
      return
    }

    const controller = new AbortController()
    let mounted = true
    let pendingRequests = 2
    setLoadingContext(true)
    setNotice(null)

    const finishRequest = () => {
      if (!mounted) {
        return
      }
      pendingRequests -= 1
      if (pendingRequests <= 0) {
        setLoadingContext(false)
      }
    }

    const fallback = window.setTimeout(() => {
      if (mounted) {
        setLoadingContext(false)
      }
    }, 2500)

    void fetchLiveSession(controller.signal)
      .then((next) => {
        if (mounted) {
          setSession(next)
        }
      })
      .catch(() => undefined)
      .finally(finishRequest)

    void fetchOpsFeeds(controller.signal)
      .then((next) => {
        if (mounted) {
          setFeeds(next)
        }
      })
      .catch(() => undefined)
      .finally(finishRequest)

    return () => {
      mounted = false
      window.clearTimeout(fallback)
      controller.abort()
    }
  }, [open])

  const commands = useMemo<PaletteCommand[]>(() => {
    const routeCommands = navigationCommands.map<PaletteCommand>((command) => ({
      ...command,
      run: () => navigate(command.to),
    }))

    const betCommands = buildBetCommands(session, navigate)
    const attentionFeedCommands = buildAttentionFeedCommands(feeds, navigate)

    return [
      ...routeCommands,
      {
        closeOnRun: false,
        description: 'Pull the live board, place eligible paper bets, settle due rows, and refresh the ribbon.',
        group: 'Bet Management',
        icon: CircleDollarSign,
        id: 'session.sync',
        keywords: ['paper', 'bet', 'sync', 'settle', 'place'],
        label: 'Sync live paper session',
        run: async () => {
          const result = await syncLiveSession()
          setSession(result.session)
          window.dispatchEvent(new CustomEvent(LIVE_SESSION_REFRESH_EVENT))
          setNotice({
            message: `Sync complete: ${result.rowsScanned} scanned, ${result.betsPlaced} placed, ${result.betsSettled} settled, ${result.betsVoided} voided.`,
            tone: 'success',
          })
        },
      },
      {
        description: 'Jump to the operator review queue for held or ambiguous settlement decisions.',
        group: 'Bet Management',
        icon: ShieldCheck,
        id: 'bets.review',
        keywords: ['bet', 'review', 'pending', 'evidence'],
        label: 'Open bet review queue',
        run: () => navigate('/review'),
      },
      ...betCommands,
      {
        closeOnRun: false,
        description: 'Request a fresh feed-health snapshot and update the Ops Feeds screen if it is open.',
        group: 'Feed Actions',
        icon: RefreshCcw,
        id: 'feeds.refresh',
        keywords: ['feed', 'health', 'refresh', 'snapshot'],
        label: 'Refresh feed health snapshot',
        run: async () => {
          const next = await fetchOpsFeeds()
          setFeeds(next)
          window.dispatchEvent(new CustomEvent(OPS_FEEDS_REFRESH_EVENT))
          setNotice({
            message: `Feed snapshot refreshed: ${next.summary.healthySources}/${next.summary.totalSources} healthy, DLQ ${next.summary.totalDlqDepth}.`,
            tone: 'success',
          })
        },
      },
      ...attentionFeedCommands,
      {
        closeOnRun: false,
        description: 'Reload the active staking policy from disk and keep the live session on the current risk rules.',
        group: 'Risk Controls',
        icon: Gauge,
        id: 'risk.policy-reload',
        keywords: ['staking', 'policy', 'reload', 'kelly'],
        label: 'Reload staking policy',
        run: async () => {
          const response = await postJson<{ checksum: string | null }>('/api/v3/ops/staking/policy/reload', {
            triggeredBy: 'command-palette',
          })
          setNotice({
            message: `Staking policy reloaded${response.checksum ? `: ${response.checksum.slice(0, 10)}` : '.'}`,
            tone: 'success',
          })
        },
      },
    ]
  }, [feeds, navigate, session])

  const visibleCommands = useMemo(() => {
    const normalizedQuery = normalize(query)
    if (!normalizedQuery) {
      return commands.slice(0, 22)
    }
    const parts = normalizedQuery.split(' ').filter(Boolean)
    return commands
      .filter((command) => {
        const target = normalize([
          command.group,
          command.label,
          command.description,
          ...(command.keywords ?? []),
        ].join(' '))
        return parts.every((part) => target.includes(part))
      })
      .slice(0, 30)
  }, [commands, query])

  useEffect(() => {
    setSelectedIndex(0)
  }, [query, visibleCommands.length])

  const runCommand = useCallback(async (command: PaletteCommand) => {
    if (executingId) {
      return
    }
    setExecutingId(command.id)
    setNotice({ message: `Running ${command.label}...`, tone: 'info' })
    try {
      await command.run()
      if (command.closeOnRun !== false) {
        close()
      }
    } catch (error) {
      setNotice({
        message: error instanceof Error ? error.message : `Unable to run ${command.label}.`,
        tone: 'error',
      })
    } finally {
      setExecutingId(null)
    }
  }, [close, executingId])

  const handleInputKeyDown = (event: ReactKeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      setSelectedIndex((index) => Math.min(index + 1, Math.max(visibleCommands.length - 1, 0)))
      return
    }
    if (event.key === 'ArrowUp') {
      event.preventDefault()
      setSelectedIndex((index) => Math.max(index - 1, 0))
      return
    }
    if (event.key === 'Enter') {
      event.preventDefault()
      const command = visibleCommands[selectedIndex]
      if (command) {
        void runCommand(command)
      }
    }
  }

  const handleDialogKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault()
      close()
      return
    }

    if (event.key !== 'Tab') {
      return
    }

    const focusable = Array.from(
      dialogRef.current?.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), input:not([disabled]), textarea:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])',
      ) ?? [],
    ).filter((element) => !element.hasAttribute('aria-hidden'))

    if (focusable.length === 0) {
      return
    }

    const first = focusable[0]
    const last = focusable[focusable.length - 1]
    if (!first || !last) {
      return
    }
    const current = document.activeElement

    if (event.shiftKey && current === first) {
      event.preventDefault()
      last.focus()
      return
    }

    if (!event.shiftKey && current === last) {
      event.preventDefault()
      first.focus()
    }
  }

  const activeCommand = visibleCommands[selectedIndex]

  return (
    <>
      <Button
        aria-label="Open command palette"
        className="size-10 px-0 py-0"
        ref={triggerRef}
        title="Open command palette"
        type="button"
        variant="secondary"
        onClick={() => setOpen(true)}
      >
        <Search className="size-4" />
      </Button>

      {open ? (
        <div
          aria-describedby={descriptionId}
          aria-labelledby={titleId}
          aria-modal="true"
          className="fixed inset-0 z-50 flex items-start justify-center bg-[rgba(15,23,42,0.28)] px-4 py-5 backdrop-blur-sm sm:py-[10vh]"
          ref={dialogRef}
          role="dialog"
          onKeyDown={handleDialogKeyDown}
        >
          <div className="w-full max-w-2xl overflow-hidden rounded-[24px] border border-[var(--line-strong)] bg-[rgba(255,255,255,0.96)] shadow-[0_32px_120px_-48px_rgba(8,25,28,0.95)]">
            <h2 id={titleId} className="sr-only">
              Command palette
            </h2>
            <p id={descriptionId} className="sr-only">
              Search and run navigation, feed, risk, and live-session commands. Use arrow keys to move through results.
            </p>
            <div className="flex items-center gap-3 border-b border-[var(--line)] px-4 py-3">
              <Search aria-hidden="true" className="size-4 text-[var(--ink-muted)]" />
              <label htmlFor={inputId} className="sr-only">
                Search commands
              </label>
              <input
                aria-activedescendant={activeCommand ? commandOptionId(activeCommand.id) : undefined}
                aria-autocomplete="list"
                aria-controls={listboxId}
                aria-describedby={notice ? noticeId : descriptionId}
                aria-expanded="true"
                autoComplete="off"
                id={inputId}
                ref={inputRef}
                role="combobox"
                className="h-11 min-w-0 flex-1 bg-transparent text-base text-[var(--ink-strong)] outline-none placeholder:text-[var(--ink-muted)]"
                placeholder="Search routes, bets, feeds, and controls"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                onKeyDown={handleInputKeyDown}
              />
              <Button
                aria-label="Close command palette"
                className="size-9 px-0 py-0"
                type="button"
                variant="ghost"
                onClick={close}
              >
                <X className="size-4" />
              </Button>
            </div>

            {notice ? (
              <div
                aria-live={notice.tone === 'error' ? 'assertive' : 'polite'}
                className={cn(
                  'border-b px-4 py-2 text-sm',
                  notice.tone === 'success' && 'border-emerald-100 bg-emerald-50 text-emerald-800',
                  notice.tone === 'error' && 'border-rose-100 bg-rose-50 text-rose-800',
                  notice.tone === 'info' && 'border-slate-100 bg-slate-50 text-slate-700',
                )}
                id={noticeId}
                role={notice.tone === 'error' ? 'alert' : 'status'}
              >
                {notice.message}
              </div>
            ) : null}

            <div
              aria-busy={loadingContext}
              aria-label="Command results"
              className="max-h-[64vh] overflow-y-auto p-2"
              id={listboxId}
              role="listbox"
            >
              {loadingContext ? (
                <div className="px-3 py-2 text-xs font-semibold uppercase tracking-[0.18em] text-[var(--ink-muted)]">
                  Loading live context
                </div>
              ) : null}

              {visibleCommands.length === 0 ? (
                <div className="rounded-[18px] border border-dashed border-[var(--line-strong)] p-5 text-sm text-[var(--ink-muted)]">
                  No matching commands.
                </div>
              ) : null}

              {visibleCommands.map((command, index) => {
                const previous = visibleCommands[index - 1]
                const showGroup = !previous || previous.group !== command.group
                const Icon = command.icon
                const active = index === selectedIndex
                const running = executingId === command.id

                return (
                  <div key={command.id}>
                    {showGroup ? (
                      <p className="px-3 pb-1 pt-3 text-[11px] font-semibold uppercase tracking-[0.2em] text-[var(--ink-muted)]">
                        {command.group}
                      </p>
                    ) : null}
                    <button
                      aria-selected={active}
                      className={cn(
                        'flex w-full items-center gap-3 rounded-[16px] px-3 py-3 text-left transition-colors',
                        active
                          ? 'bg-[var(--accent-fade)] text-[var(--ink-strong)]'
                          : 'text-[var(--ink)] hover:bg-[rgba(17,37,40,0.05)]',
                      )}
                      id={commandOptionId(command.id)}
                      role="option"
                      type="button"
                      onClick={() => void runCommand(command)}
                      onMouseEnter={() => setSelectedIndex(index)}
                    >
                      <span className="inline-flex size-10 shrink-0 items-center justify-center rounded-xl border border-[var(--line)] bg-[var(--panel)] text-[var(--accent-ink)]">
                        {running ? (
                          <RotateCcw aria-hidden="true" className="size-4 animate-spin" />
                        ) : (
                          <Icon aria-hidden="true" className="size-4" />
                        )}
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-sm font-semibold">{command.label}</span>
                        <span className="mt-0.5 block truncate text-xs text-[var(--ink-muted)]">
                          {command.description}
                        </span>
                      </span>
                    </button>
                  </div>
                )
              })}
            </div>
          </div>
        </div>
      ) : null}
    </>
  )
}

function commandOptionId(commandId: string) {
  return `command-option-${commandId.replace(/[^a-zA-Z0-9_-]+/g, '-')}`
}

function buildBetCommands(session: PaperTradingSession | null, navigate: NavigateFunction): PaletteCommand[] {
  const openBets = session?.openBetsList ?? []

  return openBets.slice(0, 8).map((bet) => ({
    description: describeBet(bet),
    group: 'Bet Management',
    icon: FileSearch2,
    id: `bet.${bet.id}.detail`,
    keywords: ['bet', 'detail', 'evidence', 'prediction', 'history', 'market', bet.eventName, bet.sideName, bet.status],
    label: `Open bet #${bet.id} detail`,
    run: () => navigate(`/user/matches/${bet.id}/evidence`),
  }))
}

function buildAttentionFeedCommands(feeds: OpsFeedsResponse | null, navigate: NavigateFunction): PaletteCommand[] {
  const attentionFeeds = feeds?.feeds
    .filter((feed) => feed.lifecycle === 'ACTIVE' && (feed.status === 'DEGRADED' || feed.status === 'DOWN'))
    .slice(0, 6) ?? []

  return attentionFeeds.map((feed) => ({
    description: describeFeed(feed),
    group: 'Feed Actions',
    icon: RadioTower,
    id: `feed.${feed.sourceId}`,
    keywords: ['feed', feed.sourceId, feed.status, feed.trustTier, ...feed.capabilities],
    label: `Inspect feed ${feed.sourceId}`,
    run: () => navigate(`/admin/feeds?source=${encodeURIComponent(feed.sourceId)}`),
  }))
}

function describeBet(bet: PaperTradeBet) {
  const stake = formatter.format(Number.isFinite(bet.stake) ? bet.stake : 0)
  const odds = Number.isFinite(bet.decimalOdds) ? bet.decimalOdds.toFixed(2) : 'N/A'
  return `${bet.eventName} | ${bet.sideName} | ${stake} @ ${odds}`
}

function describeFeed(feed: OpsFeedStatus) {
  const dlq = feed.dlqDepth > 0 ? `DLQ ${feed.dlqDepth}` : 'DLQ clear'
  const staleness = feed.stalenessSeconds === null ? 'freshness unknown' : `${feed.stalenessSeconds}s stale`
  return `${feed.status.toLowerCase()} | ${dlq} | ${staleness}`
}

function normalize(value: string) {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim()
}

async function postJson<T>(url: string, body: unknown): Promise<T> {
  const response = await fetch(url, {
    body: JSON.stringify(body),
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    method: 'POST',
  })

  if (!response.ok) {
    throw new Error(`Request failed with ${response.status}`)
  }

  return (await response.json()) as T
}
