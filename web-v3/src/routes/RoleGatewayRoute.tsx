import { useEffect, useState } from 'react'
import {
  Activity,
  ArrowRight,
  BarChart3,
  BrainCircuit,
  ChevronRight,
  CircleDollarSign,
  RadioTower,
  ShieldCheck,
  Sparkles,
} from 'lucide-react'
import { Link } from 'react-router-dom'

import { fetchLiveBoard, fetchLiveSession } from '@/features/live-studio/api'
import type { LiveOddsRecommendation, PaperTradingSession } from '@/features/live-studio/types'

export function RoleGatewayRoute() {
  const [board, setBoard] = useState<LiveOddsRecommendation[]>([])
  const [session, setSession] = useState<PaperTradingSession | null>(null)
  const [connection, setConnection] = useState<'connecting' | 'connected' | 'degraded'>('connecting')

  useEffect(() => {
    const controller = new AbortController()
    void Promise.allSettled([
      fetchLiveBoard({ limit: 40, signal: controller.signal }),
      fetchLiveSession(controller.signal),
    ]).then(([boardResult, sessionResult]) => {
      if (controller.signal.aborted) return
      if (boardResult.status === 'fulfilled') setBoard(boardResult.value)
      if (sessionResult.status === 'fulfilled') setSession(sessionResult.value)
      setConnection(boardResult.status === 'fulfilled' || sessionResult.status === 'fulfilled' ? 'connected' : 'degraded')
    })
    return () => controller.abort()
  }, [])

  const liveCount = board.filter((row) => row.live).length
  const valueCount = board.filter((row) => row.recommended).length

  return (
    <main className="gateway-shell min-h-screen overflow-hidden text-white">
      <div className="gateway-grid pointer-events-none fixed inset-0" />
      <div className="relative mx-auto flex min-h-screen w-full max-w-[1680px] flex-col px-5 py-6 sm:px-8 lg:px-12">
        <header className="flex items-center justify-between border-b border-white/10 pb-5">
          <Link className="inline-flex items-center gap-3" to="/">
            <span className="grid size-11 place-items-center rounded-2xl border border-emerald-300/30 bg-emerald-300/10 text-emerald-200">
              <Activity className="size-5" aria-hidden="true" />
            </span>
            <span>
              <span className="block text-[10px] font-semibold uppercase tracking-[0.34em] text-slate-400">TTLElite</span>
              <span className="block text-base font-semibold tracking-tight">Intelligence Exchange</span>
            </span>
          </Link>
          <div className="hidden items-center gap-2 text-xs text-slate-400 sm:flex">
            <span className="relative flex size-2">
              {connection === 'connected' ? <span className="absolute inline-flex size-full animate-ping rounded-full bg-emerald-400 opacity-60" /> : null}
              <span className={`relative inline-flex size-2 rounded-full ${connection === 'connected' ? 'bg-emerald-400' : connection === 'degraded' ? 'bg-amber-400' : 'bg-slate-500'}`} />
            </span>
            {connection === 'connected' ? 'Live session connected' : connection === 'degraded' ? 'Session data unavailable' : 'Connecting to live session'}
          </div>
        </header>

        <section className="flex flex-1 flex-col justify-center py-12 lg:py-16">
          <div className="mb-9 max-w-4xl">
            <p className="mb-4 inline-flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.3em] text-emerald-300">
              <Sparkles className="size-4" aria-hidden="true" />
              Choose your experience
            </p>
            <h1 className="max-w-3xl text-5xl font-semibold leading-[0.94] tracking-[-0.06em] sm:text-6xl lg:text-7xl">
              One live engine.
              <span className="block text-slate-400">Two ways to see it.</span>
            </h1>
            <p className="mt-6 max-w-2xl text-base leading-7 text-slate-300 sm:text-lg">
              Enter the bettor experience for clear decisions and matchup context, or open the model room for calibration,
              data quality, and operating controls. No login required.
            </p>
          </div>

          <div className="grid gap-5 lg:grid-cols-2">
            <RoleCard
              accent="emerald"
              eyebrow="User sportsbook"
              href="/user"
              icon={CircleDollarSign}
              title="Watch smarter. Decide clearly."
              description="A live-first board with Hard Rock prices, our fair odds, value gaps, score context, form, Elo, head-to-head, and a plain-English bettor read."
              stats={[
                [`${liveCount}`, 'matches live'],
                [`${valueCount}`, 'qualified values'],
                [`${session?.openBets ?? 0}`, 'open positions'],
              ]}
              features={[
                ['Live market room', Activity],
                ['Matchup intelligence', BarChart3],
                ['Decision confidence', ShieldCheck],
              ]}
            />
            <RoleCard
              accent="blue"
              eyebrow="Admin studio"
              href="/admin"
              icon={BrainCircuit}
              title="See every signal and system."
              description="A model and operations cockpit for calibration evidence, trigger performance, thresholds, exposure, feed health, settlement truth, and scraping."
              stats={[
                [session?.status ?? '—', 'session state'],
                [`${session?.totalBets ?? 0}`, 'tracked bets'],
                [session?.lastSyncAt ? 'Synced' : 'Waiting', 'engine status'],
              ]}
              features={[
                ['Model learning audit', BrainCircuit],
                ['Feed and ingest health', RadioTower],
                ['Risk and controls', ShieldCheck],
              ]}
            />
          </div>
        </section>

        <footer className="flex flex-col gap-2 border-t border-white/10 pt-5 text-xs text-slate-500 sm:flex-row sm:items-center sm:justify-between">
          <span>TTLElite Series · Decision intelligence for table tennis markets</span>
          <span>Role switching is always available from the top navigation.</span>
        </footer>
      </div>
    </main>
  )
}

