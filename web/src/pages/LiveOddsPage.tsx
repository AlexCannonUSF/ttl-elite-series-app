import OpenInNewRoundedIcon from '@mui/icons-material/OpenInNewRounded'
import PlayArrowRoundedIcon from '@mui/icons-material/PlayArrowRounded'
import RefreshRoundedIcon from '@mui/icons-material/RefreshRounded'
import RestartAltRoundedIcon from '@mui/icons-material/RestartAltRounded'
import TimelineRoundedIcon from '@mui/icons-material/TimelineRounded'
import TrendingUpRoundedIcon from '@mui/icons-material/TrendingUpRounded'
import {
  Alert,
  alpha,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  FormControl,
  Grid,
  InputLabel,
  MenuItem,
  Paper,
  Select,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { Link as RouterLink } from 'react-router-dom'
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip as ReTooltip,
  XAxis,
  YAxis,
} from 'recharts'

import { apiClient, apiErrorMessage } from '../lib/api'
import { asDateOnly, asLocalDate, asPct, asSigned, toEpochMillis } from '../lib/format'

function asMoney(value: number) {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    maximumFractionDigits: 2,
  }).format(value)
}

function parseBankrollInput(raw: string): number | null {
  const cleaned = raw.replace(/[^0-9.]/g, '')
  if (!cleaned.trim()) return null
  const parsed = Number(cleaned)
  if (!Number.isFinite(parsed)) return null
  if (parsed < 100 || parsed > 1_000_000) return null
  return parsed
}

function normalizePickToken(value: string | null | undefined) {
  return (value ?? '')
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
}

function fallbackMatchupKey(input: {
  player1Id?: number | null
  player2Id?: number | null
  player1Name: string
  player2Name: string
  startTimeIso?: string | null
}) {
  const p1 = input.player1Id != null ? `id-${input.player1Id}` : normalizePickToken(input.player1Name)
  const p2 = input.player2Id != null ? `id-${input.player2Id}` : normalizePickToken(input.player2Name)
  const [a, b] = p1 <= p2 ? [p1, p2] : [p2, p1]
  const start = normalizePickToken((input.startTimeIso ?? '').slice(0, 16) || 'na')
  return `${a}|${b}|${start}`
}

function fallbackDedupeKey(input: {
  player1Id?: number | null
  player2Id?: number | null
  player1Name: string
  player2Name: string
  startTimeIso?: string | null
  sideName?: string | null
}) {
  const matchup = fallbackMatchupKey(input)
  if (!input.sideName) return matchup
  return `${matchup}|${normalizePickToken(input.sideName)}`
}

function sortByEventTimeAsc<T extends { startTimeIso?: string | null }>(rows: T[]): T[] {
  return [...rows].sort((a, b) => {
    const left = toEpochMillis(a.startTimeIso ?? null)
    const right = toEpochMillis(b.startTimeIso ?? null)
    const leftMs = Number.isNaN(left) ? Number.POSITIVE_INFINITY : left
    const rightMs = Number.isNaN(right) ? Number.POSITIVE_INFINITY : right
    return leftMs - rightMs
  })
}

function scoreForDisplay(score: string | null | undefined): string {
  if (!score || !score.trim()) return '-'
  return score.trim()
}

function scoreSourceLabel(source: string | null | undefined, trackedAfterClose: boolean): string {
  if (trackedAfterClose) return 'TRACKED AFTER CLOSE'
  const normalized = (source ?? '').trim().toUpperCase()
  if (normalized === 'SCORE_FEED') return 'SCORE FEED'
  if (normalized === 'MARKET_BOARD') return 'BOARD FEED'
  return 'Awaiting scoreboard'
}

function settlementSourceLabel(source: string | null | undefined, reason: string | null | undefined): string {
  const normalized = (source ?? '').trim().toUpperCase()
  if (normalized === 'DECISIVE_LIVE_SCORE' || normalized.includes('SCORE_BACKED'))
    return 'DECISIVE LIVE SCORE'
  if (normalized === 'OFFICIAL_RESULT' || normalized.includes('OFFICIAL_RESULT')) return 'OFFICIAL RESULT'
  if (normalized === 'DATABASE_RESULT' || normalized.includes('DATABASE_RESULT')) return 'DATABASE MATCH'
  if (normalized === 'HEURISTIC_FALLBACK' || normalized.includes('HEURISTIC')) return 'HEURISTIC FALLBACK'
  if (normalized === 'TIMEOUT_VOID' || normalized.includes('PRIMARY_VOID')) return 'V3 VOID'
  return reason?.trim() || 'Pending result'
}

function settlementEvidenceLabel(bet: {
  settlementConfidence: number | null
  settlementEvidenceSourceCount: number | null
  settlementCoverageState: string | null
  settlementAmbiguityScore: number | null
}): string | null {
  const parts: string[] = []
  if (bet.settlementConfidence != null) {
    parts.push(`${(bet.settlementConfidence * 100).toFixed(0)}% confidence`)
  }
  if (bet.settlementEvidenceSourceCount != null) {
    const count = bet.settlementEvidenceSourceCount
    parts.push(`${count} evidence source${count === 1 ? '' : 's'}`)
  }
  if (bet.settlementCoverageState) {
    parts.push(`${bet.settlementCoverageState.toLowerCase()} coverage`)
  }
  if (bet.settlementAmbiguityScore != null && bet.settlementAmbiguityScore > 0) {
    parts.push(`${(bet.settlementAmbiguityScore * 100).toFixed(0)}% ambiguity`)
  }
  return parts.length ? parts.join(' • ') : null
}

function closingLineLabel(bet: {
  closingDecimalOdds: number | null
  closingSource: string | null
  closingMarketState: string | null
}): string | null {
  if (bet.closingDecimalOdds == null) return null
  const source = bet.closingSource?.trim() || 'market'
  const state = bet.closingMarketState?.trim().toLowerCase()
  return `Close ${bet.closingDecimalOdds.toFixed(2)} • ${source}${state ? ` • ${state}` : ''}`
}

function trackingStateMeta(trackingState: string | null | undefined): {
  label: string
  color: 'default' | 'success' | 'warning' | 'info'
} {
  const normalized = (trackingState ?? '').trim().toUpperCase()
  if (normalized === 'MARKET_CLOSED_SCORE_TRACKED') return { label: 'TRACKED AFTER CLOSE', color: 'info' }
  if (normalized === 'MARKET_CLOSED_SCORE_STALE') return { label: 'TRACKING STALE', color: 'warning' }
  if (normalized === 'OPEN_SCORE_VISIBLE') return { label: 'SCORE LIVE', color: 'success' }
  if (normalized === 'OPEN_PENDING_SCORE') return { label: 'PENDING SCORE', color: 'default' }
  if (normalized === 'SETTLED') return { label: 'SETTLED', color: 'success' }
  if (normalized === 'VOIDED') return { label: 'VOIDED', color: 'default' }
  if (normalized === 'PUSHED') return { label: 'PUSHED', color: 'default' }
  return { label: normalized || 'UNKNOWN', color: 'default' }
}

function observationSourceKindLabel(value: string | null | undefined): string {
  const normalized = (value ?? '').trim().toUpperCase()
  if (normalized === 'SCORE_FEED') return 'Score Feed'
  if (normalized === 'MARKET_BOARD') return 'Market Board'
  return normalized || 'Unknown'
}

function openBetReasonLabel(bet: {
  trackingState: string | null
  trackedAfterClose: boolean
  lastObservedAt: string | null
  lastObservedScore: string | null
  lastObservedPhase: string | null
  lastMatchCompleted: boolean
  lastObservationResulted: boolean
  lastSourceFeedEventId: string | null
  settlementReason: string | null
  startTimeIso: string | null
  identityLocked: boolean
  identityDriftCount: number
}) {
  const trackingState = (bet.trackingState ?? '').trim().toUpperCase()
  const observedAt = bet.lastObservedAt ? asLocalDate(bet.lastObservedAt) : null
  const startAt = bet.startTimeIso ? asLocalDate(bet.startTimeIso) : null

  if (bet.lastMatchCompleted) {
    return observedAt
      ? `Feed completion captured at ${observedAt}; awaiting settlement confirmation.`
      : 'Feed completion captured; awaiting settlement confirmation.'
  }
  if (bet.lastObservationResulted) {
    return 'Sportsbook marked the market resulted; awaiting the next settlement pass.'
  }
  if (bet.identityDriftCount > 0) {
    return `Identity locked; blocked ${bet.identityDriftCount} conflicting match candidate${bet.identityDriftCount === 1 ? '' : 's'} while waiting for the correct match to finish.`
  }
  if (trackingState === 'MARKET_CLOSED_SCORE_TRACKED') {
    return observedAt
      ? `Market closed, but targeted score tracking is still active from ${observedAt}.`
      : 'Market closed, but targeted score tracking is still active.'
  }
  if (trackingState === 'MARKET_CLOSED_SCORE_STALE') {
    if (bet.lastSourceFeedEventId) {
      return 'Market closed and the last tracked score went stale; waiting on official result confirmation or the next tracked refresh.'
    }
    return 'Last tracked score went stale; waiting on confirmation before we settle.'
  }
  if (trackingState === 'OPEN_SCORE_VISIBLE') {
    return observedAt
      ? `Score is still visible on the board and was last refreshed at ${observedAt}.`
      : 'Score is still visible on the board.'
  }
  if (trackingState === 'OPEN_PENDING_SCORE') {
    return startAt
      ? `Placed and waiting for the first scoreboard observation around ${startAt}.`
      : 'Placed and waiting for the first scoreboard observation.'
  }
  if (bet.trackedAfterClose) {
    return observedAt
      ? `Tracked after market close; last score seen at ${observedAt}.`
      : 'Tracked after market close; waiting for the next score update.'
  }
  if (bet.lastObservedScore || bet.lastObservedPhase) {
    return 'Latest score is on file; waiting for a decisive finish signal.'
  }
  if (bet.settlementReason) {
    return bet.settlementReason
  }
  return 'Open and waiting for the next sportsbook or result confirmation update.'
}

function identityEvidenceLabel(bet: {
  identityLocked: boolean
  identityDriftCount: number
  lockedSourceFeedEventId: string | null
  lockedExternalEventId: string | null
}) {
  if (!bet.identityLocked && bet.identityDriftCount <= 0) return null
  const parts = ['Identity locked']
  if (bet.identityDriftCount > 0) {
    parts.push(`drift blocked x${bet.identityDriftCount}`)
  }
  if (bet.lockedSourceFeedEventId) {
    parts.push(bet.lockedSourceFeedEventId)
  } else if (bet.lockedExternalEventId) {
    parts.push(`event ${bet.lockedExternalEventId}`)
  }
  return parts.join(' • ')
}

function marketVisibilityLabel(displayed: boolean, resulted: boolean): string {
  if (resulted) return 'RESULTED MARKET'
  return displayed ? 'VISIBLE MARKET' : 'HIDDEN / SUSPENDED'
}

function feedEvidenceLabel(
  matchCompleted: boolean,
  sourceFeedCode: string | null | undefined,
  sourceFeedEventId: string | null | undefined
): string {
  const parts = [sourceFeedCode, sourceFeedEventId].filter(Boolean)
  const feed = parts.length ? parts.join(' • ') : null
  if (matchCompleted && feed) return `Feed completed • ${feed}`
  if (matchCompleted) return 'Feed completed'
  if (feed) return feed
  return 'No feed id'
}

function triggerReliabilityLabel(sampleCount: number): string {
  if (sampleCount >= 12) return 'Established'
  if (sampleCount >= 5) return 'Emerging'
  if (sampleCount > 0) return 'Thin sample'
  return 'No settled history'
}

function triggerPerformanceLabel(roiPct: number, calibrationDeltaPct: number): string {
  const roiLabel =
    roiPct >= 8
      ? 'running hot'
      : roiPct >= 2
        ? 'profitable'
        : roiPct <= -10
          ? 'under pressure'
          : roiPct < 0
            ? 'mixed ROI'
            : 'flat ROI'
  const calibrationLabel =
    Math.abs(calibrationDeltaPct) <= 3
      ? 'well-calibrated'
      : calibrationDeltaPct > 0
        ? 'over-calling'
        : 'under-calling'
  return `${roiLabel} • ${calibrationLabel}`
}

