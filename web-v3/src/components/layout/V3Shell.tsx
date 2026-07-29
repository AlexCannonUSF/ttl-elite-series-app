import type { ReactNode } from 'react'
import { Activity, Database, Gauge, GitCompareArrows, PanelsTopLeft, Radar, RadioTower, ShieldCheck, Waypoints } from 'lucide-react'
import { NavLink } from 'react-router-dom'

import { Badge } from '@/components/ui/badge'
import { CommandPalette } from '@/features/command-palette/CommandPalette'
import { cn } from '@/lib/utils'

type V3ShellProps = {
  children: ReactNode
  eyebrow?: string
  title?: string
  description?: string
  badges?: ReactNode
  actions?: ReactNode
}

const shellSections = [
  { label: 'Overview', icon: PanelsTopLeft, to: '/' },
  { label: 'Live Board', icon: Activity, to: '/live-board' },
  { label: 'Ops Console', icon: Gauge, to: '/ops' },
  { label: 'Ops Feeds', icon: Radar, to: '/ops/feeds' },
  { label: 'Ops Ingest', icon: RadioTower, to: '/ops/ingest' },
  { label: 'Settlement Diffs', icon: GitCompareArrows, to: '/ops/diffs' },
  { label: 'Review', icon: ShieldCheck, to: '/review' },
  { label: 'Scraper', icon: Database, to: '/ops/scrape' },
  { label: 'Model Lab', icon: Waypoints, disabled: true },
]

export function V3Shell({
  children,
  eyebrow = 'TTLElite Series 3.0',
  title = 'V3 Workspace',
  description = 'Live operating surface for paper-trading, score-truth audit, and ops health.',
  badges = (
    <>
      <Badge variant="accent">Live</Badge>
    </>
  ),
  actions,
}: V3ShellProps) {
  return (
    <div className="min-h-screen bg-[var(--canvas)] text-[var(--ink)]">
      <a className="skip-link" href="#v3-main">
        Skip to main content
      </a>
      <div className="pointer-events-none fixed inset-0 bg-[radial-gradient(circle_at_top_left,rgba(34,197,171,0.14),transparent_34%),radial-gradient(circle_at_top_right,rgba(249,115,22,0.14),transparent_30%),linear-gradient(180deg,rgba(248,244,234,0.96),rgba(241,236,226,1))]" />
      <div className="relative mx-auto flex min-h-screen w-full max-w-[1440px] flex-col px-5 py-5 sm:px-8 lg:px-10">
        <header className="flex flex-col gap-6 rounded-[32px] border border-[var(--line)] bg-[rgba(255,255,255,0.68)] px-5 py-5 shadow-[0_24px_80px_-48px_rgba(8,25,28,0.8)] backdrop-blur lg:px-7">
          <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
            <div className="max-w-3xl">
              <div className="flex items-center gap-3">
                <span className="inline-flex size-11 items-center justify-center rounded-2xl bg-[var(--ink-strong)] text-[var(--canvas)]">
                  <PanelsTopLeft aria-hidden="true" className="size-5" />
                </span>
                <div>
                  <p className="text-xs font-semibold uppercase tracking-[0.32em] text-[var(--ink-muted)]">
                    {eyebrow}
                  </p>
                  <h1 className="font-serif text-4xl font-semibold tracking-[-0.05em] text-[var(--ink-strong)]">
                    {title}
                  </h1>
                </div>
              </div>
              <p className="mt-4 max-w-2xl text-sm leading-7 text-[var(--ink-muted)] sm:text-base">
                {description}
              </p>
            </div>
            <div className="flex flex-wrap items-center gap-3">
              {badges}
              {actions}
              <CommandPalette />
            </div>
          </div>

          <nav aria-label="Primary v3 sections" className="flex flex-wrap gap-3">
            {shellSections.map(({ label, icon: Icon, to, disabled }) => {
              if (!to || disabled) {
                return (
                  <div
                    aria-disabled="true"
                    key={label}
                    className="inline-flex items-center gap-2 rounded-full border border-[var(--line)] bg-[var(--panel)] px-3 py-2 text-sm text-[var(--ink-muted)]"
                  >
                    <Icon aria-hidden="true" className="size-4" />
                    <span>{label}</span>
                  </div>
                )
              }

              return (
                <NavLink
                  key={label}
                  to={to}
                  end={to === '/'}
                  className={({ isActive }) =>
                    cn(
                      'inline-flex items-center gap-2 rounded-full border px-3 py-2 text-sm transition-colors',
                      isActive
                        ? 'border-[var(--accent-soft)] bg-[var(--accent-fade)] text-[var(--accent-ink)]'
                        : 'border-[var(--line)] bg-[var(--panel)] text-[var(--ink-muted)] hover:border-[var(--accent-soft)] hover:text-[var(--ink-strong)]',
                    )
                  }
                >
                  <Icon aria-hidden="true" className="size-4" />
                  <span>{label}</span>
                </NavLink>
              )
            })}
          </nav>
        </header>

        <main id="v3-main" tabIndex={-1} className="mt-6 flex-1">{children}</main>

      </div>
    </div>
  )
}