type RoleCardProps = {
  accent: 'emerald' | 'blue'
  eyebrow: string
  href: string
  icon: typeof Activity
  title: string
  description: string
  stats: Array<[string, string]>
  features: Array<[string, typeof Activity]>
}

function RoleCard({ accent, description, eyebrow, features, href, icon: Icon, stats, title }: RoleCardProps) {
  const green = accent === 'emerald'
  return (
    <Link
      className={`group relative min-h-[430px] overflow-hidden rounded-[34px] border p-6 transition duration-300 hover:-translate-y-1 sm:p-8 ${
        green
          ? 'border-emerald-300/20 bg-emerald-300/[0.07] hover:border-emerald-300/45 hover:bg-emerald-300/[0.11]'
          : 'border-blue-300/20 bg-blue-400/[0.07] hover:border-blue-300/45 hover:bg-blue-400/[0.11]'
      }`}
      to={href}
    >
      <div className={`absolute -right-20 -top-20 size-72 rounded-full blur-3xl ${green ? 'bg-emerald-400/10' : 'bg-blue-500/10'}`} />
      <div className="relative flex h-full flex-col">
        <div className="flex items-start justify-between">
          <span className={`grid size-14 place-items-center rounded-2xl border ${green ? 'border-emerald-300/25 bg-emerald-300/10 text-emerald-200' : 'border-blue-300/25 bg-blue-300/10 text-blue-200'}`}>
            <Icon className="size-6" aria-hidden="true" />
          </span>
          <span className={`grid size-11 place-items-center rounded-full border border-white/10 transition group-hover:translate-x-1 ${green ? 'text-emerald-200' : 'text-blue-200'}`}>
            <ArrowRight className="size-5" aria-hidden="true" />
          </span>
        </div>
        <div className="mt-8">
          <p className={`text-xs font-semibold uppercase tracking-[0.28em] ${green ? 'text-emerald-300' : 'text-blue-300'}`}>{eyebrow}</p>
          <h2 className="mt-3 max-w-xl text-3xl font-semibold tracking-[-0.04em] sm:text-4xl">{title}</h2>
          <p className="mt-4 max-w-xl text-sm leading-6 text-slate-300 sm:text-base">{description}</p>
        </div>
        <div className="mt-7 grid grid-cols-3 gap-2">
          {stats.map(([value, label]) => (
            <div className="rounded-2xl border border-white/[0.08] bg-black/10 p-3" key={label}>
              <p className="truncate font-mono text-base font-bold text-white sm:text-lg">{value}</p>
              <p className="mt-1 text-[10px] uppercase tracking-[0.16em] text-slate-500">{label}</p>
            </div>
          ))}
        </div>
        <div className="mt-auto grid gap-2 pt-6">
          {features.map(([label, FeatureIcon]) => (
            <div className="flex items-center justify-between border-t border-white/[0.07] pt-2.5 text-sm text-slate-300" key={label}>
              <span className="flex items-center gap-2">
                <FeatureIcon className="size-4 text-slate-500" aria-hidden="true" />
                {label}
              </span>
              <ChevronRight className="size-4 text-slate-600" aria-hidden="true" />
            </div>
          ))}
        </div>
      </div>
    </Link>
  )
}