function reliabilityBandLabel(value: number | null | undefined): string {
  if (value == null) return 'Unknown'
  if (value >= 0.78) return 'Strong'
  if (value >= 0.6) return 'Solid'
  if (value >= 0.42) return 'Mixed'
  return 'Thin'
}

function reliabilityInsightLine(row: {
  overallReliability?: number | null
  topTrigger?: string | null
  topTriggerReliability?: number | null
  suggestedSideBaselineStability?: number | null
  ratingAgreement?: number | null
}) {
  const parts: string[] = []
  if (row.overallReliability != null) {
    parts.push(`Overall ${asPct(row.overallReliability)} (${reliabilityBandLabel(row.overallReliability)})`)
  }
  if (row.topTrigger && row.topTriggerReliability != null) {
    parts.push(
      `${row.topTrigger} ${asPct(row.topTriggerReliability)} (${reliabilityBandLabel(row.topTriggerReliability)})`
    )
  }
  if (row.suggestedSideBaselineStability != null) {
    parts.push(
      `Baseline ${asPct(row.suggestedSideBaselineStability)} (${reliabilityBandLabel(row.suggestedSideBaselineStability)})`
    )
  }
  if (row.ratingAgreement != null) {
    parts.push(`Model agreement ${asPct(row.ratingAgreement)} (${reliabilityBandLabel(row.ratingAgreement)})`)
  }
  if (!parts.length) {
    return 'Reliability detail not available for this row yet.'
  }
  return parts.join(' • ')
}

function triggerInsightLine(
  trigger:
    | {
        count: number
        roiPct: number
        calibrationDeltaPct: number
        avgConfidenceWidthPct: number
      }
    | null
    | undefined
) {
  if (!trigger) return 'No settled trigger history yet in this session.'
  return `${triggerReliabilityLabel(trigger.count)} • ${trigger.count} bets • ROI ${asSigned(trigger.roiPct, 2)}% • Calib ${asSigned(trigger.calibrationDeltaPct, 2)}% • Avg CI ${asSigned(trigger.avgConfidenceWidthPct, 2)}%`
}

function renderPlayerLink(playerId: number | null | undefined, playerName: string) {
  if (playerId == null) {
    return <Box component="span">{playerName}</Box>
  }
  return (
    <Box
      component={RouterLink}
      sx={{
        color: 'primary.main',
        fontWeight: 700,
        textDecoration: 'none',
        '&:hover': { textDecoration: 'underline' },
      }}
      to={`/players/${playerId}`}
    >
      {playerName}
    </Box>
  )
}

