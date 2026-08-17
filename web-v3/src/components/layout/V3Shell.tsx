import type { ReactNode } from 'react'
import {
  Activity,
  BrainCircuit,
  CircleDollarSign,
  Gauge,
  House,
  Star,
  Rewind,
  Rows3,
  Trophy,
  Users,
  WalletCards,
  Waves,
} from 'lucide-react'
import { Link, NavLink, useLocation } from 'react-router-dom'

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

const userSections = [
  { label: 'Live', icon: Activity, to: '/user' },
  { label: 'Simulation', icon: WalletCards, to: '/user/simulation' },
  { label: 'Results', icon: Trophy, to: '/user/results' },
  { label: 'Players', icon: Users, to: '/user/players' },
  { label: 'Watchlist', icon: Star, to: '/user/watchlist' },
]

const adminSections = [
  { label: 'Command', icon: Gauge, to: '/admin' },
  { label: 'Runs', icon: Rows3, to: '/admin/runs' },
  { label: 'Replay Lab', icon: Rewind, to: '/admin/replay' },
  { label: 'Model Lab', icon: BrainCircuit, to: '/admin/model-quality' },
  { label: 'Data & Ops', icon: Waves, to: '/admin/ops' },
]

function inferAdmin(pathname: string) {
  return pathname.startsWith('/admin')
    || pathname.startsWith('/ops')
    || pathname.startsWith('/review')
    || pathname.startsWith('/ml/')
}

export function V3Shell({
  children,
  eyebrow,
  title,
  description,
  badges = <Badge variant="accent">Live</Badge>,
  actions,
}: V3ShellProps) {
  const { pathname } = useLocation()
  const admin = inferAdmin(pathname)
  const sections = admin ? adminSections : userSections
  const workspace = admin ? 'Model & Operations' : 'Sportsbook Intelligence'

  return (
    <div className={cn('role-shell min-h-screen text-[var(--ink)]', admin ? 'theme-admin' : 'theme-user')}>
      <a className="skip-link" href="#v3-main">Skip to main content</a>
      <div className="role-atmosphere pointer-events-none fixed inset-0" />
      <div className="relative mx-auto flex min-h-screen w-full max-w-[1800px] flex-col px-4 py-4 sm:px-6 lg:px-8">
        <header className="role-header rounded-[26px] border border-white/10 px-4 py-4 shadow-2xl shadow-black/20 backdrop-blur-xl sm:px-5">
          <div className="flex flex-col gap-4 2xl:flex-row 2xl:items-center 2xl:justify-between">
            <div className="flex min-w-0 items-center gap-3">
              <Link
                className={cn(
                  'grid size-11 shrink-0 place-items-center rounded-2xl border',
                  admin
                    ? 'border-blue-300/25 bg-blue-300/10 text-blue-200'
                    : 'border-emerald-300/25 bg-emerald-300/10 text-emerald-200',
                )}
                to="/"
                aria-label="Choose workspace"
              >
                {admin ? <BrainCircuit className="size-5" aria-hidden="true" /> : <CircleDollarSign className="size-5" aria-hidden="true" />}
              </Link>
              <div className="min-w-0">
                <p className={cn(
                  'truncate text-[10px] font-semibold uppercase tracking-[0.3em]',
                  admin ? 'text-blue-300' : 'text-emerald-300',
                )}>
                  {eyebrow ?? workspace}
                </p>
                <div className="flex items-center gap-3">
                  <h1 className="truncate text-xl font-semibold tracking-[-0.035em] text-white sm:text-2xl">{title ?? workspace}</h1>
                  <span className="hidden items-center gap-1.5 rounded-full border border-white/10 bg-white/[0.04] px-2 py-1 text-[10px] font-semibold uppercase tracking-[0.16em] text-slate-400 sm:inline-flex">
                    <span className={cn('size-1.5 rounded-full', admin ? 'bg-blue-400' : 'animate-pulse bg-emerald-400')} />
                    {admin ? 'Operator' : 'Live'}
                  </span>
                </div>
              </div>
            </div>

            <nav aria-label={`${workspace} sections`} className="hide-scrollbar -mx-1 flex gap-1 overflow-x-auto px-1">
              {sections.map(({ label, icon: Icon, to }) => (
                <NavLink
                  key={label}
                  to={to}
                  end={to === '/user' || to === '/admin'}
                  className={({ isActive }) => cn(
                    'inline-flex shrink-0 items-center gap-2 rounded-xl border px-3 py-2 text-xs font-semibold transition',
                    isActive
                      ? admin
                        ? 'border-blue-300/25 bg-blue-300/12 text-blue-100'
                        : 'border-emerald-300/25 bg-emerald-300/12 text-emerald-100'
                      : 'border-transparent text-slate-400 hover:border-white/10 hover:bg-white/[0.04] hover:text-white',
                  )}
                >
                  <Icon className="size-3.5" aria-hidden="true" />
                  {label}
                </NavLink>
              ))}
            </nav>

            <div className="role-shell-actions flex flex-wrap items-center gap-2">
              {badges}
              {actions}
              {admin ? <CommandPalette /> : null}
              <Link
                className="inline-flex items-center gap-2 rounded-xl border border-white/10 bg-white/[0.04] px-3 py-2 text-xs font-semibold text-slate-300 transition hover:bg-white/[0.08] hover:text-white"
                to="/"
              >
                <House className="size-3.5" aria-hidden="true" />
                Switch
              </Link>
            </div>
          </div>
          {description ? (
            <p className="mt-3 max-w-4xl border-t border-white/[0.07] pt-3 text-xs leading-5 text-slate-400 sm:text-sm">
              {description}
            </p>
          ) : null}
        </header>

        <main id="v3-main" tabIndex={-1} className="mt-5 flex-1">{children}</main>
      </div>
    </div>
  )
}
