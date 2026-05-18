import { ArrowRight, ChartNoAxesGantt, Layers2, MonitorPlay, ShieldAlert } from 'lucide-react'
import { Link } from 'react-router-dom'

import { V3Shell } from '@/components/layout/V3Shell'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'

const foundations = [
  {
    title: 'Live Studio shell',
    icon: MonitorPlay,
    detail: 'Navigation, framing, and workspace rhythm are separated from 2.0 before any product migration.',
  },
  {
    title: 'Source-owned UI primitives',
    icon: Layers2,
    detail: 'Button, card, and badge primitives are local files so V3 can evolve without a heavy component framework.',
  },
  {
    title: 'Tailwind v4 tokens',
    icon: ChartNoAxesGantt,
    detail: 'The new shell uses a separate token system and visual language, ready for the Live Studio redesign.',
  },
  {
    title: 'Low-blast-radius rollout',
    icon: ShieldAlert,
    detail: 'This workspace builds alone for now. The current 2.0 UI remains the running product until the mount step.',
  },
]

export function HomeRoute() {
  return (
    <V3Shell
      badges={
        <>
          <Badge variant="accent">Phase 01</Badge>
          <Badge>Workspace Overview</Badge>
        </>
      }
    >
      <section className="grid gap-5 xl:grid-cols-[1.15fr_0.85fr]">
        <Card>
          <CardHeader>
            <Badge variant="accent" className="w-fit">
              Workspace Overview
            </Badge>
            <CardTitle>V3 is mounted and ready for real product surfaces</CardTitle>
            <CardDescription>
              The shell work is behind us. Phase 01 is now about landing operational pages that read real backend
              contracts, starting with ingestion health and source visibility.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-5">
            <div className="grid gap-3 sm:grid-cols-3">
              <Metric label="Current role" value="Workspace" />
              <Metric label="Cutover state" value="Mounted at /v3/*" />
              <Metric label="UI stack" value="React 19 + Tailwind 4" />
            </div>
            <div className="rounded-[24px] border border-[var(--line)] bg-[var(--panel-soft)] p-4">
              <p className="text-sm font-semibold uppercase tracking-[0.24em] text-[var(--ink-muted)]">
                Phase 01 focus
              </p>
              <p className="mt-3 max-w-2xl text-sm leading-7 text-[var(--ink)]">
                Make operational truth visible first: unified feeds, health telemetry, queue pressure, and the data
                quality signals we need before later settlement and prediction promotions.
              </p>
            </div>
            <div className="flex flex-wrap gap-3">
              <Button asChild>
                <Link to="/ops/feeds">
                  Open Ops Feeds
                  <ArrowRight className="size-4" />
                </Link>
              </Button>
              <Button variant="secondary" asChild>
                <Link to="/ops/diffs">
                  Review Settlement Diffs
                  <ArrowRight className="size-4" />
                </Link>
              </Button>
              <Button variant="ghost" asChild>
                <Link to="/review">
                  Open Review Queue
                  <ArrowRight className="size-4" />
                </Link>
              </Button>
              <Button variant="ghost">
                Next up: model lab
                <ArrowRight className="size-4" />
              </Button>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <Badge className="w-fit">Foundations</Badge>
            <CardTitle>What this scaffold already gives us</CardTitle>
            <CardDescription>
              The shell is still lean, but it now has the routing and visual structure we need for the first true V3
              product pages.
            </CardDescription>
          </CardHeader>
          <CardContent className="grid gap-3">
            {foundations.map(({ title, icon: Icon, detail }) => (
              <div
                key={title}
                className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4"
              >
                <div className="flex items-center gap-3">
                  <span className="inline-flex size-10 items-center justify-center rounded-2xl bg-[var(--panel-soft)] text-[var(--accent-ink)]">
                    <Icon className="size-4" />
                  </span>
                  <div>
                    <p className="font-medium text-[var(--ink-strong)]">{title}</p>
                    <p className="mt-1 text-sm leading-6 text-[var(--ink-muted)]">{detail}</p>
                  </div>
                </div>
              </div>
            ))}
          </CardContent>
        </Card>
      </section>
    </V3Shell>
  )
}

type MetricProps = {
  label: string
  value: string
}

function Metric({ label, value }: MetricProps) {
  return (
    <div className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4">
      <p className="text-xs font-semibold uppercase tracking-[0.24em] text-[var(--ink-muted)]">{label}</p>
      <p className="mt-2 font-serif text-2xl font-semibold tracking-[-0.04em] text-[var(--ink-strong)]">{value}</p>
    </div>
  )
}
