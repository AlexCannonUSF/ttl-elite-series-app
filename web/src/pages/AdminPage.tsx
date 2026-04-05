import PlayArrowRoundedIcon from '@mui/icons-material/PlayArrowRounded'
import SaveRoundedIcon from '@mui/icons-material/SaveRounded'
import {
  Alert,
  Button,
  Card,
  CardContent,
  Chip,
  Divider,
  FormControl,
  Grid,
  InputLabel,
  List,
  ListItem,
  ListItemText,
  MenuItem,
  Select,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
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
import { asDurationSeconds, asLocalDate, asPct, toEpochMillis } from '../lib/format'

export function AdminPage() {
  const [runStatusFilter, setRunStatusFilter] = useState<string>('')
  const [runModeFilter, setRunModeFilter] = useState<string>('')
  const [dryRunPage, setDryRunPage] = useState<number>(1)
  const [rangeFromPage, setRangeFromPage] = useState<number>(1)
  const [rangeToPage, setRangeToPage] = useState<number>(3)

  const statusQuery = useQuery({
    queryKey: ['scrape-status'],
    queryFn: apiClient.getScrapeStatus,
    refetchInterval: 5000,
  })

  const metricsQuery = useQuery({
    queryKey: ['scrape-metrics'],
    queryFn: () => apiClient.getScrapeMetrics(250),
  })

  const runsQuery = useQuery({
    queryKey: ['scrape-runs', runStatusFilter, runModeFilter],
    queryFn: () =>
      apiClient.getScrapeRuns({
        limit: 50,
        mode: runModeFilter || undefined,
        status: runStatusFilter || undefined,
      }),
  })

  const errorsQuery = useQuery({
    queryKey: ['scrape-errors'],
    queryFn: () => apiClient.getScrapeErrors(50),
  })

  const aliasesQuery = useQuery({
    queryKey: ['aliases'],
    queryFn: () => apiClient.getAliases(),
  })

  const liveSessionQuery = useQuery({
    queryKey: ['admin-live-session'],
    queryFn: () => apiClient.getLiveStudioSession(),
  })

  const integrityQuery = useQuery({
    queryKey: ['admin-live-integrity'],
    queryFn: () => apiClient.getLiveStudioIntegrity(),
  })

  const runScrapeMutation = useMutation({
    mutationFn: apiClient.runScrape,
    onSuccess: () => {
      statusQuery.refetch()
      runsQuery.refetch()
      metricsQuery.refetch()
    },
  })

  const runRangeMutation = useMutation({
    mutationFn: () => apiClient.runScrapeRange(rangeFromPage, rangeToPage),
    onSuccess: () => {
      statusQuery.refetch()
      runsQuery.refetch()
      metricsQuery.refetch()
    },
  })

  const backfillMutation = useMutation({
    mutationFn: apiClient.backfillMatchResults,
    onSuccess: () => {
      runsQuery.refetch()
      metricsQuery.refetch()
      integrityQuery.refetch()
      liveSessionQuery.refetch()
    },
  })

  const dryRunMutation = useMutation({
    mutationFn: () => apiClient.dryRunScrapeSelectors(dryRunPage),
  })

  const modeOptions = useMemo(() => {
    const fromRuns = (runsQuery.data ?? []).map((r) => r.mode)
    return ['AUTO', 'PAGE_RANGE', 'SINGLE_POST', ...fromRuns].filter(
      (mode, index, arr) => mode && arr.indexOf(mode) === index
    )
  }, [runsQuery.data])

  const runHealth = useMemo(() => {
    const runs = runsQuery.data ?? []
    if (!runs.length) {
      return {
        total: 0,
        success: 0,
        failed: 0,
        running: 0,
        avgSaved: 0,
        avgDurationSeconds: 0,
      }
    }
    let success = 0
    let failed = 0
    let running = 0
    let totalSaved = 0
    let totalDurationSeconds = 0
    let durationCount = 0
    for (const run of runs) {
      if (run.status === 'SUCCESS') success++
      else if (run.status === 'FAILED') failed++
      else if (run.status === 'RUNNING') running++
      totalSaved += run.savedMatches
      const started = toEpochMillis(run.startedAt)
      const finished = toEpochMillis(run.finishedAt)
      if (!Number.isNaN(started) && !Number.isNaN(finished) && finished >= started) {
        totalDurationSeconds += (finished - started) / 1000
        durationCount++
      }
    }
    return {
      total: runs.length,
      success,
      failed,
      running,
      avgSaved: totalSaved / runs.length,
      avgDurationSeconds: durationCount ? totalDurationSeconds / durationCount : 0,
    }
  }, [runsQuery.data])

  const runTrend = useMemo(() => {
    const runs = [...(runsQuery.data ?? [])].sort((a, b) => a.runId - b.runId).slice(-20)
    return runs.map((run) => {
      const started = toEpochMillis(run.startedAt)
      const finished = toEpochMillis(run.finishedAt)
      const durationSeconds =
        Number.isNaN(started) || Number.isNaN(finished) || finished < started
          ? 0
          : (finished - started) / 1000
      return {
        run: `#${run.runId}`,
        saved: run.savedMatches,
        duration: Number(durationSeconds.toFixed(1)),
      }
    })
  }, [runsQuery.data])

  const errorModeData = useMemo(() => {
    const counts = new Map<string, number>()
    for (const err of errorsQuery.data ?? []) {
      const key = err.mode || 'UNKNOWN'
      counts.set(key, (counts.get(key) ?? 0) + 1)
    }
    return [...counts.entries()]
      .map(([mode, count]) => ({ mode, count }))
      .sort((left, right) => right.count - left.count)
      .slice(0, 6)
  }, [errorsQuery.data])

  const session = liveSessionQuery.data
  const integrity = integrityQuery.data
  const settledCount = session ? session.wins + session.losses + session.pushes + session.voidedBets : 0
  const [nowMs, setNowMs] = useState<number | null>(null)

  useEffect(() => {
    const syncNow = () => setNowMs(Date.now())
    syncNow()
    const timer = window.setInterval(syncNow, 60_000)
    return () => window.clearInterval(timer)
  }, [])

  const syncAgeMinutes = useMemo(() => {
    if (nowMs == null) return null
    const at = toEpochMillis(session?.lastSyncAt ?? null)
    if (Number.isNaN(at)) return null
    return Math.max(0, (nowMs - at) / 60000)
  }, [nowMs, session?.lastSyncAt])

  const liveOddsHealth = buildHealthSignal(
    session
      ? syncAgeMinutes == null
        ? 0.2
        : syncAgeMinutes <= 5
          ? 0.9
          : syncAgeMinutes <= 20
            ? 0.6
            : 0.25
      : 0.15
  )
  const trackedScoreHealth = buildHealthSignal(
    integrity
      ? clamp01(
          0.45 * ratio(integrity.scoreFeedObservations, integrity.trackedObservations || 1) +
            0.35 *
              ratio(integrity.trackedAfterCloseObservations, Math.max(1, integrity.scoreFeedObservations)) +
            0.2 * ratio(integrity.targetedCompletionSettlements, Math.max(1, settledCount))
        )
      : 0.15
  )
  const officialResultHealth = buildHealthSignal(
    integrity
      ? clamp01(
          0.55 * ratio(integrity.officialResultSettlements, Math.max(1, settledCount)) +
            0.45 * ((metricsQuery.data?.successRate ?? 0) > 0.85 ? 1 : (metricsQuery.data?.successRate ?? 0))
        )
      : 0.2
  )
  const fallbackReliance = buildHealthSignal(
    integrity
      ? clamp01(
          1 -
            ratio(
              integrity.databaseSettlements + integrity.heuristicSettlements + integrity.voidedSettlements,
              Math.max(1, settledCount)
            )
        )
      : 0.2
  )

  const pipelineReadout = [
    {
      label: 'Fetch',
      detail: statusQuery.data?.running
        ? `Scraper running in ${statusQuery.data.mode}`
        : `Last scrape ${asLocalDate(metricsQuery.data?.lastRunAt ?? null)}`,
      chip: statusQuery.data?.running ? 'Active' : 'Idle',
    },
    {
      label: 'Parse',
      detail: `${errorsQuery.data?.length ?? 0} recent parse/system errors logged`,
      chip: `${runHealth.failed} failed runs`,
    },
    {
      label: 'Persist',
      detail: `${metricsQuery.data?.averageMatchesAdded?.toFixed(1) ?? '0.0'} avg matches added per run`,
      chip: `${runHealth.avgSaved.toFixed(1)} avg saved`,
    },
    {
      label: 'Settle',
      detail: integrity
        ? `${integrity.scoreBackedSettlements} score-backed • ${integrity.officialResultSettlements} official`
        : 'Integrity snapshot pending',
      chip: `${settledCount} settled`,
    },
  ]

  return (
    <Stack spacing={2}>
      <Stack spacing={0.5}>
        <Typography variant="h4">Operations</Typography>
        <Typography color="text.secondary">
          Source health, scraper diagnostics, settlement integrity, and replay-ready observability.
        </Typography>
      </Stack>

      <Alert severity="info">
        Operations cards emphasize the current stack state and recent reliability, while scrape history and
        alias tables are broader maintenance views. Use this page to judge source health and fallback reliance
        before trusting live automation at scale.
      </Alert>

      <Stack direction={{ md: 'row', xs: 'column' }} spacing={1.2}>
        <Button
          onClick={() => runScrapeMutation.mutate()}
          startIcon={<PlayArrowRoundedIcon />}
          variant="contained"
        >
          Trigger Scrape Run
        </Button>
        <Button onClick={() => backfillMutation.mutate()} startIcon={<SaveRoundedIcon />} variant="outlined">
          Backfill Structured Results
        </Button>
      </Stack>

      <Stack direction={{ md: 'row', xs: 'column' }} spacing={1.2}>
        <TextField
          label="From Page"
          onChange={(event) => setRangeFromPage(Number(event.target.value) || 1)}
          size="small"
          type="number"
          value={rangeFromPage}
        />
        <TextField
          label="To Page"
          onChange={(event) => setRangeToPage(Number(event.target.value) || 1)}
          size="small"
          type="number"
          value={rangeToPage}
        />
        <Button onClick={() => runRangeMutation.mutate()} variant="outlined">
          Run Page Range
        </Button>
      </Stack>

      {runScrapeMutation.data ? <Alert severity="success">{runScrapeMutation.data}</Alert> : null}
      {runRangeMutation.data ? <Alert severity="success">{runRangeMutation.data}</Alert> : null}
      {backfillMutation.data ? (
        <Alert severity="success">Updated {backfillMutation.data.updatedMatches} matches.</Alert>
      ) : null}
      {statusQuery.error ? (
        <Alert severity="error">{apiErrorMessage(statusQuery.error, 'Scrape status failed to load.')}</Alert>
      ) : null}
      {metricsQuery.error ? (
        <Alert severity="error">
          {apiErrorMessage(metricsQuery.error, 'Scrape metrics failed to load.')}
        </Alert>
      ) : null}
      {integrityQuery.error ? (
        <Alert severity="error">
          {apiErrorMessage(integrityQuery.error, 'Integrity telemetry failed to load.')}
        </Alert>
      ) : null}

      <Grid container spacing={2}>
        <Grid size={{ md: 3, xs: 12 }}>
          <SourceHealthCard
            description="How fresh and stable the live odds board looks from the operator side."
            detail={session?.lastSyncAt ? `Last sync ${asLocalDate(session.lastSyncAt)}` : 'No live sync yet'}
            label="Live Odds Feed"
            metric={session ? `${session.simulationRowsScanned} rows scanned` : 'No session'}
            signal={liveOddsHealth}
          />
        </Grid>
        <Grid size={{ md: 3, xs: 12 }}>
          <SourceHealthCard
            description="Whether tracked-after-close score continuity is carrying enough of the current session."
            detail={
              integrity
                ? `${integrity.scoreFeedObservations} tracked score observations`
                : 'No integrity snapshot'
            }
            label="Tracked Score Feed"
            metric={integrity ? `${integrity.trackedAfterCloseObservations} after-close obs` : 'Pending'}
            signal={trackedScoreHealth}
          />
        </Grid>
        <Grid size={{ md: 3, xs: 12 }}>
          <SourceHealthCard
            description="How much the system is leaning on official result confirmation rather than fallback settlement."
            detail={
              integrity
                ? `${integrity.officialResultSettlements} official result settlements`
                : 'No official result activity yet'
            }
            label="Official Result Layer"
            metric={
              metricsQuery.data
                ? `Scrape success ${asPct(metricsQuery.data.successRate)}`
                : 'No scrape metrics'
            }
            signal={officialResultHealth}
          />
        </Grid>
        <Grid size={{ md: 3, xs: 12 }}>
          <SourceHealthCard
            description="Lower reliance on DB/heuristic/voided settlement paths means the stack is healthier."
            detail={
              integrity
                ? `${integrity.databaseSettlements} DB • ${integrity.heuristicSettlements} heuristic • ${integrity.voidedSettlements} voided`
                : 'No fallback telemetry'
            }
            label="Fallback Reliance"
            metric={settledCount ? `${settledCount} settled decisions` : 'No settled decisions'}
            signal={fallbackReliance}
          />
        </Grid>
      </Grid>

      <Grid container spacing={2}>
        <Grid size={{ md: 3, xs: 6 }}>
          <MetricCell label="Loaded Runs" value={`${runHealth.total}`} />
        </Grid>
        <Grid size={{ md: 3, xs: 6 }}>
          <MetricCell
            label="Success Rate"
            value={
              metricsQuery.data
                ? asPct(metricsQuery.data.successRate)
                : runHealth.total
                  ? asPct(runHealth.success / runHealth.total)
                  : '0.0%'
            }
          />
        </Grid>
        <Grid size={{ md: 3, xs: 6 }}>
          <MetricCell
            label="Avg Duration"
            value={
              metricsQuery.data
                ? asDurationSeconds(metricsQuery.data.averageDurationSeconds)
                : asDurationSeconds(runHealth.avgDurationSeconds)
            }
          />
        </Grid>
        <Grid size={{ md: 3, xs: 6 }}>
          <MetricCell label="Alias Registry" value={`${aliasesQuery.data?.length ?? 0}`} />
        </Grid>
      </Grid>

      <Grid container spacing={2}>
        <Grid size={{ md: 6, xs: 12 }}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Stack spacing={1}>
                <Typography variant="h6">Pipeline Readout</Typography>
                <Typography color="text.secondary" variant="body2">
                  A quick operator view of where the system is healthy and where we are leaning on fallback
                  behavior.
                </Typography>
                <Divider />
                <List dense disablePadding>
                  {pipelineReadout.map((step) => (
                    <ListItem key={step.label} sx={{ px: 0 }}>
                      <ListItemText primary={step.label} secondary={step.detail} />
                      <Chip label={step.chip} size="small" />
                    </ListItem>
                  ))}
                </List>
              </Stack>
            </CardContent>
          </Card>
        </Grid>
        <Grid size={{ md: 6, xs: 12 }}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Stack spacing={1}>
                <Typography variant="h6">Scrape Run Trend (Last 20)</Typography>
                {!runTrend.length ? (
                  <Alert severity="info">Run trend appears once scrape history is available.</Alert>
                ) : (
                  <ResponsiveContainer height={240} width="100%">
                    <AreaChart data={runTrend}>
                      <defs>
                        <linearGradient id="adminSavedGradient" x1="0" x2="0" y1="0" y2="1">
                          <stop offset="5%" stopColor="#0f7f76" stopOpacity={0.34} />
                          <stop offset="95%" stopColor="#0f7f76" stopOpacity={0.08} />
                        </linearGradient>
                      </defs>
                      <CartesianGrid strokeDasharray="3 3" />
                      <XAxis dataKey="run" interval={0} tick={{ fontSize: 11 }} />
                      <YAxis tickFormatter={(value) => `${Math.round(value)}`} width={54} />
                      <ReTooltip
                        formatter={(value, name) => [
                          name === 'saved'
                            ? `${Number(value ?? 0)} matches`
                            : `${Number(value ?? 0).toFixed(1)} sec`,
                          name === 'saved' ? 'Saved' : 'Duration',
                        ]}
                      />
                      <Area
                        dataKey="saved"
                        fill="url(#adminSavedGradient)"
                        stroke="#0f7f76"
                        strokeWidth={2.2}
                        type="monotone"
                      />
                      <Area
                        dataKey="duration"
                        fill="rgba(217,93,57,0.12)"
                        stroke="#d95d39"
                        strokeWidth={1.8}
                        type="monotone"
                      />
                    </AreaChart>
                  </ResponsiveContainer>
                )}
              </Stack>
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      <Grid container spacing={2}>
        <Grid size={{ md: 5, xs: 12 }}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Stack spacing={1}>
                <Typography variant="h6">Failure Surface</Typography>
                {!errorModeData.length ? (
                  <Typography color="text.secondary" variant="body2">
                    No recent scrape/system errors were found in the current sample window.
                  </Typography>
                ) : (
                  <ResponsiveContainer height={220} width="100%">
                    <BarChart data={errorModeData}>
                      <CartesianGrid strokeDasharray="3 3" />
                      <XAxis dataKey="mode" tick={{ fontSize: 11 }} />
                      <YAxis allowDecimals={false} />
                      <ReTooltip formatter={(value) => [Number(value ?? 0), 'Errors']} />
                      <Bar dataKey="count" fill="#d95d39" radius={[6, 6, 0, 0]} />
                    </BarChart>
                  </ResponsiveContainer>
                )}
              </Stack>
            </CardContent>
          </Card>
        </Grid>
        <Grid size={{ md: 7, xs: 12 }}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Stack spacing={1}>
                <Typography variant="h6">Dry Run / Replay Probe</Typography>
                <Typography color="text.secondary" variant="body2">
                  Use this to confirm selector behavior before a new scrape run or when a list page changes
                  shape.
                </Typography>
                <Stack direction={{ md: 'row', xs: 'column' }} spacing={1.2}>
                  <TextField
                    label="Dry Run Page"
                    onChange={(event) => setDryRunPage(Number(event.target.value) || 1)}
                    size="small"
                    type="number"
                    value={dryRunPage}
                  />
                  <Button onClick={() => dryRunMutation.mutate()} variant="outlined">
                    Dry Run Selectors
                  </Button>
                </Stack>
                {dryRunMutation.data ? (
                  <Stack spacing={1}>
                    <Divider />
                    <Typography variant="body2">URL: {dryRunMutation.data.listUrl}</Typography>
                    <Typography variant="body2">Selector: {dryRunMutation.data.selector}</Typography>
                    <Typography variant="body2">
                      Post links found: {dryRunMutation.data.postLinksFound}
                    </Typography>
                    <List dense disablePadding>
                      {dryRunMutation.data.sampleLinks.map((link) => (
                        <ListItem key={link} sx={{ px: 0 }}>
                          <ListItemText primary={link} />
                        </ListItem>
                      ))}
                    </List>
                  </Stack>
                ) : null}
              </Stack>
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      <Card>
        <CardContent>
          <Stack direction={{ md: 'row', xs: 'column' }} spacing={1.2}>
            <FormControl size="small" sx={{ minWidth: 140 }}>
              <InputLabel id="run-status-label">Status</InputLabel>
              <Select
                label="Status"
                labelId="run-status-label"
                onChange={(event) => setRunStatusFilter(event.target.value)}
                value={runStatusFilter}
              >
                <MenuItem value="">All</MenuItem>
                <MenuItem value="SUCCESS">SUCCESS</MenuItem>
                <MenuItem value="FAILED">FAILED</MenuItem>
                <MenuItem value="RUNNING">RUNNING</MenuItem>
              </Select>
            </FormControl>

            <FormControl size="small" sx={{ minWidth: 180 }}>
              <InputLabel id="run-mode-label">Mode</InputLabel>
              <Select
                label="Mode"
                labelId="run-mode-label"
                onChange={(event) => setRunModeFilter(event.target.value)}
                value={runModeFilter}
              >
                <MenuItem value="">All</MenuItem>
                {modeOptions.map((mode) => (
                  <MenuItem key={mode} value={mode}>
                    {mode}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>
          </Stack>

          <Divider sx={{ my: 1.5 }} />
          <Typography gutterBottom variant="h6">
            Scrape Run History
          </Typography>

          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Run ID</TableCell>
                <TableCell>Status</TableCell>
                <TableCell>Mode</TableCell>
                <TableCell>Started</TableCell>
                <TableCell>Finished</TableCell>
                <TableCell align="right">Saved</TableCell>
                <TableCell>Error</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {(runsQuery.data ?? []).map((run) => (
                <TableRow key={run.runId}>
                  <TableCell>{run.runId}</TableCell>
                  <TableCell>
                    <Chip
                      color={
                        run.status === 'SUCCESS'
                          ? 'success'
                          : run.status === 'FAILED'
                            ? 'error'
                            : run.status === 'RUNNING'
                              ? 'warning'
                              : 'default'
                      }
                      label={run.status}
                      size="small"
                    />
                  </TableCell>
                  <TableCell>{run.mode}</TableCell>
                  <TableCell>{asLocalDate(run.startedAt)}</TableCell>
                  <TableCell>{asLocalDate(run.finishedAt)}</TableCell>
                  <TableCell align="right">{run.savedMatches}</TableCell>
                  <TableCell>{run.error ?? '—'}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <Card>
        <CardContent>
          <Typography gutterBottom variant="h6">
            Scrape Error Log
          </Typography>
          <List dense>
            {(errorsQuery.data ?? []).map((err, index) => (
              <ListItem key={`${err.runId}-${err.occurredAt}-${index}`} sx={{ px: 0 }}>
                <ListItemText
                  primary={`[${err.mode}] ${err.message}`}
                  secondary={`Run ${err.runId} • ${asLocalDate(err.occurredAt)}${err.url ? ` • ${err.url}` : ''}`}
                />
              </ListItem>
            ))}
          </List>
        </CardContent>
      </Card>

      <Card>
        <CardContent>
          <Typography gutterBottom variant="h6">
            Player Alias Registry
          </Typography>
          <Typography color="text.secondary" variant="body2">
            Canonical identity mappings the scraper and live engine lean on for clean player resolution.
          </Typography>
          <Divider sx={{ my: 1 }} />
          <List dense>
            {(aliasesQuery.data ?? []).slice(0, 20).map((alias) => (
              <ListItem key={alias.id} sx={{ px: 0 }}>
                <ListItemText
                  primary={`${alias.aliasName} -> ${alias.playerName}`}
                  secondary={`Created ${asLocalDate(alias.createdAt)}`}
                />
              </ListItem>
            ))}
          </List>
        </CardContent>
      </Card>
    </Stack>
  )
}

interface MetricCellProps {
  label: string
  value: string
}

function MetricCell({ label, value }: MetricCellProps) {
  return (
    <Card sx={{ height: '100%' }}>
      <CardContent>
        <Typography color="text.secondary" variant="caption">
          {label}
        </Typography>
        <Typography sx={{ fontWeight: 700 }} variant="h6">
          {value}
        </Typography>
      </CardContent>
    </Card>
  )
}

interface SourceHealthCardProps {
  label: string
  description: string
  metric: string
  detail: string
  signal: HealthSignal
}

function SourceHealthCard({ label, description, metric, detail, signal }: SourceHealthCardProps) {
  return (
    <Card sx={{ height: '100%' }}>
      <CardContent>
        <Stack spacing={1}>
          <Stack alignItems="center" direction="row" justifyContent="space-between">
            <Typography variant="h6">{label}</Typography>
            <Chip color={signal.color} label={signal.label} size="small" />
          </Stack>
          <Typography color="text.secondary" variant="body2">
            {description}
          </Typography>
          <Divider />
          <Typography variant="body2">{metric}</Typography>
          <Typography color="text.secondary" variant="caption">
            {detail}
          </Typography>
        </Stack>
      </CardContent>
    </Card>
  )
}

interface HealthSignal {
  label: string
  color: 'success' | 'warning' | 'error' | 'default'
}

function buildHealthSignal(score: number): HealthSignal {
  if (score >= 0.72) return { label: 'Healthy', color: 'success' }
  if (score >= 0.42) return { label: 'Watching', color: 'warning' }
  return { label: 'Weak', color: 'error' }
}

function ratio(numerator: number, denominator: number) {
  if (!Number.isFinite(numerator) || !Number.isFinite(denominator) || denominator <= 0) return 0
  return numerator / denominator
}

function clamp01(value: number) {
  if (!Number.isFinite(value)) return 0
  if (value < 0) return 0
  if (value > 1) return 1
  return value
}