export function LiveOddsPage() {
  const [strategy, setStrategy] = useState<'CONSERVATIVE' | 'AGGRESSIVE'>('CONSERVATIVE')
  const [modelVersion, setModelVersion] = useState<string>('ENSEMBLE')
  const [includeUnresolved, setIncludeUnresolved] = useState(true)
  const [search, setSearch] = useState('')
  const [resetBankrollInput, setResetBankrollInput] = useState('1000')
  const [timelineTarget, setTimelineTarget] = useState<{ eventKey: string; label: string } | null>(null)

  const liveOddsQuery = useQuery({
    queryKey: ['live-odds', strategy, modelVersion, includeUnresolved],
    queryFn: () => apiClient.getLiveStudioBoard(strategy, modelVersion, 80, includeUnresolved),
    refetchInterval: 20000,
  })

  const paperSessionQuery = useQuery({
    queryKey: ['paper-trading-session'],
    queryFn: () => apiClient.getLiveStudioSession(),
    refetchInterval: 15000,
  })

  const completedMatchesQuery = useQuery({
    queryKey: ['paper-trading-completed-matches'],
    queryFn: () => apiClient.getLiveStudioCompletedMatches(3, 150),
    refetchInterval: 20000,
  })

  const liveStudioIntegrityQuery = useQuery({
    queryKey: ['live-studio-integrity'],
    queryFn: () => apiClient.getLiveStudioIntegrity(),
    refetchInterval: 15000,
  })

  const timelineQuery = useQuery({
    queryKey: ['live-studio-timeline', timelineTarget?.eventKey],
    queryFn: () => apiClient.getLiveStudioMatchTimeline(timelineTarget!.eventKey),
    enabled: Boolean(timelineTarget?.eventKey),
    refetchInterval: timelineTarget?.eventKey ? 15000 : false,
  })

  const refreshMutation = useMutation({
    mutationFn: () => apiClient.refreshOddsValueEngine(strategy, modelVersion),
    onSuccess: () => {
      liveOddsQuery.refetch()
      paperSessionQuery.refetch()
      completedMatchesQuery.refetch()
      liveStudioIntegrityQuery.refetch()
    },
  })

  const syncPaperMutation = useMutation({
    mutationFn: () => apiClient.syncLiveStudio(strategy, modelVersion, 80),
    onSuccess: () => {
      liveOddsQuery.refetch()
      paperSessionQuery.refetch()
      completedMatchesQuery.refetch()
      liveStudioIntegrityQuery.refetch()
    },
  })

  const resetPaperMutation = useMutation({
    mutationFn: (startingBankroll: number) =>
      apiClient.resetLiveStudio(startingBankroll, 'Paper Session', true),
    onSuccess: () => {
      paperSessionQuery.refetch()
      completedMatchesQuery.refetch()
      liveStudioIntegrityQuery.refetch()
    },
  })

  const triggerResetSimulation = () => {
    const startingBankroll = parseBankrollInput(resetBankrollInput)
    if (startingBankroll == null) {
      window.alert('Enter a bankroll between $100 and $1,000,000 before resetting.')
      return
    }
    const confirmed = window.confirm(
      `Reset simulation and clear all prior paper picks/history? This starts a new session from ${asMoney(startingBankroll)}.`
    )
    if (!confirmed) return
    resetPaperMutation.mutate(startingBankroll)
  }

  const rows = useMemo(() => {
    const term = search.trim().toLowerCase()
    if (!term) return liveOddsQuery.data ?? []
    return (liveOddsQuery.data ?? []).filter((row) => {
      const blob =
        `${row.eventName} ${row.competitionName} ${row.player1Name} ${row.player2Name}`.toLowerCase()
      return blob.includes(term)
    })
  }, [liveOddsQuery.data, search])

  const session = paperSessionQuery.data
  const openDedupeKeys = useMemo(() => {
    const set = new Set<string>()
    for (const bet of session?.openBetsList ?? []) {
      set.add(
        bet.dedupeKey ??
          fallbackDedupeKey({
            player1Name: bet.player1Name,
            player2Name: bet.player2Name,
            startTimeIso: bet.startTimeIso,
            sideName: bet.sideName,
          })
      )
    }
    return set
  }, [session?.openBetsList])

  const openMatchupKeys = useMemo(() => {
    const set = new Set<string>()
    for (const bet of session?.openBetsList ?? []) {
      set.add(
        bet.eventKey ??
          fallbackMatchupKey({
            player1Name: bet.player1Name,
            player2Name: bet.player2Name,
            startTimeIso: bet.startTimeIso,
          })
      )
    }
    return set
  }, [session])

  const openBetsOffBoard = useMemo(() => {
    if (!session?.openBetsList?.length || !rows.length) return 0
    const rowKeys = new Set(
      rows.map(
        (row) =>
          row.matchupKey ??
          fallbackMatchupKey({
            player1Id: row.player1Id,
            player2Id: row.player2Id,
            player1Name: row.player1Name,
            player2Name: row.player2Name,
            startTimeIso: row.startTimeIso,
          })
      )
    )
    let count = 0
    for (const bet of session.openBetsList) {
      const eventKey =
        bet.eventKey ??
        fallbackMatchupKey({
          player1Name: bet.player1Name,
          player2Name: bet.player2Name,
          startTimeIso: bet.startTimeIso,
        })
      if (!rowKeys.has(eventKey)) count++
    }
    return count
  }, [rows, session])

  const boardDiagnostics = useMemo(() => {
    let liveRows = 0
    let unresolvedRows = 0
    let recommendedRows = 0
    let paperPickRows = 0
    let avgEdgeSum = 0
    let avgEdgeCount = 0
    let avgConfidenceWidthSum = 0
    let avgConfidenceWidthCount = 0
    let longshotRows = 0

    for (const row of rows) {
      if (row.live) liveRows++
      if (row.player1Id == null || row.player2Id == null) unresolvedRows++
      if (row.recommended) recommendedRows++
      if (row.americanOddsPlayer1 > 200 || row.americanOddsPlayer2 > 200) {
        longshotRows++
      }
      if (row.suggestedEdge != null) {
        avgEdgeSum += row.suggestedEdge
        avgEdgeCount++
      }
      if (row.confidenceLow != null && row.confidenceHigh != null) {
        avgConfidenceWidthSum += row.confidenceHigh - row.confidenceLow
        avgConfidenceWidthCount++
      }

      const rowDedupeKey =
        row.suggestedDedupeKey ??
        (row.suggestedSide
          ? fallbackDedupeKey({
              player1Id: row.player1Id,
              player2Id: row.player2Id,
              player1Name: row.player1Name,
              player2Name: row.player2Name,
              startTimeIso: row.startTimeIso,
              sideName: row.suggestedSide,
            })
          : null)
      if (rowDedupeKey && openDedupeKeys.has(rowDedupeKey)) {
        paperPickRows++
      }
    }

    return {
      totalRows: rows.length,
      liveRows,
      unresolvedRows,
      recommendedRows,
      paperPickRows,
      longshotRows,
      averageEdge: avgEdgeCount ? avgEdgeSum / avgEdgeCount : 0,
      averageConfidenceWidth: avgConfidenceWidthCount ? avgConfidenceWidthSum / avgConfidenceWidthCount : 0,
    }
  }, [rows, openDedupeKeys])

  const completedByMatchId = useMemo(() => {
    const index = new Map<
      number,
      {
        winnerName: string
        loserName: string
        score: string
        startTimeIso: string | null
        matchDateIso: string | null
      }
    >()
    for (const row of completedMatchesQuery.data ?? []) {
      if (row.matchId != null) {
        index.set(row.matchId, row)
      }
    }
    return index
  }, [completedMatchesQuery.data])

  const openBetsOrdered = useMemo(
    () => sortByEventTimeAsc(session?.openBetsList ?? []),
    [session?.openBetsList]
  )

  const recentBetsOrdered = useMemo(() => {
    const rows = [...(session?.recentBets ?? [])]
    rows.sort((a, b) => {
      const settledA = toEpochMillis(a.settledAt)
      const settledB = toEpochMillis(b.settledAt)
      if (!Number.isNaN(settledA) || !Number.isNaN(settledB)) {
        const left = Number.isNaN(settledA) ? -1 : settledA
        const right = Number.isNaN(settledB) ? -1 : settledB
        if (left !== right) return right - left
      }
      const placedA = toEpochMillis(a.placedAt)
      const placedB = toEpochMillis(b.placedAt)
      const left = Number.isNaN(placedA) ? 0 : placedA
      const right = Number.isNaN(placedB) ? 0 : placedB
      return right - left
    })
    return rows
  }, [session?.recentBets])

  const settledBetRows = useMemo(
    () => recentBetsOrdered.filter((bet) => bet.status !== 'OPEN').slice(0, 16),
    [recentBetsOrdered]
  )
  const settledSummary = useMemo(() => {
    let won = 0
    let lost = 0
    let voided = 0
    let pnl = 0
    for (const bet of settledBetRows) {
      if (bet.status === 'WON') won++
      else if (bet.status === 'LOST') lost++
      else if (bet.status === 'VOIDED') voided++
      pnl += bet.profitLoss ?? 0
    }
    return { won, lost, voided, pnl }
  }, [settledBetRows])

  const runWatch = useMemo(() => {
    const recent = session?.recentBets ?? []
    const open = session?.openBetsList ?? []
    const settled = recent.filter((bet) => bet.status !== 'OPEN')
    const decisionSettled = settled.filter((bet) => bet.status === 'WON' || bet.status === 'LOST')

    const openExposure = open.reduce((sum, bet) => sum + bet.stake, 0)
    const expectedOpenValue = open.reduce(
      (sum, bet) => sum + (bet.modelProbability * bet.potentialPayout - bet.stake),
      0
    )
    const largestOpenStake = open.reduce((max, bet) => Math.max(max, bet.stake), 0)
    const averageSettledStake = settled.length
      ? settled.reduce((sum, bet) => sum + bet.stake, 0) / settled.length
      : 0
    const averageSettledEdge = settled.length
      ? settled.reduce((sum, bet) => sum + bet.edge, 0) / settled.length
      : 0

    let confidenceCount = 0
    let confidenceWidthSum = 0
    for (const bet of settled) {
      if (bet.confidenceLow != null && bet.confidenceHigh != null) {
        confidenceWidthSum += Math.max(0, bet.confidenceHigh - bet.confidenceLow)
        confidenceCount++
      }
    }
    const averageConfidenceWidth = confidenceCount ? confidenceWidthSum / confidenceCount : 0

    const livePlaced = recent.filter((bet) => bet.liveAtPlacement).length
    const prematchPlaced = Math.max(0, recent.length - livePlaced)
    const positiveOddsCount = recent.filter((bet) => bet.americanOdds > 0).length
    const favoriteOddsCount = recent.filter((bet) => bet.americanOdds < 0).length

    const settlement = {
      decisiveLiveScore: 0,
      targetedCompletion: 0,
      officialResult: 0,
      database: 0,
      heuristic: 0,
      voided: 0,
      other: 0,
    }
    for (const bet of settled) {
      const source = (bet.settlementSource ?? '').trim().toUpperCase()
      const reason = (bet.settlementReason ?? '').trim().toUpperCase()
      if (source === 'DECISIVE_LIVE_SCORE') {
        settlement.decisiveLiveScore++
        if (reason.includes('TARGETED_MATCH_COMPLETED')) {
          settlement.targetedCompletion++
        }
      } else if (source === 'OFFICIAL_RESULT') {
        settlement.officialResult++
      } else if (source === 'DATABASE_RESULT') {
        settlement.database++
      } else if (source === 'HEURISTIC_FALLBACK') {
        settlement.heuristic++
      } else if (source === 'TIMEOUT_VOID' || bet.status === 'VOIDED') {
        settlement.voided++
      } else {
        settlement.other++
      }
    }
    const scoreBackedSettlements = settlement.decisiveLiveScore + settlement.heuristic
    const scoreBackedRate = settled.length ? scoreBackedSettlements / settled.length : 0

    const highEdgeSettled = decisionSettled.filter((bet) => bet.edge >= 0.12)
    const highEdgeWins = highEdgeSettled.filter((bet) => bet.status === 'WON').length
    const highEdgeWinRate = highEdgeSettled.length ? highEdgeWins / highEdgeSettled.length : 0
    const exposureMetrics = session?.exposureMetrics ?? null

    return {
      openExposure,
      expectedOpenValue,
      largestOpenStake,
      averageSettledStake,
      averageSettledEdge,
      averageConfidenceWidth,
      livePlaced,
      prematchPlaced,
      positiveOddsCount,
      favoriteOddsCount,
      scoreBackedSettlements,
      scoreBackedRate,
      settlement,
      highEdgeSettledCount: highEdgeSettled.length,
      highEdgeWinRate,
      exposureMetrics,
    }
  }, [session?.exposureMetrics, session?.openBetsList, session?.recentBets])

  const scoreContinuityWatch = useMemo(() => {
    const open = session?.openBetsList ?? []
    const counts = {
      openPendingScore: 0,
      openScoreVisible: 0,
      marketClosedScoreTracked: 0,
      marketClosedScoreStale: 0,
      other: 0,
    }

    let freshestObservedAt: string | null = null

    for (const bet of open) {
      const state = (bet.trackingState ?? '').trim().toUpperCase()
      if (state === 'OPEN_PENDING_SCORE') {
        counts.openPendingScore++
      } else if (state === 'OPEN_SCORE_VISIBLE') {
        counts.openScoreVisible++
      } else if (state === 'MARKET_CLOSED_SCORE_TRACKED') {
        counts.marketClosedScoreTracked++
      } else if (state === 'MARKET_CLOSED_SCORE_STALE') {
        counts.marketClosedScoreStale++
      } else {
        counts.other++
      }

      if (
        bet.lastObservedAt &&
        (!freshestObservedAt || toEpochMillis(bet.lastObservedAt) > toEpochMillis(freshestObservedAt))
      ) {
        freshestObservedAt = bet.lastObservedAt
      }
    }

    return {
      ...counts,
      totalOpen: open.length,
      freshestObservedAt,
    }
  }, [session?.openBetsList])

  const finishedMatchesOrdered = useMemo(() => {
    const rows = [...(completedMatchesQuery.data ?? [])]
    rows.sort((a, b) => {
      const left = toEpochMillis(a.startTimeIso ?? a.matchDateIso)
      const right = toEpochMillis(b.startTimeIso ?? b.matchDateIso)
      const leftMs = Number.isNaN(left) ? 0 : left
      const rightMs = Number.isNaN(right) ? 0 : right
      return rightMs - leftMs
    })
    return rows
  }, [completedMatchesQuery.data])

  const triggerLookup = useMemo(
    () =>
      new Map((session?.topTriggers ?? []).map((trigger) => [trigger.trigger.trim().toLowerCase(), trigger])),
    [session?.topTriggers]
  )

  const triggerChartData = useMemo(
    () =>
      (session?.topTriggers ?? []).slice(0, 6).map((trigger) => ({
        trigger: trigger.trigger.length > 16 ? `${trigger.trigger.slice(0, 14)}…` : trigger.trigger,
        roi: Number(trigger.roiPct.toFixed(2)),
        bets: trigger.count,
        pnl: Number(trigger.pnl.toFixed(2)),
      })),
    [session?.topTriggers]
  )

  const equityData = useMemo(
    () =>
      (session?.equityCurve ?? []).slice(-50).map((point) => ({
        at: asLocalDate(point.at, { includeTime: true, fallback: 'N/A' }),
        bankroll: Number(point.bankroll.toFixed(2)),
      })),
    [session?.equityCurve]
  )

  const integrity = liveStudioIntegrityQuery.data
  const settledCount = session
    ? session.wins + session.losses + session.pushes + session.voidedBets
    : settledBetRows.length
  const timelineRows = useMemo(() => timelineQuery.data ?? [], [timelineQuery.data])
  const timelineSummary = useMemo(() => {
    if (!timelineRows.length) {
      return {
        trackedAfterCloseCount: 0,
        uniqueSources: 0,
        firstObservedAt: null as string | null,
        lastObservedAt: null as string | null,
        latestScore: null as string | null,
      }
    }
    const trackedAfterCloseCount = timelineRows.filter((row) => row.trackedAfterClose).length
    const uniqueSources = new Set(timelineRows.map((row) => `${row.sourceKind}:${row.source}`)).size
    const firstObservedAt = timelineRows[0]?.observedAt ?? null
    const lastObservation = timelineRows[timelineRows.length - 1] ?? null
    return {
      trackedAfterCloseCount,
      uniqueSources,
      firstObservedAt,
      lastObservedAt: lastObservation?.observedAt ?? null,
      latestScore: lastObservation?.liveScore ?? null,
    }
  }, [timelineRows])

  return (
    <Stack spacing={2}>
      <Paper
        sx={{
          p: 2,
          borderRadius: 4,
          background: (theme) =>
            `linear-gradient(130deg, ${alpha(theme.palette.primary.light, 0.18)} 0%, ${alpha(theme.palette.background.paper, 0.95)} 45%, ${alpha(theme.palette.secondary.light, 0.16)} 100%)`,
        }}
      >
        <Stack spacing={2}>
          <Stack direction={{ md: 'row', xs: 'column' }} justifyContent="space-between" spacing={2}>
            <Stack spacing={0.4}>
              <Typography variant="h4">Live Studio</Typography>
              <Typography color="text.secondary">
                Real-time TTL board, tracked scores, recommendation flow, and paper-trading telemetry in one
                workspace.
              </Typography>
              <Stack direction="row" spacing={0.8}>
                <Chip icon={<TimelineRoundedIcon />} label={`Rows ${rows.length}`} size="small" />
                <Chip
                  icon={<TrendingUpRoundedIcon />}
                  label={`Win rate ${session ? asPct(session.settledWinRate) : 'N/A'}`}
                  size="small"
                  color="primary"
                />
              </Stack>
            </Stack>

            <Stack direction={{ sm: 'row', xs: 'column' }} spacing={1}>
              <Tooltip title="Starting bankroll for the next reset (paper simulation pot).">
                <TextField
                  label="Simulation Pot ($)"
                  onChange={(event) => setResetBankrollInput(event.target.value)}
                  size="small"
                  sx={{ width: { sm: 170, xs: '100%' } }}
                  value={resetBankrollInput}
                />
              </Tooltip>
              <Tooltip title="Clears current paper session history and starts a fresh simulation from your chosen pot amount.">
                <Button
                  color="warning"
                  onClick={triggerResetSimulation}
                  startIcon={<RestartAltRoundedIcon />}
                  variant="contained"
                >
                  Reset
                </Button>
              </Tooltip>
              <Tooltip title="Runs one full cycle: fetch live odds, auto-pick qualified singles, and settle finished bets.">
                <Button
                  color="secondary"
                  onClick={() => syncPaperMutation.mutate()}
                  startIcon={<PlayArrowRoundedIcon />}
                  variant="contained"
                >
                  Sync + Simulate
                </Button>
              </Tooltip>
              <Tooltip title="Refreshes odds feed and stores value opportunities to the database.">
                <Button
                  onClick={() => refreshMutation.mutate()}
                  startIcon={<RefreshRoundedIcon />}
                  variant="contained"
                >
                  Refresh + Persist
                </Button>
              </Tooltip>
            </Stack>
          </Stack>

          <Alert severity="info">
            Live Studio cards are <strong>current-session</strong> views. They show this paper-trading run,
            while adaptive tuning and model thresholds can still inherit bounded learning from prior settled
            history without mixing the reporting window.
          </Alert>

          <Stack direction={{ md: 'row', xs: 'column' }} spacing={1}>
            <Tooltip title="Conservative needs stronger model edge; Aggressive allows more action.">
              <FormControl size="small" sx={{ minWidth: 180 }}>
                <InputLabel id="live-strategy-label">Strategy</InputLabel>
                <Select
                  label="Strategy"
                  labelId="live-strategy-label"
                  onChange={(event) => setStrategy(event.target.value as 'CONSERVATIVE' | 'AGGRESSIVE')}
                  value={strategy}
                >
                  <MenuItem value="CONSERVATIVE">Conservative</MenuItem>
                  <MenuItem value="AGGRESSIVE">Aggressive</MenuItem>
                </Select>
              </FormControl>
            </Tooltip>

            <Tooltip title="Choose which model powers win probabilities and edge grades.">
              <FormControl size="small" sx={{ minWidth: 220 }}>
                <InputLabel id="live-model-label">Model</InputLabel>
                <Select
                  label="Model"
                  labelId="live-model-label"
                  onChange={(event) => setModelVersion(String(event.target.value))}
                  value={modelVersion}
                >
                  <MenuItem value="ENSEMBLE">Ensemble</MenuItem>
                  <MenuItem value="LOGISTIC">Logistic</MenuItem>
                  <MenuItem value="RF_LIKE">RF-Like</MenuItem>
                  <MenuItem value="GBT_LIKE">GBT-Like</MenuItem>
                  <MenuItem value="BASELINE">Baseline</MenuItem>
                </Select>
              </FormControl>
            </Tooltip>

            <Tooltip title="Include rows where names are not mapped to internal players yet.">
              <FormControl size="small" sx={{ minWidth: 190 }}>
                <InputLabel id="live-unresolved-label">Unresolved Players</InputLabel>
                <Select
                  label="Unresolved Players"
                  labelId="live-unresolved-label"
                  onChange={(event) => setIncludeUnresolved(event.target.value === 'true')}
                  value={String(includeUnresolved)}
                >
                  <MenuItem value="true">Show Unresolved</MenuItem>
                  <MenuItem value="false">Hide Unresolved</MenuItem>
                </Select>
              </FormControl>
            </Tooltip>

            <Tooltip title="Filter the board by event name or player name.">
              <TextField
                onChange={(event) => setSearch(event.target.value)}
                placeholder="Search event or player"
                size="small"
                sx={{ maxWidth: 360 }}
                value={search}
              />
            </Tooltip>
          </Stack>
        </Stack>
      </Paper>

      {liveOddsQuery.error ? (
        <Alert severity="error">
          {apiErrorMessage(liveOddsQuery.error, 'Unable to fetch live odds recommendations.')}
        </Alert>
      ) : null}
      {refreshMutation.error ? (
        <Alert severity="error">{apiErrorMessage(refreshMutation.error, 'Persist refresh failed.')}</Alert>
      ) : null}
      {syncPaperMutation.error ? (
        <Alert severity="error">
          {apiErrorMessage(syncPaperMutation.error, 'Paper-trading sync failed.')}
        </Alert>
      ) : null}
      {completedMatchesQuery.error ? (
        <Alert severity="error">
          {apiErrorMessage(completedMatchesQuery.error, 'Completed match log failed to load.')}
        </Alert>
      ) : null}
      {liveStudioIntegrityQuery.error ? (
        <Alert severity="error">
          {apiErrorMessage(liveStudioIntegrityQuery.error, 'Settlement integrity failed to load.')}
        </Alert>
      ) : null}
      {resetPaperMutation.error ? (
        <Alert severity="error">
          {apiErrorMessage(resetPaperMutation.error, 'Reset paper session failed.')}
        </Alert>
      ) : null}
      {resetPaperMutation.data ? (
        <Alert severity="success">
          Simulation reset complete. Session #{resetPaperMutation.data.sessionId} is fresh with no prior picks
          and a starting bankroll of {asMoney(resetPaperMutation.data.startingBankroll)}.
        </Alert>
      ) : null}

      {syncPaperMutation.data ? (
        <Alert severity="success">
          Simulation synced: scanned {syncPaperMutation.data.rowsScanned}, placed{' '}
          {syncPaperMutation.data.betsPlaced}, settled {syncPaperMutation.data.betsSettled}, voided{' '}
          {syncPaperMutation.data.betsVoided}.
        </Alert>
      ) : null}

      <Grid container spacing={2}>
        <Grid size={{ md: 8, xs: 12 }}>
          <Card>
            <CardContent>
              <Stack alignItems="center" direction="row" justifyContent="space-between">
                <Typography variant="h6">Paper Session</Typography>
                <Tooltip title="Close current simulation session and start a new paper bankroll using the amount above.">
                  <Button
                    onClick={triggerResetSimulation}
                    size="small"
                    startIcon={<RestartAltRoundedIcon />}
                    variant="outlined"
                  >
                    Reset ({asMoney(parseBankrollInput(resetBankrollInput) ?? 1000)})
                  </Button>
                </Tooltip>
              </Stack>
              {session ? (
                <Stack spacing={1.1} sx={{ mt: 1.2 }}>
                  <Grid container spacing={1}>
                    <Grid size={{ md: 3, xs: 6 }}>
                      <Typography color="text.secondary" variant="caption">
                        Bankroll
                      </Typography>
                      <Typography sx={{ fontWeight: 700 }}>{asMoney(session.currentBankroll)}</Typography>
                    </Grid>
                    <Grid size={{ md: 3, xs: 6 }}>
                      <Typography color="text.secondary" variant="caption">
                        Realized P&L
                      </Typography>
                      <Typography
                        sx={{
                          fontWeight: 700,
                          color: session.realizedPnl >= 0 ? 'success.main' : 'error.main',
                        }}
                      >
                        {asSigned(session.realizedPnl, 2)}
                      </Typography>
                    </Grid>
                    <Grid size={{ md: 2, xs: 6 }}>
                      <Typography color="text.secondary" variant="caption">
                        ROI
                      </Typography>
                      <Typography sx={{ fontWeight: 700 }}>{asSigned(session.roiPct, 2)}%</Typography>
                    </Grid>
                    <Grid size={{ md: 2, xs: 6 }}>
                      <Typography color="text.secondary" variant="caption">
                        Open Bets
                      </Typography>
                      <Typography sx={{ fontWeight: 700 }}>{session.openBets}</Typography>
                    </Grid>
                    <Grid size={{ md: 2, xs: 6 }}>
                      <Typography color="text.secondary" variant="caption">
                        Win Rate
                      </Typography>
                      <Typography sx={{ fontWeight: 700 }}>{asPct(session.settledWinRate)}</Typography>
                    </Grid>
                  </Grid>
                  <Divider />
                  <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap' }}>
                    <Chip label={`Bets ${session.totalBets}`} size="small" />
                    <Chip label={`Wins ${session.wins}`} size="small" color="success" />
                    <Chip label={`Losses ${session.losses}`} size="small" />
                    <Chip label={`Pushes ${session.pushes}`} size="small" />
                    <Chip label={`Voided ${session.voidedBets}`} size="small" />
                    <Chip label={`Rows Scanned ${session.simulationRowsScanned}`} size="small" />
                    <Chip label={`Staked ${asMoney(session.totalStaked)}`} size="small" />
                    <Chip label={`Returned ${asMoney(session.totalReturned)}`} size="small" />
                    <Chip
                      color="secondary"
                      label={`Auto Sync ${session.lastSyncAt ? asLocalDate(session.lastSyncAt) : 'Pending'}`}
                      size="small"
                    />
                  </Stack>
                </Stack>
              ) : (
                <Typography color="text.secondary" sx={{ mt: 1 }} variant="body2">
                  Loading paper-trading session...
                </Typography>
              )}
            </CardContent>
          </Card>
        </Grid>

        <Grid size={{ md: 4, xs: 12 }}>
          <Card>
            <CardContent>
              <Typography gutterBottom variant="h6">
                Top Win Triggers (Current Session)
              </Typography>
              {!session?.topTriggers?.length ? (
                <Typography color="text.secondary" variant="body2">
                  No settled simulation bets yet.
                </Typography>
              ) : (
                <Stack spacing={0.8}>
                  {session.topTriggers.slice(0, 6).map((trigger) => (
                    <Stack key={trigger.trigger} spacing={0.2}>
                      <Stack alignItems="center" direction="row" justifyContent="space-between">
                        <Typography variant="body2">{trigger.trigger}</Typography>
                        <Chip
                          color={trigger.pnl >= 0 ? 'success' : 'default'}
                          label={`${trigger.count} bets | ${asSigned(trigger.pnl, 2)}`}
                          size="small"
                        />
                      </Stack>
                      <Typography color="text.secondary" variant="caption">
                        {triggerReliabilityLabel(trigger.count)} •{' '}
                        {triggerPerformanceLabel(trigger.roiPct, trigger.calibrationDeltaPct)}
                      </Typography>
                      <Typography color="text.secondary" variant="caption">
                        Win {asPct(trigger.winRate)} • Avg edge {asSigned(trigger.avgEdgePct, 2)}% • ROI{' '}
                        {asSigned(trigger.roiPct, 2)}% • Calib {asSigned(trigger.calibrationDeltaPct, 2)}%
                      </Typography>
                    </Stack>
                  ))}
                </Stack>
              )}
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      <Grid container spacing={2}>
        <Grid size={{ md: 7, xs: 12 }}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Typography gutterBottom variant="h6">
                Bankroll Curve (Latest 50 Settlements)
              </Typography>
              {!equityData.length ? (
                <Typography color="text.secondary" variant="body2">
                  Equity curve will populate after settled simulation bets are recorded.
                </Typography>
              ) : (
                <Box sx={{ height: 220 }}>
                  <ResponsiveContainer height="100%" width="100%">
                    <AreaChart data={equityData}>
                      <defs>
                        <linearGradient id="bankrollGradient" x1="0" x2="0" y1="0" y2="1">
                          <stop offset="5%" stopColor="#0f7f76" stopOpacity={0.36} />
                          <stop offset="95%" stopColor="#0f7f76" stopOpacity={0.06} />
                        </linearGradient>
                      </defs>
                      <CartesianGrid strokeDasharray="3 3" stroke={alpha('#1b2727', 0.16)} />
                      <XAxis dataKey="at" hide />
                      <YAxis tickFormatter={(value) => `$${Math.round(value)}`} width={68} />
                      <ReTooltip formatter={(value) => asMoney(Number(value ?? 0))} />
                      <Area
                        dataKey="bankroll"
                        fill="url(#bankrollGradient)"
                        stroke="#0f7f76"
                        strokeWidth={2.4}
                        type="monotone"
                      />
                    </AreaChart>
                  </ResponsiveContainer>
                </Box>
              )}
            </CardContent>
          </Card>
        </Grid>

        <Grid size={{ md: 5, xs: 12 }}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Typography gutterBottom variant="h6">
                Trigger ROI Snapshot
              </Typography>
              {!triggerChartData.length ? (
                <Typography color="text.secondary" variant="body2">
                  Trigger ROI appears after settled picks are classified by trigger.
                </Typography>
              ) : (
                <Box sx={{ height: 220 }}>
                  <ResponsiveContainer height="100%" width="100%">
                    <BarChart data={triggerChartData}>
                      <CartesianGrid strokeDasharray="3 3" stroke={alpha('#1b2727', 0.15)} />
                      <XAxis dataKey="trigger" interval={0} tick={{ fontSize: 12 }} />
                      <YAxis tickFormatter={(value) => `${value.toFixed(0)}%`} width={58} />
                      <ReTooltip
                        formatter={(value, _name, payload) => {
                          const betCount = (payload?.payload as { bets?: number } | undefined)?.bets
                          return [`${asSigned(Number(value ?? 0), 2)}% (${betCount ?? 0} bets)`, 'ROI']
                        }}
                      />
                      <Bar dataKey="roi" fill="#0f7f76" radius={[8, 8, 2, 2]} />
                    </BarChart>
                  </ResponsiveContainer>
                </Box>
              )}
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      <Grid container spacing={2}>
        <Grid size={{ md: 7, xs: 12 }}>
          <Card>
            <CardContent>
              <Typography gutterBottom variant="h6">
                Board Diagnostics
              </Typography>
              <Grid container spacing={1}>
                <Grid size={{ md: 3, xs: 6 }}>
                  <Typography color="text.secondary" variant="caption">
                    Board Rows
                  </Typography>
                  <Typography sx={{ fontWeight: 700 }}>{boardDiagnostics.totalRows}</Typography>
                </Grid>
                <Grid size={{ md: 3, xs: 6 }}>
                  <Typography color="text.secondary" variant="caption">
                    Live / Upcoming
                  </Typography>
                  <Typography sx={{ fontWeight: 700 }}>
                    {boardDiagnostics.liveRows} /{' '}
                    {Math.max(0, boardDiagnostics.totalRows - boardDiagnostics.liveRows)}
                  </Typography>
                </Grid>
                <Grid size={{ md: 3, xs: 6 }}>
                  <Typography color="text.secondary" variant="caption">
                    Recommended / Picked
                  </Typography>
                  <Typography sx={{ fontWeight: 700 }}>
                    {boardDiagnostics.recommendedRows} / {boardDiagnostics.paperPickRows}
                  </Typography>
                </Grid>
                <Grid size={{ md: 3, xs: 6 }}>
                  <Typography color="text.secondary" variant="caption">
                    Unresolved
                  </Typography>
                  <Typography sx={{ fontWeight: 700 }}>{boardDiagnostics.unresolvedRows}</Typography>
                </Grid>
                <Grid size={{ md: 3, xs: 6 }}>
                  <Typography color="text.secondary" variant="caption">
                    Avg Suggested Edge
                  </Typography>
                  <Typography sx={{ fontWeight: 700 }}>
                    {asSigned(boardDiagnostics.averageEdge * 100, 2)}%
                  </Typography>
                </Grid>
                <Grid size={{ md: 3, xs: 6 }}>
                  <Typography color="text.secondary" variant="caption">
                    Avg Confidence Width
                  </Typography>
                  <Typography sx={{ fontWeight: 700 }}>
                    {asSigned(boardDiagnostics.averageConfidenceWidth * 100, 2)}%
                  </Typography>
                </Grid>
                <Grid size={{ md: 3, xs: 6 }}>
                  <Typography color="text.secondary" variant="caption">
                    Longshot Rows (&gt;+200)
                  </Typography>
                  <Typography sx={{ fontWeight: 700 }}>{boardDiagnostics.longshotRows}</Typography>
                </Grid>
                <Grid size={{ md: 3, xs: 6 }}>
                  <Typography color="text.secondary" variant="caption">
                    Open Bets Off Board
                  </Typography>
                  <Typography sx={{ fontWeight: 700 }}>{openBetsOffBoard}</Typography>
                </Grid>
              </Grid>
            </CardContent>
          </Card>
        </Grid>
        <Grid size={{ md: 5, xs: 12 }}>
          <Card>
            <CardContent>
              <Typography gutterBottom variant="h6">
                Selection Engine Notes
              </Typography>
              <Stack spacing={0.8}>
                <Typography color="text.secondary" variant="body2">
                  Picks are gated by stronger model-over-implied probability gaps, longshot guardrails, and
                  significance-aware reliability weighting.
                </Typography>
                <Typography color="text.secondary" variant="body2">
                  Live pricing blends model + market and applies in-game score context using set score and
                  point score.
                </Typography>
                <Typography color="text.secondary" variant="body2">
                  Finalized matches settle from sportsbook score, then last observed scoreboard fallback when
                  rows disappear; missing-board bets auto-void and refund after timeout.
                </Typography>
                <Typography color="text.secondary" variant="body2">
                  Session cards on this screen are run-only. Adaptive tuning still starts from prior settled
                  history so each new run inherits learning without mixing reporting windows.
                </Typography>
                {session?.adaptiveMetrics ? (
                  <Typography color="text.secondary" variant="body2">
                    Adaptive learning ({session.adaptiveMetrics.sampleSize} settled picks): edge shift{' '}
                    {asSigned(session.adaptiveMetrics.edgeShiftPct, 2)}%, score shift{' '}
                    {asSigned(session.adaptiveMetrics.selectionScoreShift, 2)}, stake multiplier{' '}
                    {session.adaptiveMetrics.stakeMultiplier.toFixed(2)}, calibration drift{' '}
                    {asSigned(session.adaptiveMetrics.calibrationErrorPct, 2)}%.
                  </Typography>
                ) : null}
                {session?.decisionTelemetry ? (
                  <>
                    <Divider sx={{ my: 0.6 }} />
                    <Typography color="text.secondary" variant="body2">
                      Decision telemetry ({session.decisionTelemetry.consideredCount} candidates): placed{' '}
                      <strong>{session.decisionTelemetry.placedCount}</strong>, skipped{' '}
                      <strong>{session.decisionTelemetry.skippedCount}</strong>, placement rate{' '}
                      <strong>{session.decisionTelemetry.placementRatePct.toFixed(1)}%</strong>, avg signal
                      quality <strong>{session.decisionTelemetry.avgSignalQualityPct.toFixed(1)}%</strong>.
                    </Typography>
                    <Typography color="text.secondary" variant="body2">
                      Avg placed edge{' '}
                      <strong>{asSigned(session.decisionTelemetry.avgPlacedEdgePct, 2)}%</strong> vs skipped
                      edge <strong>{asSigned(session.decisionTelemetry.avgSkippedEdgePct, 2)}%</strong>; avg
                      selection score{' '}
                      <strong>{session.decisionTelemetry.avgSelectionScore.toFixed(2)}</strong>.
                    </Typography>
                    <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', mt: 0.3 }}>
                      <Chip label={`Placed ${session.decisionTelemetry.placedCount}`} size="small" />
                      <Chip label={`Skipped ${session.decisionTelemetry.skippedCount}`} size="small" />
                      <Chip
                        color={session.decisionTelemetry.fallbackPlacedCount > 0 ? 'warning' : 'default'}
                        label={`Fallback placed ${session.decisionTelemetry.fallbackPlacedCount}`}
                        size="small"
                      />
                      {session.decisionTelemetry.topSkipReasons.slice(0, 3).map((reason) => (
                        <Chip
                          key={reason.reason}
                          label={`${reason.reason} ${reason.count}`}
                          size="small"
                          variant="outlined"
                        />
                      ))}
                    </Stack>
                  </>
                ) : null}
              </Stack>
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      <Grid container spacing={2}>
        <Grid size={{ md: 6, xs: 12 }}>
          <Card>
            <CardContent>
              <Typography gutterBottom variant="h6">
                Risk & Profit Watch (Current Session)
              </Typography>
              <Grid container spacing={1}>
                <Grid size={{ md: 4, xs: 6 }}>
                  <Typography color="text.secondary" variant="caption">
                    Open Exposure
                  </Typography>
                  <Typography sx={{ fontWeight: 700 }}>{asMoney(runWatch.openExposure)}</Typography>
                  {runWatch.exposureMetrics ? (
                    <Typography color="text.secondary" variant="caption">
                      Cap {asMoney(runWatch.exposureMetrics.openExposureCap)} • Usage{' '}
                      {asPct(runWatch.exposureMetrics.openExposureUsagePct)}
                    </Typography>
                  ) : null}
                </Grid>
                <Grid size={{ md: 4, xs: 6 }}>
                  <Typography color="text.secondary" variant="caption">
                    Expected Open Value
                  </Typography>
                  <Typography
                    sx={{
                      color: runWatch.expectedOpenValue >= 0 ? 'success.main' : 'error.main',
                      fontWeight: 700,
                    }}
                  >
                    {asSigned(runWatch.expectedOpenValue, 2)}
                  </Typography>
                </Grid>
                <Grid size={{ md: 4, xs: 6 }}>
                  <Typography color="text.secondary" variant="caption">
                    Largest Open Stake
                  </Typography>
                  <Typography sx={{ fontWeight: 700 }}>{asMoney(runWatch.largestOpenStake)}</Typography>
                  {runWatch.exposureMetrics?.mostExposedPlayerName ? (
                    <Typography color="text.secondary" variant="caption">
                      Top player {runWatch.exposureMetrics.mostExposedPlayerName} •{' '}
                      {asPct(runWatch.exposureMetrics.mostExposedPlayerCapUsagePct)}
                    </Typography>
                  ) : null}
                </Grid>
                <Grid size={{ md: 4, xs: 6 }}>
                  <Typography color="text.secondary" variant="caption">
                    Avg Settled Stake
                  </Typography>
                  <Typography sx={{ fontWeight: 700 }}>{asMoney(runWatch.averageSettledStake)}</Typography>
                  {runWatch.exposureMetrics?.mostExposedTrigger ? (
                    <Typography color="text.secondary" variant="caption">
                      Top trigger {runWatch.exposureMetrics.mostExposedTrigger} •{' '}
                      {asPct(runWatch.exposureMetrics.mostExposedTriggerCapUsagePct)}
                    </Typography>
                  ) : null}
                </Grid>
                <Grid size={{ md: 4, xs: 6 }}>
                  <Typography color="text.secondary" variant="caption">
                    Avg Settled Edge
                  </Typography>
                  <Typography sx={{ fontWeight: 700 }}>
                    {asSigned(runWatch.averageSettledEdge * 100, 2)}%
                  </Typography>
                </Grid>
                <Grid size={{ md: 4, xs: 6 }}>
                  <Typography color="text.secondary" variant="caption">
                    Avg CI Width
                  </Typography>
                  <Typography sx={{ fontWeight: 700 }}>
                    {asSigned(runWatch.averageConfidenceWidth * 100, 2)}%
                  </Typography>
                </Grid>
              </Grid>
              <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', mt: 1.3 }}>
                <Chip label={`Live placed ${runWatch.livePlaced}`} size="small" />
                <Chip label={`Prematch placed ${runWatch.prematchPlaced}`} size="small" />
                <Chip label={`Favorite odds ${runWatch.favoriteOddsCount}`} size="small" />
                <Chip label={`Plus-money odds ${runWatch.positiveOddsCount}`} size="small" />
                {runWatch.exposureMetrics ? (
                  <Chip
                    color={runWatch.exposureMetrics.openExposureUsagePct >= 0.8 ? 'warning' : 'default'}
                    label={`Exposure cap ${asPct(runWatch.exposureMetrics.openExposureUsagePct)}`}
                    size="small"
                  />
                ) : null}
                {runWatch.exposureMetrics ? (
                  <Chip
                    color={runWatch.exposureMetrics.concurrentOpenBetUsagePct >= 0.8 ? 'warning' : 'default'}
                    label={`Open slots ${session?.openBets ?? 0}/${runWatch.exposureMetrics.maxConcurrentOpenBets}`}
                    size="small"
                  />
                ) : null}
                {runWatch.exposureMetrics && runWatch.exposureMetrics.playerNearCapCount > 0 ? (
                  <Chip
                    color="warning"
                    label={`Player concentration ${runWatch.exposureMetrics.playerNearCapCount}`}
                    size="small"
                  />
                ) : null}
                {runWatch.exposureMetrics && runWatch.exposureMetrics.triggerNearCapCount > 0 ? (
                  <Chip
                    color="warning"
                    label={`Trigger concentration ${runWatch.exposureMetrics.triggerNearCapCount}`}
                    size="small"
                  />
                ) : null}
                <Chip
                  color={runWatch.highEdgeWinRate >= 0.55 ? 'success' : 'default'}
                  label={`High-edge (>=12%) win ${asPct(runWatch.highEdgeWinRate)} on ${runWatch.highEdgeSettledCount} bets`}
                  size="small"
                />
              </Stack>
            </CardContent>
          </Card>
        </Grid>
        <Grid size={{ md: 6, xs: 12 }}>
          <Card>
            <CardContent>
              <Typography gutterBottom variant="h6">
                Score Continuity Watch (Current Session)
              </Typography>
              <Grid container spacing={1}>
                <Grid size={{ md: 4, xs: 6 }}>
                  <Typography color="text.secondary" variant="caption">
                    Open Bets Tracked
                  </Typography>
                  <Typography sx={{ fontWeight: 700 }}>{scoreContinuityWatch.totalOpen}</Typography>
                </Grid>
                <Grid size={{ md: 4, xs: 6 }}>
                  <Typography color="text.secondary" variant="caption">
                    Tracked After Close
                  </Typography>
                  <Typography
                    sx={{
                      color: scoreContinuityWatch.marketClosedScoreTracked > 0 ? 'info.main' : 'text.primary',
                      fontWeight: 700,
                    }}
                  >
                    {scoreContinuityWatch.marketClosedScoreTracked}
                  </Typography>
                </Grid>
                <Grid size={{ md: 4, xs: 6 }}>
                  <Typography color="text.secondary" variant="caption">
                    Tracking Stale
                  </Typography>
                  <Typography
                    sx={{
                      color:
                        scoreContinuityWatch.marketClosedScoreStale > 0 ? 'warning.main' : 'text.primary',
                      fontWeight: 700,
                    }}
                  >
                    {scoreContinuityWatch.marketClosedScoreStale}
                  </Typography>
                </Grid>
                <Grid size={{ md: 4, xs: 6 }}>
                  <Typography color="text.secondary" variant="caption">
                    Score Visible On Board
                  </Typography>
                  <Typography sx={{ fontWeight: 700 }}>{scoreContinuityWatch.openScoreVisible}</Typography>
                </Grid>
                <Grid size={{ md: 4, xs: 6 }}>
                  <Typography color="text.secondary" variant="caption">
                    Pending Score
                  </Typography>
                  <Typography sx={{ fontWeight: 700 }}>{scoreContinuityWatch.openPendingScore}</Typography>
                </Grid>
                <Grid size={{ md: 4, xs: 6 }}>
                  <Typography color="text.secondary" variant="caption">
                    Freshest Score Update
                  </Typography>
                  <Typography sx={{ fontWeight: 700 }}>
                    {scoreContinuityWatch.freshestObservedAt
                      ? asLocalDate(scoreContinuityWatch.freshestObservedAt)
                      : 'N/A'}
                  </Typography>
                </Grid>
              </Grid>
              <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', mt: 1.3 }}>
                <Chip
                  color="info"
                  label={`After close ${scoreContinuityWatch.marketClosedScoreTracked}`}
                  size="small"
                />
                <Chip
                  color="warning"
                  label={`Stale ${scoreContinuityWatch.marketClosedScoreStale}`}
                  size="small"
                />
                <Chip
                  color="success"
                  label={`Score live ${scoreContinuityWatch.openScoreVisible}`}
                  size="small"
                />
                <Chip label={`Pending ${scoreContinuityWatch.openPendingScore}`} size="small" />
                {scoreContinuityWatch.other > 0 ? (
                  <Chip label={`Other ${scoreContinuityWatch.other}`} size="small" />
                ) : null}
              </Stack>
              <Typography color="text.secondary" sx={{ mt: 1.3 }} variant="body2">
                This card tells us whether open bets are still score-visible on the board, being tracked after
                market close, or drifting stale before confirmation.
              </Typography>
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      <Grid container spacing={2}>
        <Grid size={{ md: 12, xs: 12 }}>
          <Card>
            <CardContent>
              <Typography gutterBottom variant="h6">
                Settlement Integrity (Current Session)
              </Typography>
              <Grid container spacing={1}>
                <Grid size={{ md: 4, xs: 6 }}>
                  <Typography color="text.secondary" variant="caption">
                    Score-backed Settlements
                  </Typography>
                  <Typography sx={{ fontWeight: 700 }}>
                    {integrity?.scoreBackedSettlements ?? runWatch.scoreBackedSettlements} (
                    {asPct(
                      settledCount > 0
                        ? (integrity?.scoreBackedSettlements ?? runWatch.scoreBackedSettlements) /
                            settledCount
                        : runWatch.scoreBackedRate
                    )}
                    )
                  </Typography>
                </Grid>
                <Grid size={{ md: 4, xs: 6 }}>
                  <Typography color="text.secondary" variant="caption">
                    Targeted Completion
                  </Typography>
                  <Typography sx={{ fontWeight: 700 }}>
                    {integrity?.targetedCompletionSettlements ?? runWatch.settlement.targetedCompletion}
                  </Typography>
                </Grid>
                <Grid size={{ md: 4, xs: 6 }}>
                  <Typography color="text.secondary" variant="caption">
                    Official Result
                  </Typography>
                  <Typography sx={{ fontWeight: 700 }}>
                    {integrity?.officialResultSettlements ?? runWatch.settlement.officialResult}
                  </Typography>
                </Grid>
                <Grid size={{ md: 4, xs: 6 }}>
                  <Typography color="text.secondary" variant="caption">
                    Heuristic Fallback
                  </Typography>
                  <Typography sx={{ fontWeight: 700 }}>
                    {integrity?.heuristicSettlements ?? runWatch.settlement.heuristic}
                  </Typography>
                </Grid>
                <Grid size={{ md: 4, xs: 6 }}>
                  <Typography color="text.secondary" variant="caption">
                    Database Settlements
                  </Typography>
                  <Typography sx={{ fontWeight: 700 }}>
                    {integrity?.databaseSettlements ?? runWatch.settlement.database}
                  </Typography>
                </Grid>
                <Grid size={{ md: 4, xs: 6 }}>
                  <Typography color="text.secondary" variant="caption">
                    Voided (Timeout)
                  </Typography>
                  <Typography sx={{ fontWeight: 700 }}>
                    {integrity?.voidedSettlements ?? runWatch.settlement.voided}
                  </Typography>
                </Grid>
                <Grid size={{ md: 4, xs: 6 }}>
                  <Typography color="text.secondary" variant="caption">
                    Tracked After Close
                  </Typography>
                  <Typography sx={{ fontWeight: 700 }}>
                    {integrity?.trackedAfterCloseObservations ?? 0}
                  </Typography>
                </Grid>
              </Grid>
              <Typography color="text.secondary" sx={{ mt: 1.3 }} variant="body2">
                Non-decisive scores stay open while the tracked score feed can still observe the match, even
                after the sportsbook market disappears.
              </Typography>
              <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', mt: 1.1 }}>
                <Chip
                  color="info"
                  label={`Targeted completion ${integrity?.targetedCompletionSettlements ?? runWatch.settlement.targetedCompletion}`}
                  size="small"
                />
                <Chip
                  color="success"
                  label={`Score-backed ${integrity?.scoreBackedSettlements ?? runWatch.scoreBackedSettlements}`}
                  size="small"
                />
                <Chip
                  label={`Tracked-after-close obs ${integrity?.trackedAfterCloseObservations ?? 0}`}
                  size="small"
                />
              </Stack>
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      <Card>
        <CardContent>
          <Stack spacing={1}>
            <Typography color="text.secondary" variant="body2">
              Rows: {rows.length} • Last query source: {rows[0]?.source ?? 'N/A'}
              {openBetsOffBoard > 0 ? ` • Open bets not on current board: ${openBetsOffBoard}` : ''}
            </Typography>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Status</TableCell>
                  <TableCell>Event</TableCell>
                  <TableCell>Matchup</TableCell>
                  <TableCell align="right">Market Odds</TableCell>
                  <TableCell align="right">Model vs Implied</TableCell>
                  <TableCell align="right">Best Edge</TableCell>
                  <TableCell align="right">Grade</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {rows.map((row) => {
                  const triggerStats = row.topTrigger
                    ? triggerLookup.get(row.topTrigger.trim().toLowerCase())
                    : undefined
                  const rowMatchupKey =
                    row.matchupKey ??
                    fallbackMatchupKey({
                      player1Id: row.player1Id,
                      player2Id: row.player2Id,
                      player1Name: row.player1Name,
                      player2Name: row.player2Name,
                      startTimeIso: row.startTimeIso,
                    })
                  const rowDedupeKey =
                    row.suggestedDedupeKey ??
                    (row.suggestedSide
                      ? fallbackDedupeKey({
                          player1Id: row.player1Id,
                          player2Id: row.player2Id,
                          player1Name: row.player1Name,
                          player2Name: row.player2Name,
                          startTimeIso: row.startTimeIso,
                          sideName: row.suggestedSide,
                        })
                      : null)
                  const picked = rowDedupeKey != null && openDedupeKeys.has(rowDedupeKey)
                  const hasOpenMatchupBet = openMatchupKeys.has(rowMatchupKey)
                  const recommendedColor = picked
                    ? alpha('#0ea5e9', 0.14)
                    : hasOpenMatchupBet
                      ? alpha('#f59e0b', 0.1)
                      : row.recommended
                        ? alpha('#10b981', 0.1)
                        : 'transparent'
                  return (
                    <TableRow
                      key={`${row.eventName}-${row.player1Name}-${row.player2Name}`}
                      sx={{
                        backgroundColor: recommendedColor,
                        borderLeft: picked
                          ? '3px solid #0284c7'
                          : row.recommended
                            ? '3px solid #059669'
                            : '3px solid transparent',
                      }}
                    >
                      <TableCell>
                        <Stack direction="row" spacing={0.5}>
                          <Chip
                            color={row.live ? 'error' : 'default'}
                            label={row.live ? 'LIVE' : 'UPCOMING'}
                            size="small"
                          />
                          {row.liveScore ? (
                            <Tooltip title="Live score from the sportsbook feed (sets first).">
                              <Chip
                                color="info"
                                label={`SCORE ${row.liveScore}`}
                                size="small"
                                variant="outlined"
                              />
                            </Tooltip>
                          ) : null}
                          {picked ? (
                            <Tooltip title="This side is currently open in the paper-trading simulation.">
                              <Chip color="info" label="PAPER PICK" size="small" />
                            </Tooltip>
                          ) : null}
                          {row.recommended ? (
                            <Tooltip title="Model edge + confidence cleared the recommendation threshold.">
                              <Chip color="success" label="RECOMMENDED" size="small" />
                            </Tooltip>
                          ) : null}
                          {!picked && hasOpenMatchupBet ? (
                            <Tooltip title="There is already an open paper bet on this matchup (possibly opposite side).">
                              <Chip color="warning" label="OPEN BET" size="small" />
                            </Tooltip>
                          ) : null}
                        </Stack>
                      </TableCell>
                      <TableCell>
                        <Stack>
                          <Typography variant="body2">{row.eventName}</Typography>
                          <Typography color="text.secondary" variant="caption">
                            {row.competitionName} •{' '}
                            {row.startTimeIso ? asLocalDate(row.startTimeIso) : 'Time N/A'}
                            {row.liveScore ? ` • Score ${row.liveScore}` : ''}
                            {row.matchPhase ? ` • ${row.matchPhase}` : ''}
                          </Typography>
                        </Stack>
                      </TableCell>
                      <TableCell>
                        <Stack alignItems="center" direction="row" spacing={0.6}>
                          <Typography component="div" variant="body2">
                            {renderPlayerLink(row.player1Id, row.player1Name)} vs{' '}
                            {renderPlayerLink(row.player2Id, row.player2Name)}
                          </Typography>
                          {row.player1Id != null && row.player2Id != null ? (
                            <Tooltip title="Open detailed matchup analysis for these players.">
                              <Button
                                component={RouterLink}
                                size="small"
                                startIcon={<OpenInNewRoundedIcon fontSize="small" />}
                                to={`/matchup?player1Id=${row.player1Id}&player2Id=${row.player2Id}&modelVersion=${encodeURIComponent(modelVersion)}`}
                                variant="text"
                              >
                                Open
                              </Button>
                            </Tooltip>
                          ) : null}
                        </Stack>
                        <Typography color="text.secondary" variant="caption">
                          Suggested:{' '}
                          <Box
                            component="span"
                            sx={{ fontWeight: picked ? 800 : 600, color: picked ? 'info.dark' : 'inherit' }}
                          >
                            {row.suggestedSide ?? 'N/A'}
                          </Box>
                          {row.topTrigger ? ` • Trigger: ${row.topTrigger}` : ''}
                        </Typography>
                        {row.topTrigger ? (
                          <Typography color="text.secondary" variant="caption">
                            Trigger profile: {triggerInsightLine(triggerStats)}
                          </Typography>
                        ) : null}
                        <Typography color="text.secondary" variant="caption">
                          Signal reliability: {reliabilityInsightLine(row)}
                        </Typography>
                        {row.matchupKey ? (
                          <Stack direction="row" justifyContent="flex-start" sx={{ mt: 0.4 }}>
                            <Button
                              onClick={() => {
                                if (!row.matchupKey) return
                                setTimelineTarget({ eventKey: row.matchupKey, label: row.eventName })
                              }}
                              size="small"
                              startIcon={<TimelineRoundedIcon fontSize="small" />}
                              variant="text"
                            >
                              Timeline
                            </Button>
                          </Stack>
                        ) : null}
                      </TableCell>
                      <TableCell align="right">
                        <Typography variant="body2">
                          {row.player1Name}:{' '}
                          {row.americanOddsPlayer1 > 0
                            ? `+${row.americanOddsPlayer1}`
                            : row.americanOddsPlayer1}
                          {row.modelFairAmericanOddsPlayer1 != null
                            ? ` (Fair ${row.modelFairAmericanOddsPlayer1 > 0 ? '+' : ''}${row.modelFairAmericanOddsPlayer1})`
                            : ''}
                        </Typography>
                        <Typography variant="body2">
                          {row.player2Name}:{' '}
                          {row.americanOddsPlayer2 > 0
                            ? `+${row.americanOddsPlayer2}`
                            : row.americanOddsPlayer2}
                          {row.modelFairAmericanOddsPlayer2 != null
                            ? ` (Fair ${row.modelFairAmericanOddsPlayer2 > 0 ? '+' : ''}${row.modelFairAmericanOddsPlayer2})`
                            : ''}
                        </Typography>
                      </TableCell>
                      <TableCell align="right">
                        <Typography variant="body2">
                          {row.modelProbabilityPlayer1 == null
                            ? 'N/A'
                            : `${asPct(row.modelProbabilityPlayer1)} vs ${asPct(row.impliedProbabilityPlayer1)}`}
                        </Typography>
                        <Typography variant="body2">
                          {row.modelProbabilityPlayer2 == null
                            ? 'N/A'
                            : `${asPct(row.modelProbabilityPlayer2)} vs ${asPct(row.impliedProbabilityPlayer2)}`}
                        </Typography>
                      </TableCell>
                      <TableCell align="right">
                        <Typography sx={{ fontWeight: 700 }} variant="body2">
                          {row.suggestedEdge == null ? 'N/A' : asSigned(row.suggestedEdge * 100, 2) + '%'}
                        </Typography>
                        <Typography color="text.secondary" variant="caption">
                          {row.rationale}
                          {row.suggestedFairAmericanOdds != null
                            ? ` • Suggested Fair ${row.suggestedFairAmericanOdds > 0 ? '+' : ''}${row.suggestedFairAmericanOdds}`
                            : ''}
                        </Typography>
                      </TableCell>
                      <TableCell align="right">
                        <Chip
                          color={
                            row.grade === 'A' || row.grade === 'B'
                              ? 'success'
                              : row.grade === 'C'
                                ? 'warning'
                                : 'default'
                          }
                          label={row.grade}
                          size="small"
                        />
                      </TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
            {!rows.length ? (
              <Box>
                <Alert severity="info">
                  No live odds rows available. Try switching unresolved players to Show Unresolved, then
                  verify `hr.segment`/`hr.channel` settings if still empty.
                </Alert>
              </Box>
            ) : null}
          </Stack>
        </CardContent>
      </Card>

      <Card>
        <CardContent>
          <Typography gutterBottom variant="h6">
            Open Bet Ledger
          </Typography>
          {!session?.openBetsList?.length ? (
            <Alert severity="info">No open paper bets right now.</Alert>
          ) : (
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Match Time</TableCell>
                  <TableCell>Matchup</TableCell>
                  <TableCell>Our Side</TableCell>
                  <TableCell align="right">Stake</TableCell>
                  <TableCell align="right">Odds</TableCell>
                  <TableCell align="right">Edge</TableCell>
                  <TableCell align="right">Last Score</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {openBetsOrdered.slice(0, 12).map((bet) => (
                  <TableRow key={bet.id}>
                    <TableCell>
                      <Stack>
                        <Typography variant="body2">{asLocalDate(bet.startTimeIso)}</Typography>
                        <Typography color="text.secondary" variant="caption">
                          Placed {asLocalDate(bet.placedAt)}
                        </Typography>
                      </Stack>
                    </TableCell>
                    <TableCell>
                      <Stack>
                        <Typography variant="body2">{bet.eventName}</Typography>
                        <Typography color="text.secondary" variant="caption">
                          {bet.player1Name} vs {bet.player2Name}
                        </Typography>
                        {bet.topTrigger ? (
                          <Typography color="text.secondary" variant="caption">
                            Trigger {bet.topTrigger} •{' '}
                            {triggerInsightLine(triggerLookup.get(bet.topTrigger.trim().toLowerCase()))}
                          </Typography>
                        ) : null}
                        <Stack direction="row" justifyContent="flex-start" sx={{ mt: 0.4 }}>
                          <Button
                            onClick={() =>
                              setTimelineTarget({ eventKey: bet.eventKey, label: bet.eventName })
                            }
                            size="small"
                            startIcon={<TimelineRoundedIcon fontSize="small" />}
                            variant="text"
                          >
                            Timeline
                          </Button>
                        </Stack>
                      </Stack>
                    </TableCell>
                    <TableCell>
                      <Stack alignItems="center" direction="row" spacing={0.7}>
                        <Chip color="info" label="OUR PICK" size="small" />
                        <Chip
                          color={trackingStateMeta(bet.trackingState).color}
                          label={trackingStateMeta(bet.trackingState).label}
                          size="small"
                          variant="outlined"
                        />
                        <Typography sx={{ fontWeight: 800 }} variant="body2">
                          {bet.sideName}
                        </Typography>
                      </Stack>
                      <Typography color="text.secondary" sx={{ mt: 0.5, maxWidth: 320 }} variant="caption">
                        {openBetReasonLabel(bet)}
                      </Typography>
                    </TableCell>
                    <TableCell align="right">{asMoney(bet.stake)}</TableCell>
                    <TableCell align="right">
                      {bet.americanOdds > 0 ? `+${bet.americanOdds}` : bet.americanOdds}
                    </TableCell>
                    <TableCell align="right">{asSigned(bet.edge * 100, 2)}%</TableCell>
                    <TableCell align="right">
                      <Stack alignItems="flex-end" spacing={0.25}>
                        <Typography variant="body2">{scoreForDisplay(bet.lastObservedScore)}</Typography>
                        <Typography color="text.secondary" variant="caption">
                          {bet.lastObservedPhase ?? 'Awaiting scoreboard'}
                        </Typography>
                        <Typography color="text.secondary" variant="caption">
                          {scoreSourceLabel(bet.lastScoreSource, bet.trackedAfterClose)}
                          {bet.trackingState ? ` • ${trackingStateMeta(bet.trackingState).label}` : ''}
                          {bet.lastObservedAt ? ` • ${asLocalDate(bet.lastObservedAt)}` : ''}
                        </Typography>
                        <Typography color="text.secondary" variant="caption">
                          {marketVisibilityLabel(bet.lastObservationDisplayed, bet.lastObservationResulted)}
                          {bet.lastMatchCompleted ? ' • Feed completed' : ''}
                          {bet.lastSourceFeedEventId ? ` • ${bet.lastSourceFeedEventId}` : ''}
                        </Typography>
                        {identityEvidenceLabel(bet) ? (
                          <Typography color="text.secondary" variant="caption">
                            {identityEvidenceLabel(bet)}
                          </Typography>
                        ) : null}
                        {bet.lastScoreDetail ? (
                          <Typography color="text.secondary" variant="caption">
                            {bet.lastScoreDetail}
                          </Typography>
                        ) : null}
                      </Stack>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardContent>
          <Typography gutterBottom variant="h6">
            Recent Simulation Bets
          </Typography>
          {!session?.recentBets?.length ? (
            <Alert severity="info">
              No simulated bets yet. Press Sync + Simulate to start tracking live performance.
            </Alert>
          ) : (
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Placed</TableCell>
                  <TableCell>Match Time</TableCell>
                  <TableCell>Live Score</TableCell>
                  <TableCell>Result Context</TableCell>
                  <TableCell>Side</TableCell>
                  <TableCell align="right">Stake</TableCell>
                  <TableCell align="right">Odds</TableCell>
                  <TableCell align="right">Edge</TableCell>
                  <TableCell align="right">P&L</TableCell>
                  <TableCell align="right">Status</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {recentBetsOrdered.slice(0, 14).map((bet) => {
                  const triggerStats = bet.topTrigger
                    ? triggerLookup.get(bet.topTrigger.trim().toLowerCase())
                    : undefined
                  const result =
                    bet.resultMatchId != null ? completedByMatchId.get(bet.resultMatchId) : undefined
                  const fallbackOpponent =
                    bet.sideName === bet.player1Name ? bet.player2Name : bet.player1Name
                  const impliedWinner =
                    bet.status === 'WON' ? bet.sideName : bet.status === 'LOST' ? fallbackOpponent : null
                  return (
                    <TableRow key={bet.id}>
                      <TableCell>{asLocalDate(bet.placedAt)}</TableCell>
                      <TableCell>{asLocalDate(bet.startTimeIso)}</TableCell>
                      <TableCell>
                        <Stack>
                          <Typography variant="body2">{scoreForDisplay(bet.lastObservedScore)}</Typography>
                          <Typography color="text.secondary" variant="caption">
                            {settlementSourceLabel(bet.settlementSource, bet.settlementReason)}
                            {bet.lastObservedAt ? ` • ${asLocalDate(bet.lastObservedAt)}` : ''}
                          </Typography>
                          {settlementEvidenceLabel(bet) ? (
                            <Typography color="text.secondary" variant="caption">
                              {settlementEvidenceLabel(bet)}
                            </Typography>
                          ) : null}
                          {closingLineLabel(bet) ? (
                            <Typography color="text.secondary" variant="caption">
                              {closingLineLabel(bet)}
                            </Typography>
                          ) : null}
                          {bet.lastMatchCompleted || bet.lastSourceFeedEventId ? (
                            <Typography color="text.secondary" variant="caption">
                              {feedEvidenceLabel(
                                bet.lastMatchCompleted,
                                bet.lastSourceFeedCode,
                                bet.lastSourceFeedEventId
                              )}
                            </Typography>
                          ) : null}
                          {identityEvidenceLabel(bet) ? (
                            <Typography color="text.secondary" variant="caption">
                              {identityEvidenceLabel(bet)}
                            </Typography>
                          ) : null}
                        </Stack>
                      </TableCell>
                      <TableCell>
                        <Stack>
                          <Typography variant="body2">
                            {result?.winnerName ?? impliedWinner ?? 'Pending result'}
                          </Typography>
                          <Typography color="text.secondary" variant="caption">
                            Score {scoreForDisplay(result?.score ?? bet.lastObservedScore)}
                            {bet.trackingState ? ` • ${trackingStateMeta(bet.trackingState).label}` : ''}
                          </Typography>
                          <Stack direction="row" justifyContent="flex-start" sx={{ mt: 0.4 }}>
                            <Button
                              onClick={() =>
                                setTimelineTarget({ eventKey: bet.eventKey, label: bet.eventName })
                              }
                              size="small"
                              startIcon={<TimelineRoundedIcon fontSize="small" />}
                              variant="text"
                            >
                              Timeline
                            </Button>
                          </Stack>
                        </Stack>
                      </TableCell>
                      <TableCell>
                        <Stack>
                          <Stack alignItems="center" direction="row" spacing={0.7}>
                            <Chip color="info" label="OUR PICK" size="small" />
                            <Chip
                              color={trackingStateMeta(bet.trackingState).color}
                              label={trackingStateMeta(bet.trackingState).label}
                              size="small"
                              variant="outlined"
                            />
                            <Typography sx={{ fontWeight: 800 }} variant="body2">
                              {bet.sideName}
                            </Typography>
                          </Stack>
                          <Typography color="text.secondary" variant="caption">
                            {bet.eventName}
                            {bet.lastScoreSource
                              ? ` • ${scoreSourceLabel(bet.lastScoreSource, bet.trackedAfterClose)}`
                              : ''}
                            {bet.trackingState ? ` • ${trackingStateMeta(bet.trackingState).label}` : ''}
                          </Typography>
                          {bet.topTrigger ? (
                            <Typography color="text.secondary" variant="caption">
                              Trigger {bet.topTrigger} • {triggerInsightLine(triggerStats)}
                            </Typography>
                          ) : null}
                        </Stack>
                      </TableCell>
                      <TableCell align="right">{asMoney(bet.stake)}</TableCell>
                      <TableCell align="right">
                        {bet.americanOdds > 0 ? `+${bet.americanOdds}` : bet.americanOdds}
                      </TableCell>
                      <TableCell align="right">{asSigned(bet.edge * 100, 2)}%</TableCell>
                      <TableCell
                        align="right"
                        sx={{ color: (bet.profitLoss ?? 0) >= 0 ? 'success.main' : 'error.main' }}
                      >
                        {bet.profitLoss == null ? '-' : asSigned(bet.profitLoss, 2)}
                      </TableCell>
                      <TableCell align="right">
                        <Chip
                          color={
                            bet.status === 'WON'
                              ? 'success'
                              : bet.status === 'OPEN'
                                ? 'warning'
                                : bet.status === 'VOIDED'
                                  ? 'info'
                                  : 'default'
                          }
                          label={bet.status}
                          size="small"
                        />
                        {bet.status === 'OPEN' ? (
                          <Typography
                            color="text.secondary"
                            sx={{ mt: 0.5, maxWidth: 240, ml: 'auto' }}
                            variant="caption"
                          >
                            {openBetReasonLabel(bet)}
                          </Typography>
                        ) : null}
                      </TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardContent>
          <Stack
            alignItems={{ sm: 'center', xs: 'flex-start' }}
            direction={{ sm: 'row', xs: 'column' }}
            justifyContent="space-between"
            spacing={1}
            sx={{ mb: 1 }}
          >
            <Typography variant="h6">Settled Bet Tape (Latest Wins/Losses)</Typography>
            <Stack direction="row" spacing={0.7}>
              <Chip color="success" label={`Wins ${settledSummary.won}`} size="small" />
              <Chip label={`Losses ${settledSummary.lost}`} size="small" />
              <Chip color="info" label={`Voided ${settledSummary.voided}`} size="small" />
              <Chip
                color={settledSummary.pnl >= 0 ? 'success' : 'default'}
                label={`Tape P&L ${asSigned(settledSummary.pnl, 2)}`}
                size="small"
              />
            </Stack>
          </Stack>
          {!settledBetRows.length ? (
            <Alert severity="info">No settled picks yet in this session.</Alert>
          ) : (
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Settled</TableCell>
                  <TableCell>Match</TableCell>
                  <TableCell>Our Side</TableCell>
                  <TableCell>Winner</TableCell>
                  <TableCell align="right">Score</TableCell>
                  <TableCell align="right">P&L</TableCell>
                  <TableCell align="right">Status</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {settledBetRows.map((bet) => {
                  const triggerStats = bet.topTrigger
                    ? triggerLookup.get(bet.topTrigger.trim().toLowerCase())
                    : undefined
                  const result =
                    bet.resultMatchId != null ? completedByMatchId.get(bet.resultMatchId) : undefined
                  const opponent = bet.sideName === bet.player1Name ? bet.player2Name : bet.player1Name
                  const winnerName =
                    result?.winnerName ??
                    (bet.status === 'WON' ? bet.sideName : bet.status === 'LOST' ? opponent : 'N/A')
                  return (
                    <TableRow key={`settled-${bet.id}`}>
                      <TableCell>{asLocalDate(bet.settledAt ?? bet.placedAt)}</TableCell>
                      <TableCell>
                        <Stack>
                          <Typography variant="body2">{bet.eventName}</Typography>
                          <Typography color="text.secondary" variant="caption">
                            {asLocalDate(bet.startTimeIso)} • {bet.player1Name} vs {bet.player2Name}
                          </Typography>
                          <Stack direction="row" justifyContent="flex-start" sx={{ mt: 0.4 }}>
                            <Button
                              onClick={() =>
                                setTimelineTarget({ eventKey: bet.eventKey, label: bet.eventName })
                              }
                              size="small"
                              startIcon={<TimelineRoundedIcon fontSize="small" />}
                              variant="text"
                            >
                              Timeline
                            </Button>
                          </Stack>
                        </Stack>
                      </TableCell>
                      <TableCell>
                        <Stack alignItems="flex-start" spacing={0.4}>
                          <Chip color="info" label={bet.sideName} size="small" />
                          {bet.topTrigger ? (
                            <Typography color="text.secondary" variant="caption">
                              Trigger {bet.topTrigger} • {triggerInsightLine(triggerStats)}
                            </Typography>
                          ) : null}
                        </Stack>
                      </TableCell>
                      <TableCell>{winnerName}</TableCell>
                      <TableCell align="right">
                        <Stack alignItems="flex-end" spacing={0.25}>
                          <Typography variant="body2">
                            {scoreForDisplay(result?.score ?? bet.lastObservedScore)}
                          </Typography>
                          <Typography color="text.secondary" variant="caption">
                            {settlementSourceLabel(bet.settlementSource, bet.settlementReason)}
                          </Typography>
                          {settlementEvidenceLabel(bet) ? (
                            <Typography color="text.secondary" variant="caption">
                              {settlementEvidenceLabel(bet)}
                            </Typography>
                          ) : null}
                          {closingLineLabel(bet) ? (
                            <Typography color="text.secondary" variant="caption">
                              {closingLineLabel(bet)}
                            </Typography>
                          ) : null}
                          {bet.lastMatchCompleted || bet.lastSourceFeedEventId ? (
                            <Typography color="text.secondary" variant="caption">
                              {feedEvidenceLabel(
                                bet.lastMatchCompleted,
                                bet.lastSourceFeedCode,
                                bet.lastSourceFeedEventId
                              )}
                            </Typography>
                          ) : null}
                          {identityEvidenceLabel(bet) ? (
                            <Typography color="text.secondary" variant="caption">
                              {identityEvidenceLabel(bet)}
                            </Typography>
                          ) : null}
                        </Stack>
                      </TableCell>
                      <TableCell
                        align="right"
                        sx={{ color: (bet.profitLoss ?? 0) >= 0 ? 'success.main' : 'error.main' }}
                      >
                        {bet.profitLoss == null ? '-' : asSigned(bet.profitLoss, 2)}
                      </TableCell>
                      <TableCell align="right">
                        <Chip
                          color={
                            bet.status === 'WON' ? 'success' : bet.status === 'LOST' ? 'error' : 'default'
                          }
                          label={bet.status}
                          size="small"
                        />
                      </TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardContent>
          <Typography gutterBottom variant="h6">
            Finished Match Log (Picked + Non-Picked)
          </Typography>
          {!finishedMatchesOrdered.length ? (
            <Alert severity="info">No completed matches found in the recent window.</Alert>
          ) : (
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Date/Time</TableCell>
                  <TableCell>Matchup</TableCell>
                  <TableCell>Winner</TableCell>
                  <TableCell>Loser</TableCell>
                  <TableCell align="right">Score</TableCell>
                  <TableCell align="right">Pick</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {finishedMatchesOrdered.slice(0, 30).map((match) => (
                  <TableRow key={match.matchId}>
                    <TableCell>
                      <Stack>
                        <Typography variant="body2">
                          {asLocalDate(match.startTimeIso ?? match.matchDateIso)}
                        </Typography>
                        {!match.startTimeIso ? (
                          <Typography color="text.secondary" variant="caption">
                            Date-only archive entry ({asDateOnly(match.matchDateIso)})
                          </Typography>
                        ) : null}
                      </Stack>
                    </TableCell>
                    <TableCell>
                      <Stack>
                        <Typography variant="body2">{match.eventName}</Typography>
                        <Typography color="text.secondary" variant="caption">
                          {match.player1Name} vs {match.player2Name}
                        </Typography>
                      </Stack>
                    </TableCell>
                    <TableCell>{match.winnerName}</TableCell>
                    <TableCell>{match.loserName}</TableCell>
                    <TableCell align="right">{match.score}</TableCell>
                    <TableCell align="right">
                      {match.picked ? (
                        <Chip
                          color={
                            match.pickStatus === 'WON'
                              ? 'success'
                              : match.pickStatus === 'OPEN'
                                ? 'warning'
                                : match.pickStatus === 'VOIDED'
                                  ? 'info'
                                  : 'default'
                          }
                          label={match.pickStatus ?? 'PICKED'}
                          size="small"
                        />
                      ) : (
                        <Chip label="NOT PICKED" size="small" />
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <Dialog fullWidth maxWidth="lg" onClose={() => setTimelineTarget(null)} open={Boolean(timelineTarget)}>
        <DialogTitle>
          Score Continuity Timeline{timelineTarget ? ` • ${timelineTarget.label}` : ''}
        </DialogTitle>
        <DialogContent dividers>
          {timelineQuery.error ? (
            <Alert severity="error">
              {apiErrorMessage(timelineQuery.error, 'Unable to load score timeline.')}
            </Alert>
          ) : null}
          <Stack spacing={2}>
            <Grid container spacing={1}>
              <Grid size={{ md: 3, xs: 6 }}>
                <Typography color="text.secondary" variant="caption">
                  Observations
                </Typography>
                <Typography sx={{ fontWeight: 700 }}>{timelineRows.length}</Typography>
              </Grid>
              <Grid size={{ md: 3, xs: 6 }}>
                <Typography color="text.secondary" variant="caption">
                  Tracked After Close
                </Typography>
                <Typography sx={{ fontWeight: 700 }}>{timelineSummary.trackedAfterCloseCount}</Typography>
              </Grid>
              <Grid size={{ md: 3, xs: 6 }}>
                <Typography color="text.secondary" variant="caption">
                  Source Mix
                </Typography>
                <Typography sx={{ fontWeight: 700 }}>{timelineSummary.uniqueSources}</Typography>
              </Grid>
              <Grid size={{ md: 3, xs: 6 }}>
                <Typography color="text.secondary" variant="caption">
                  Latest Score
                </Typography>
                <Typography sx={{ fontWeight: 700 }}>
                  {scoreForDisplay(timelineSummary.latestScore)}
                </Typography>
              </Grid>
            </Grid>

            <Grid container spacing={1}>
              <Grid size={{ md: 6, xs: 12 }}>
                <Typography color="text.secondary" variant="caption">
                  First Observed
                </Typography>
                <Typography variant="body2">
                  {timelineSummary.firstObservedAt ? asLocalDate(timelineSummary.firstObservedAt) : 'N/A'}
                </Typography>
              </Grid>
              <Grid size={{ md: 6, xs: 12 }}>
                <Typography color="text.secondary" variant="caption">
                  Last Observed
                </Typography>
                <Typography variant="body2">
                  {timelineSummary.lastObservedAt ? asLocalDate(timelineSummary.lastObservedAt) : 'N/A'}
                </Typography>
              </Grid>
            </Grid>

            {!timelineRows.length && !timelineQuery.isFetching ? (
              <Alert severity="info">
                No tracked observations are stored for this event yet. Once the match is seen on the board or
                by targeted score polling, the timeline will populate here.
              </Alert>
            ) : null}

            {timelineRows.length ? (
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>Observed</TableCell>
                    <TableCell>Score</TableCell>
                    <TableCell>Phase</TableCell>
                    <TableCell>Evidence</TableCell>
                    <TableCell>Source</TableCell>
                    <TableCell>Confidence</TableCell>
                    <TableCell align="right">Tracked</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {timelineRows.map((row) => (
                    <TableRow key={row.id}>
                      <TableCell>{asLocalDate(row.observedAt)}</TableCell>
                      <TableCell>
                        <Stack>
                          <Typography variant="body2">{scoreForDisplay(row.liveScore)}</Typography>
                          <Typography color="text.secondary" variant="caption">
                            {row.externalEventId ? `Event ${row.externalEventId}` : row.eventKey}
                          </Typography>
                        </Stack>
                      </TableCell>
                      <TableCell>{row.matchPhase ?? 'Awaiting scoreboard'}</TableCell>
                      <TableCell>
                        <Stack spacing={0.4}>
                          <Stack direction="row" spacing={0.5}>
                            {row.matchCompleted ? (
                              <Chip color="success" label="COMPLETED" size="small" />
                            ) : null}
                            {row.resulted ? <Chip color="warning" label="RESULTED" size="small" /> : null}
                          </Stack>
                          <Typography color="text.secondary" variant="caption">
                            {feedEvidenceLabel(row.matchCompleted, row.sourceFeedCode, row.sourceFeedEventId)}
                          </Typography>
                          {row.scoreDetail ? (
                            <Typography color="text.secondary" variant="caption">
                              {row.scoreDetail}
                            </Typography>
                          ) : null}
                        </Stack>
                      </TableCell>
                      <TableCell>
                        <Stack>
                          <Typography variant="body2">
                            {observationSourceKindLabel(row.sourceKind)}
                          </Typography>
                          <Typography color="text.secondary" variant="caption">
                            {row.source}
                          </Typography>
                          <Typography color="text.secondary" variant="caption">
                            {marketVisibilityLabel(row.displayed, row.resulted)}
                          </Typography>
                        </Stack>
                      </TableCell>
                      <TableCell>{asPct(row.sourceConfidence)}</TableCell>
                      <TableCell align="right">
                        {row.trackedAfterClose ? (
                          <Chip color="info" label="AFTER CLOSE" size="small" />
                        ) : (
                          <Chip label="ACTIVE MARKET" size="small" />
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            ) : null}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setTimelineTarget(null)}>Close</Button>
        </DialogActions>
      </Dialog>
    </Stack>
  )
}
