import { useEffect, useId, useRef } from 'react'
import {
  ColorType,
  createChart,
  LineSeries,
  type IChartApi,
  type ISeriesApi,
  type UTCTimestamp,
} from 'lightweight-charts'

import type { LiveBoardHistoryPoint } from '@/features/live-studio/types'

type OddsSparkChartProps = {
  player1Name: string
  player2Name: string
  points: LiveBoardHistoryPoint[]
}

export function OddsSparkChart({ player1Name, player2Name, points }: OddsSparkChartProps) {
  const containerRef = useRef<HTMLDivElement | null>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const player1SeriesRef = useRef<ISeriesApi<'Line'> | null>(null)
  const player2SeriesRef = useRef<ISeriesApi<'Line'> | null>(null)
  const descriptionId = useId()

  useEffect(() => {
    const container = containerRef.current
    if (!container) {
      return
    }

    const chart = createChart(container, {
      autoSize: true,
      grid: {
        horzLines: { color: 'rgba(17,37,40,0.07)' },
        vertLines: { color: 'rgba(17,37,40,0.04)' },
      },
      layout: {
        background: { color: 'transparent', type: ColorType.Solid },
        textColor: '#6a7f82',
      },
      localization: {
        priceFormatter: (price: number) => price.toFixed(2),
      },
      rightPriceScale: {
        borderColor: 'rgba(17,37,40,0.12)',
        scaleMargins: {
          bottom: 0.18,
          top: 0.18,
        },
      },
      timeScale: {
        borderColor: 'rgba(17,37,40,0.12)',
        secondsVisible: true,
        timeVisible: true,
      },
    })

    chartRef.current = chart
    player1SeriesRef.current = chart.addSeries(LineSeries, {
      color: '#0f766e',
      lineWidth: 2,
      priceLineVisible: false,
    })
    player2SeriesRef.current = chart.addSeries(LineSeries, {
      color: '#b45309',
      lineWidth: 2,
      priceLineVisible: false,
    })
    window.requestAnimationFrame(() => {
      removeChartFromTabOrder(container)
    })

    return () => {
      chart.remove()
      chartRef.current = null
      player1SeriesRef.current = null
      player2SeriesRef.current = null
    }
  }, [])

  useEffect(() => {
    const normalized = normalizePoints(points)
    player1SeriesRef.current?.setData(normalized.map((point) => ({ time: point.time, value: point.player1Odds })))
    player2SeriesRef.current?.setData(normalized.map((point) => ({ time: point.time, value: point.player2Odds })))
    chartRef.current?.timeScale().fitContent()
    const container = containerRef.current
    if (container) {
      window.requestAnimationFrame(() => removeChartFromTabOrder(container))
    }
  }, [points])

  return (
    <div
      aria-describedby={descriptionId}
      aria-label={`Odds movement chart for ${player1Name} and ${player2Name}`}
      className="min-h-[260px] overflow-hidden rounded-[20px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)]"
      role="group"
    >
      <p id={descriptionId} className="sr-only">
        The chart plots recent decimal odds for both players from the local live-board polling history.
      </p>
      <div className="flex flex-wrap items-center gap-3 border-b border-[var(--line)] px-4 py-3 text-xs font-semibold uppercase tracking-[0.18em] text-[var(--ink-muted)]">
        <span className="inline-flex items-center gap-2">
          <span aria-hidden="true" className="size-2 rounded-full bg-teal-700" />
          {player1Name}
        </span>
        <span className="inline-flex items-center gap-2">
          <span aria-hidden="true" className="size-2 rounded-full bg-amber-700" />
          {player2Name}
        </span>
      </div>
      <div ref={containerRef} className="h-[245px] w-full" />
    </div>
  )
}

function normalizePoints(points: LiveBoardHistoryPoint[]) {
  const sorted = [...points]
    .filter((point) => (
      Number.isFinite(point.time)
      && Number.isFinite(point.player1Odds)
      && Number.isFinite(point.player2Odds)
    ))
    .sort((left, right) => left.time - right.time)

  const unique = new Map<number, LiveBoardHistoryPoint & { time: UTCTimestamp }>()
  for (const point of sorted) {
    unique.set(point.time, {
      ...point,
      time: point.time as UTCTimestamp,
    })
  }
  return [...unique.values()]
}

function removeChartFromTabOrder(container: HTMLDivElement) {
  container.querySelectorAll<HTMLElement>('canvas, [tabindex]').forEach((element) => {
    element.setAttribute('aria-hidden', 'true')
    element.setAttribute('tabindex', '-1')
  })
}
