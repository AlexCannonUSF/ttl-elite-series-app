import RefreshRoundedIcon from '@mui/icons-material/RefreshRounded'
import ScienceRoundedIcon from '@mui/icons-material/ScienceRounded'
import {
  Alert,
  Box,
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
  Tooltip,
  Typography,
} from '@mui/material'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
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
import { asLocalDate, asPct, asSigned } from '../lib/format'

function asMoney(value: number) {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    maximumFractionDigits: 2,
  }).format(value)
}

export function AnalyticsPage() {
  const [registryFamily, setRegistryFamily] = useState<string>('ALL')
  const [oddsStrategy, setOddsStrategy] = useState<'CONSERVATIVE' | 'AGGRESSIVE'>('CONSERVATIVE')
  const [oddsModel, setOddsModel] = useState<string>('ENSEMBLE')

  const benchmarkQuery = useQuery({
    queryKey: ['stats-benchmark'],
    queryFn: () => apiClient.benchmarkStats(25),
  })

  const modelRegistryQuery = useQuery({
    queryKey: ['model-registry', registryFamily],
    queryFn: () => apiClient.getModelRegistry(registryFamily === 'ALL' ? undefined : registryFamily, 20),
  })

  const modelTrainingQuery = useQuery({
    queryKey: ['last-model-training-report'],
    queryFn: apiClient.getLastModelTrainingReport,
  })

  const adaptiveRegimeQuery = useQuery({
    queryKey: ['adaptive-regime-profiles'],
    queryFn: apiClient.getAdaptiveRegimeProfiles,
  })

  const liveSessionQuery = useQuery({
    queryKey: ['analytics-live-session'],
    queryFn: () => apiClient.getLiveStudioSession(),
  })

  const liveIntegrityQuery = useQuery({
    queryKey: ['analytics-live-integrity'],
    queryFn: () => apiClient.getLiveStudioIntegrity(),
  })

  const valueOpportunitiesQuery = useQuery({
    queryKey: ['analytics-value-opportunities', oddsStrategy],
    queryFn: () => apiClient.getValueOpportunities(oddsStrategy, 18),
  })

  const rerunMutation = useMutation({
    mutationFn: () => apiClient.benchmarkStats(25),
    onSuccess: () => {
      benchmarkQuery.refetch()
    },
  })

  const trainModelsMutation = useMutation({
    mutationFn: () => apiClient.trainPredictionModels(),
    onSuccess: () => {
      modelRegistryQuery.refetch()
      modelTrainingQuery.refetch()
    },
  })

  const refreshOddsMutation = useMutation({
    mutationFn: () => apiClient.refreshOddsValueEngine(oddsStrategy, oddsModel),
    onSuccess: () => {
      valueOpportunitiesQuery.refetch()
      liveSessionQuery.refetch()
      liveIntegrityQuery.refetch()
    },
  })

  const champion =
    modelTrainingQuery.data?.candidates.find((candidate) => candidate.active) ??
    modelTrainingQuery.data?.candidates[0]
  const activeRegistryEntries = useMemo(
    () => (modelRegistryQuery.data ?? []).filter((entry) => entry.active),
    [modelRegistryQuery.data]
  )

  const calibrationData = useMemo(
    () =>
      (modelTrainingQuery.data?.calibrationCurve ?? []).map((bin) => ({
        bucket: `${Math.round(bin.lowerBound * 100)}-${Math.round(bin.upperBound * 100)}%`,
        predicted: Number((bin.meanPredicted * 100).toFixed(2)),
        observed: Number((bin.observedRate * 100).toFixed(2)),
        samples: bin.count,
      })),
    [modelTrainingQuery.data?.calibrationCurve]
  )

  const validationRegimeData = useMemo(
    () =>
      (modelTrainingQuery.data?.validationRegimes ?? []).map((regime) => ({
        label: abbreviateTrigger(regime.label),
        predictedPct: Number((regime.meanPredicted * 100).toFixed(2)),
        observedPct: Number((regime.observedRate * 100).toFixed(2)),
        accuracyPct: Number((regime.accuracy * 100).toFixed(2)),
        brierScore: Number(regime.brierScore.toFixed(4)),
        count: regime.count,
      })),
    [modelTrainingQuery.data?.validationRegimes]
  )

  const operationalRegimeData = useMemo(
    () =>
      (modelTrainingQuery.data?.operationalRegimes ?? []).map((regime) => ({
        label: abbreviateTrigger(regime.label),
        predictedPct: Number((regime.meanPredicted * 100).toFixed(2)),
        observedPct: Number((regime.observedRate * 100).toFixed(2)),
        accuracyPct: Number((regime.accuracy * 100).toFixed(2)),
        brierScore: Number(regime.brierScore.toFixed(4)),
        roiPct: Number((regime.roiPct ?? 0).toFixed(2)),
        count: regime.count,
      })),
    [modelTrainingQuery.data?.operationalRegimes]
  )

  const session = liveSessionQuery.data
  const integrity = liveIntegrityQuery.data
  const adaptiveRegimes = adaptiveRegimeQuery.data ?? []
  const opportunities = useMemo(() => valueOpportunitiesQuery.data ?? [], [valueOpportunitiesQuery.data])
  const settledCount = session ? session.wins + session.losses + session.pushes + session.voidedBets : 0
  const scoreBackedRate = integrity && settledCount > 0 ? integrity.scoreBackedSettlements / settledCount : 0
  const targetedCompletionRate =
    integrity && settledCount > 0 ? integrity.targetedCompletionSettlements / settledCount : 0
  const calibrationDrift = session?.adaptiveMetrics?.calibrationErrorPct ?? null

  const equityData = useMemo(
    () =>
      (session?.equityCurve ?? []).slice(-40).map((point, index) => ({
        label: index + 1,
        bankroll: Number(point.bankroll.toFixed(2)),
        pnl: Number(point.cumulativePnl.toFixed(2)),
      })),
    [session?.equityCurve]
  )

  const triggerData = useMemo(
    () =>
      [...(session?.topTriggers ?? [])]
        .sort((left, right) => right.count - left.count || Math.abs(right.pnl) - Math.abs(left.pnl))
        .slice(0, 6)
        .map((trigger) => ({
          trigger: abbreviateTrigger(trigger.trigger),
          roiPct: Number(trigger.roiPct.toFixed(2)),
          winRatePct: Number((trigger.winRate * 100).toFixed(1)),
          avgEdgePct: Number(trigger.avgEdgePct.toFixed(2)),
          count: trigger.count,
        })),
    [session?.topTriggers]
  )

  const settlementMixData = useMemo(
    () => [
      { label: 'Targeted', value: integrity?.targetedCompletionSettlements ?? 0 },
      { label: 'Official', value: integrity?.officialResultSettlements ?? 0 },
      { label: 'Database', value: integrity?.databaseSettlements ?? 0 },
      { label: 'Heuristic', value: integrity?.heuristicSettlements ?? 0 },
      { label: 'Voided', value: integrity?.voidedSettlements ?? 0 },
    ],
    [integrity]
  )

  const registryHealthRows = useMemo(
    () =>
      (modelRegistryQuery.data ?? []).slice(0, 8).map((entry) => ({
        id: entry.id,
        label: `${entry.modelFamily} • ${entry.modelVersion}`,
        secondary: `Brier ${entry.brierScore?.toFixed(4) ?? 'N/A'} • Accuracy ${
          entry.accuracy != null ? asPct(entry.accuracy) : 'N/A'
        } • ${entry.active ? 'ACTIVE' : 'INACTIVE'}`,
      })),
    [modelRegistryQuery.data]
  )

  const valueWatchlist = useMemo(
    () =>
      [...opportunities]
        .sort((left, right) => right.edge - left.edge || right.modelProbability - left.modelProbability)
        .slice(0, 5),
    [opportunities]
  )

  return (
    <Stack spacing={2}>
      <Stack alignItems="center" direction="row" justifyContent="space-between">
        <Stack spacing={0.5}>
          <Typography variant="h4">Analytics Lab</Typography>
          <Typography color="text.secondary">
            Training validation, live operating results, trigger quality, and adaptive tuning in one place.
          </Typography>
        </Stack>

        <Stack direction={{ md: 'row', xs: 'column' }} spacing={1}>
          <Tooltip title="Run the statistics benchmark again so query-speed diagnostics stay current.">
            <Button
              onClick={() => rerunMutation.mutate()}
              startIcon={<RefreshRoundedIcon />}
              variant="contained"
            >
              Rerun Benchmark
            </Button>
          </Tooltip>
          <Tooltip title="Retrain all model families and refresh the active champion and calibration diagnostics.">
            <Button
              onClick={() => trainModelsMutation.mutate()}
              startIcon={<ScienceRoundedIcon />}
              variant="outlined"
            >
              Train Models
            </Button>
          </Tooltip>
          <Tooltip title="Refresh the odds value engine using the selected strategy and model version.">
            <Button onClick={() => refreshOddsMutation.mutate()} variant="outlined">
              Refresh Odds Value Engine
            </Button>
          </Tooltip>
        </Stack>
      </Stack>

      <Alert severity="info">
        Analytics Lab intentionally mixes three windows: <strong>training holdout</strong> for model
        validation, <strong>current session</strong> for live operating results, and{' '}
        <strong>adaptive regime tuning</strong> for bounded learning from recent settled picks. Read each card
        in that context.
      </Alert>

      <Stack direction={{ md: 'row', xs: 'column' }} spacing={1}>
        <FormControl size="small" sx={{ minWidth: 180 }}>
          <InputLabel id="odds-strategy-label">Value Strategy</InputLabel>
          <Select
            label="Value Strategy"
            labelId="odds-strategy-label"
            onChange={(event) => setOddsStrategy(event.target.value as 'CONSERVATIVE' | 'AGGRESSIVE')}
            value={oddsStrategy}
          >
            <MenuItem value="CONSERVATIVE">Conservative</MenuItem>
            <MenuItem value="AGGRESSIVE">Aggressive</MenuItem>
          </Select>
        </FormControl>
        <FormControl size="small" sx={{ minWidth: 180 }}>
          <InputLabel id="odds-model-label">Odds Model</InputLabel>
          <Select
            label="Odds Model"
            labelId="odds-model-label"
            onChange={(event) => setOddsModel(String(event.target.value))}
            value={oddsModel}
          >
            <MenuItem value="ENSEMBLE">Ensemble</MenuItem>
            <MenuItem value="LOGISTIC">Logistic</MenuItem>
            <MenuItem value="RF_LIKE">RF-Like</MenuItem>
            <MenuItem value="GBT_LIKE">GBT-Like</MenuItem>
          </Select>
        </FormControl>
        <FormControl size="small" sx={{ minWidth: 200 }}>
          <InputLabel id="registry-family-label">Registry Family</InputLabel>
          <Select
            label="Registry Family"
            labelId="registry-family-label"
            onChange={(event) => setRegistryFamily(String(event.target.value))}
            value={registryFamily}
          >
            <MenuItem value="ALL">All Families</MenuItem>
            <MenuItem value="LOGISTIC">Logistic</MenuItem>
            <MenuItem value="GBT_LIKE">GBT-Like</MenuItem>
            <MenuItem value="RF_LIKE">RF-Like</MenuItem>
            <MenuItem value="ENSEMBLE">Ensemble</MenuItem>
          </Select>
        </FormControl>
      </Stack>

      {benchmarkQuery.error ? (
        <Alert severity="error">
          {apiErrorMessage(benchmarkQuery.error, 'Unable to fetch benchmark data.')}
        </Alert>
      ) : null}
      {modelTrainingQuery.error ? (
        <Alert severity="error">
          {apiErrorMessage(modelTrainingQuery.error, 'Unable to fetch training report.')}
        </Alert>
      ) : null}
      {liveSessionQuery.error ? (
        <Alert severity="error">
          {apiErrorMessage(liveSessionQuery.error, 'Unable to load current session analytics.')}
        </Alert>
      ) : null}
      {liveIntegrityQuery.error ? (
        <Alert severity="error">
          {apiErrorMessage(liveIntegrityQuery.error, 'Unable to load settlement integrity.')}
        </Alert>
      ) : null}
      {adaptiveRegimeQuery.error ? (
        <Alert severity="error">
          {apiErrorMessage(adaptiveRegimeQuery.error, 'Unable to load adaptive regime profiles.')}
        </Alert>
      ) : null}
      {valueOpportunitiesQuery.error ? (
        <Alert severity="error">
          {apiErrorMessage(valueOpportunitiesQuery.error, 'Unable to load value opportunities.')}
        </Alert>
      ) : null}
      {trainModelsMutation.error ? (
        <Alert severity="error">{apiErrorMessage(trainModelsMutation.error, 'Model training failed.')}</Alert>
      ) : null}
      {refreshOddsMutation.error ? (
        <Alert severity="error">{apiErrorMessage(refreshOddsMutation.error, 'Odds refresh failed.')}</Alert>
      ) : null}
      {trainModelsMutation.data ? (
        <Alert severity="success">
          Trained {trainModelsMutation.data.candidates.length} model variants. Champion:{' '}
          {trainModelsMutation.data.championFamily}
        </Alert>
      ) : null}
      {refreshOddsMutation.data ? (
        <Alert severity="success">
          Odds refreshed ({refreshOddsMutation.data.strategy}, {refreshOddsMutation.data.modelVersion}):{' '}
          {refreshOddsMutation.data.opportunitiesCreated} value opportunities created.
        </Alert>
      ) : null}

      <Grid container spacing={2}>
        <Grid size={{ lg: 2, md: 4, xs: 6 }}>
          <MetricCard
            label="Active Champion"
            value={champion ? champion.family : 'N/A'}
            secondary={champion ? champion.version : 'No training report'}
          />
        </Grid>
        <Grid size={{ lg: 2, md: 4, xs: 6 }}>
          <MetricCard
            label="Champion Brier"
            value={champion ? champion.brierScore.toFixed(4) : 'N/A'}
            secondary={
              champion ? `LogLoss ${champion.logLoss.toFixed(4)}` : 'Train models to compare candidates'
            }
          />
        </Grid>
        <Grid size={{ lg: 2, md: 4, xs: 6 }}>
          <MetricCard
            label="Session ROI"
            value={session ? `${asSigned(session.roiPct, 2)}%` : 'N/A'}
            secondary={session ? `Win rate ${asPct(session.settledWinRate)}` : 'No live session snapshot'}
          />
        </Grid>
        <Grid size={{ lg: 2, md: 4, xs: 6 }}>
          <MetricCard
            label="Score-backed Settlements"
            value={integrity && settledCount > 0 ? asPct(scoreBackedRate) : 'N/A'}
            secondary={
              integrity
                ? `${integrity.scoreBackedSettlements} of ${settledCount || 0} settled decisions`
                : 'Waiting for integrity data'
            }
          />
        </Grid>
        <Grid size={{ lg: 2, md: 4, xs: 6 }}>
          <MetricCard
            label="Targeted Completion"
            value={integrity && settledCount > 0 ? asPct(targetedCompletionRate) : 'N/A'}
            secondary={
              integrity
                ? `${integrity.targetedCompletionSettlements} tracked completions`
                : 'No targeted completions yet'
            }
          />
        </Grid>
        <Grid size={{ lg: 2, md: 4, xs: 6 }}>
          <MetricCard
            label="Value Watchlist"
            value={`${opportunities.length}`}
            secondary={
              opportunities.length
                ? `${oddsStrategy.toLowerCase()} strategy opportunities`
                : 'No active opportunities'
            }
          />
        </Grid>
      </Grid>

      <Grid container spacing={2}>
        <Grid size={{ md: 6, xs: 12 }}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Typography gutterBottom variant="h6">
                Calibration Curve (Predicted vs Actual)
              </Typography>
              {!calibrationData.length ? (
                <Typography color="text.secondary" variant="body2">
                  Train models to populate calibration bins and confidence diagnostics.
                </Typography>
              ) : (
                <Box sx={{ height: 280 }}>
                  <ResponsiveContainer height="100%" width="100%">
                    <AreaChart data={calibrationData}>
                      <CartesianGrid strokeDasharray="3 3" />
                      <XAxis dataKey="bucket" tick={{ fontSize: 12 }} />
                      <YAxis tickFormatter={(value) => `${value}%`} />
                      <ReTooltip
                        formatter={(value, name) => [
                          `${Number(value ?? 0).toFixed(2)}%`,
                          name === 'predicted' ? 'Predicted' : 'Observed',
                        ]}
                        labelFormatter={(label) => `Bin ${label}`}
                      />
                      <Area
                        dataKey="predicted"
                        fill="#0f7f7622"
                        name="predicted"
                        stroke="#0f7f76"
                        strokeWidth={2.2}
                        type="monotone"
                      />
                      <Area
                        dataKey="observed"
                        fill="#d95d3915"
                        name="observed"
                        stroke="#d95d39"
                        strokeWidth={2.2}
                        type="monotone"
                      />
                    </AreaChart>
                  </ResponsiveContainer>
                </Box>
              )}
            </CardContent>
          </Card>
        </Grid>

        <Grid size={{ md: 6, xs: 12 }}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Typography gutterBottom variant="h6">
                Session Bankroll Curve
              </Typography>
              {!equityData.length ? (
                <Typography color="text.secondary" variant="body2">
                  Sync a live session to populate bankroll and cumulative P&amp;L telemetry.
                </Typography>
              ) : (
                <Box sx={{ height: 280 }}>
                  <ResponsiveContainer height="100%" width="100%">
                    <AreaChart data={equityData}>
                      <CartesianGrid strokeDasharray="3 3" />
                      <XAxis dataKey="label" tick={{ fontSize: 12 }} />
                      <YAxis tickFormatter={(value) => asMoney(Number(value ?? 0))} />
                      <ReTooltip
                        formatter={(value, name) => [
                          asMoney(Number(value ?? 0)),
                          name === 'bankroll' ? 'Bankroll' : 'Cumulative P&L',
                        ]}
                        labelFormatter={(label) => `Settlement ${label}`}
                      />
                      <Area
                        dataKey="bankroll"
                        fill="#0f7f7620"
                        name="bankroll"
                        stroke="#0f7f76"
                        strokeWidth={2.2}
                        type="monotone"
                      />
                      <Area
                        dataKey="pnl"
                        fill="#d95d3915"
                        name="pnl"
                        stroke="#d95d39"
                        strokeWidth={2.2}
                        type="monotone"
                      />
                    </AreaChart>
                  </ResponsiveContainer>
                </Box>
              )}
            </CardContent>
          </Card>
        </Grid>

        <Grid size={{ md: 6, xs: 12 }}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Typography gutterBottom variant="h6">
                Trigger ROI Snapshot
              </Typography>
              {!triggerData.length ? (
                <Typography color="text.secondary" variant="body2">
                  Settle picks in the current session to get trigger-level ROI and win-rate diagnostics.
                </Typography>
              ) : (
                <Box sx={{ height: 280 }}>
                  <ResponsiveContainer height="100%" width="100%">
                    <BarChart data={triggerData}>
                      <CartesianGrid strokeDasharray="3 3" />
                      <XAxis dataKey="trigger" tick={{ fontSize: 12 }} />
                      <YAxis tickFormatter={(value) => `${value}%`} />
                      <ReTooltip
                        formatter={(value, name) => [
                          `${Number(value ?? 0).toFixed(2)}%`,
                          name === 'roiPct' ? 'ROI' : name === 'winRatePct' ? 'Win rate' : 'Avg edge',
                        ]}
                      />
                      <Bar dataKey="roiPct" fill="#0f7f76" radius={[6, 6, 0, 0]} />
                    </BarChart>
                  </ResponsiveContainer>
                </Box>
              )}
            </CardContent>
          </Card>
        </Grid>

        <Grid size={{ md: 6, xs: 12 }}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Typography gutterBottom variant="h6">
                Settlement Source Mix
              </Typography>
              {!integrity ? (
                <Typography color="text.secondary" variant="body2">
                  Integrity telemetry will appear here once current-session settlement data is available.
                </Typography>
              ) : (
                <Box sx={{ height: 280 }}>
                  <ResponsiveContainer height="100%" width="100%">
                    <BarChart data={settlementMixData}>
                      <CartesianGrid strokeDasharray="3 3" />
                      <XAxis dataKey="label" tick={{ fontSize: 12 }} />
                      <YAxis allowDecimals={false} />
                      <ReTooltip formatter={(value) => [Number(value ?? 0), 'Settlements']} />
                      <Bar dataKey="value" fill="#d95d39" radius={[6, 6, 0, 0]} />
                    </BarChart>
                  </ResponsiveContainer>
                </Box>
              )}
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      <Grid container spacing={2}>
        <Grid size={{ md: 6, xs: 12 }}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Typography gutterBottom variant="h6">
                Validation Regimes (Training Holdout)
              </Typography>
              {!validationRegimeData.length ? (
                <Typography color="text.secondary" variant="body2">
                  Train models to inspect favorite, underdog, and confidence-bucket calibration.
                </Typography>
              ) : (
                <Stack spacing={1.5}>
                  <Box sx={{ height: 260 }}>
                    <ResponsiveContainer height="100%" width="100%">
                      <BarChart data={validationRegimeData}>
                        <CartesianGrid strokeDasharray="3 3" />
                        <XAxis dataKey="label" tick={{ fontSize: 12 }} />
                        <YAxis tickFormatter={(value) => `${value}%`} />
                        <ReTooltip
                          formatter={(value, name) => [
                            `${Number(value ?? 0).toFixed(2)}%`,
                            name === 'predictedPct'
                              ? 'Predicted'
                              : name === 'observedPct'
                                ? 'Observed'
                                : 'Accuracy',
                          ]}
                        />
                        <Bar dataKey="predictedPct" fill="#0f7f76" radius={[6, 6, 0, 0]} />
                        <Bar dataKey="observedPct" fill="#d95d39" radius={[6, 6, 0, 0]} />
                      </BarChart>
                    </ResponsiveContainer>
                  </Box>
                  <List dense disablePadding>
                    {validationRegimeData.slice(0, 5).map((regime) => (
                      <ListItem key={regime.label} sx={{ px: 0 }}>
                        <ListItemText
                          primary={`${regime.label} • ${regime.count} samples`}
                          secondary={`Pred ${regime.predictedPct.toFixed(1)}% • Obs ${regime.observedPct.toFixed(1)}% • Acc ${regime.accuracyPct.toFixed(1)}% • Brier ${regime.brierScore.toFixed(4)}`}
                        />
                      </ListItem>
                    ))}
                  </List>
                </Stack>
              )}
            </CardContent>
          </Card>
        </Grid>

        <Grid size={{ md: 6, xs: 12 }}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Typography gutterBottom variant="h6">
                Operational Regimes (Settled Paper Trades)
              </Typography>
              {!operationalRegimeData.length ? (
                <Typography color="text.secondary" variant="body2">
                  Settle more paper trades to compare prematch, live, and side-type performance.
                </Typography>
              ) : (
                <Stack spacing={1.5}>
                  <Box sx={{ height: 260 }}>
                    <ResponsiveContainer height="100%" width="100%">
                      <BarChart data={operationalRegimeData}>
                        <CartesianGrid strokeDasharray="3 3" />
                        <XAxis dataKey="label" tick={{ fontSize: 12 }} />
                        <YAxis tickFormatter={(value) => `${value}%`} />
                        <ReTooltip
                          formatter={(value, name) => [
                            `${Number(value ?? 0).toFixed(2)}%`,
                            name === 'predictedPct'
                              ? 'Predicted'
                              : name === 'observedPct'
                                ? 'Observed'
                                : 'ROI',
                          ]}
                        />
                        <Bar dataKey="observedPct" fill="#0f7f76" radius={[6, 6, 0, 0]} />
                        <Bar dataKey="roiPct" fill="#d95d39" radius={[6, 6, 0, 0]} />
                      </BarChart>
                    </ResponsiveContainer>
                  </Box>
                  <List dense disablePadding>
                    {operationalRegimeData.slice(0, 6).map((regime) => (
                      <ListItem key={regime.label} sx={{ px: 0 }}>
                        <ListItemText
                          primary={`${regime.label} • ${regime.count} settled`}
                          secondary={`Pred ${regime.predictedPct.toFixed(1)}% • Obs ${regime.observedPct.toFixed(1)}% • ROI ${asSigned(regime.roiPct, 2)}% • Brier ${regime.brierScore.toFixed(4)}`}
                        />
                      </ListItem>
                    ))}
                  </List>
                </Stack>
              )}
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      <Grid container spacing={2}>
        <Grid size={{ md: 12, xs: 12 }}>
          <Card>
            <CardContent>
              <Stack spacing={1.2}>
                <Typography variant="h6">Adaptive Regime Tuning</Typography>
                <Divider />
                {!adaptiveRegimes.length ? (
                  <Typography color="text.secondary" variant="body2">
                    Adaptive regime profiles will appear once we have enough settled paper-trade history in
                    each bucket.
                  </Typography>
                ) : (
                  <>
                    <Typography variant="body2">
                      These are the live learning profiles currently nudging confidence scale and CI width in
                      the value engine.
                    </Typography>
                    <Stack direction="row" flexWrap="wrap" gap={1}>
                      {adaptiveRegimes.slice(0, 6).map((profile) => (
                        <Chip
                          key={profile.label}
                          color={
                            Math.abs(profile.calibrationErrorPct) > 6 || profile.roiPct < -5
                              ? 'warning'
                              : 'default'
                          }
                          label={`${profile.label} • ${Math.round(profile.reliability * 100)}% rel • scale ${profile.confidenceScale.toFixed(2)}`}
                          size="small"
                        />
                      ))}
                    </Stack>
                    <List dense disablePadding>
                      {adaptiveRegimes.slice(0, 8).map((profile) => (
                        <ListItem key={profile.label} sx={{ px: 0 }}>
                          <ListItemText
                            primary={`${profile.label} • ${profile.sampleSize} samples • ${Math.round(profile.reliability * 100)}% reliability`}
                            secondary={`Calib ${asSigned(profile.calibrationErrorPct, 2)}% • ROI ${asSigned(profile.roiPct, 2)}% • scale ${profile.confidenceScale.toFixed(2)} • CI boost ${asSigned(profile.ciBoost * 100, 2)}%`}
                          />
                        </ListItem>
                      ))}
                    </List>
                  </>
                )}
              </Stack>
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      <Grid container spacing={2}>
        <Grid size={{ md: 4, xs: 12 }}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Stack spacing={1.2}>
                <Typography variant="h6">Current Session Intelligence</Typography>
                <Divider />
                {session ? (
                  <>
                    <Typography variant="body2">
                      Session bankroll sits at <strong>{asMoney(session.currentBankroll)}</strong> with
                      realized P&amp;L of <strong>{asSigned(session.realizedPnl, 2)}</strong>.
                    </Typography>
                    <Typography variant="body2">
                      Open exposure is <strong>{asMoney(session.exposureMetrics.openExposure)}</strong>{' '}
                      against a cap of <strong>{asMoney(session.exposureMetrics.openExposureCap)}</strong>.
                    </Typography>
                    <Typography variant="body2">
                      Most concentrated player:{' '}
                      <strong>{session.exposureMetrics.mostExposedPlayerName ?? 'N/A'}</strong> at{' '}
                      {asPct(session.exposureMetrics.mostExposedPlayerCapUsagePct)} of player cap.
                    </Typography>
                    <Typography variant="body2">
                      Most concentrated trigger:{' '}
                      <strong>{session.exposureMetrics.mostExposedTrigger ?? 'N/A'}</strong> at{' '}
                      {asPct(session.exposureMetrics.mostExposedTriggerCapUsagePct)} of trigger cap.
                    </Typography>
                    <Typography variant="body2">
                      Decision flow: <strong>{session.decisionTelemetry.placedCount}</strong> placed out of{' '}
                      <strong>{session.decisionTelemetry.consideredCount}</strong> considered (
                      {session.decisionTelemetry.placementRatePct.toFixed(1)}%).
                    </Typography>
                    <Typography variant="body2">
                      Avg signal quality{' '}
                      <strong>{session.decisionTelemetry.avgSignalQualityPct.toFixed(1)}%</strong>; top skip
                      reason <strong>{session.decisionTelemetry.topSkipReasons[0]?.reason ?? 'N/A'}</strong>.
                    </Typography>
                    <Stack direction="row" flexWrap="wrap" gap={1}>
                      <Chip label={`Open bets ${session.openBets}`} size="small" />
                      <Chip
                        label={`Usage ${asPct(session.exposureMetrics.openExposureUsagePct)}`}
                        size="small"
                      />
                      <Chip
                        label={`Placement ${session.decisionTelemetry.placementRatePct.toFixed(1)}%`}
                        size="small"
                      />
                      <Chip
                        label={`Avg signal ${session.decisionTelemetry.avgSignalQualityPct.toFixed(1)}%`}
                        size="small"
                      />
                      <Chip
                        color={session.exposureMetrics.playerNearCapCount > 0 ? 'warning' : 'default'}
                        label={`Player cap alerts ${session.exposureMetrics.playerNearCapCount}`}
                        size="small"
                      />
                      <Chip
                        color={session.exposureMetrics.triggerNearCapCount > 0 ? 'warning' : 'default'}
                        label={`Trigger cap alerts ${session.exposureMetrics.triggerNearCapCount}`}
                        size="small"
                      />
                      {calibrationDrift != null ? (
                        <Chip
                          color={Math.abs(calibrationDrift) > 8 ? 'warning' : 'default'}
                          label={`Calibration drift ${asSigned(calibrationDrift, 2)}%`}
                          size="small"
                        />
                      ) : null}
                    </Stack>
                  </>
                ) : (
                  <Typography color="text.secondary" variant="body2">
                    No active live session snapshot is available yet.
                  </Typography>
                )}
              </Stack>
            </CardContent>
          </Card>
        </Grid>

        <Grid size={{ md: 4, xs: 12 }}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Stack spacing={1.2}>
                <Typography variant="h6">Value Engine Watchlist</Typography>
                <Divider />
                {!valueWatchlist.length ? (
                  <Typography color="text.secondary" variant="body2">
                    Refresh the value engine to populate the current watchlist.
                  </Typography>
                ) : (
                  <List dense disablePadding>
                    {valueWatchlist.map((opportunity) => (
                      <ListItem key={opportunity.id} sx={{ px: 0 }}>
                        <ListItemText
                          primary={`${opportunity.playerSideName} • edge ${asSigned(opportunity.edge * 100, 2)}%`}
                          secondary={`${opportunity.source} • ${opportunity.modelVersion} • model ${asPct(
                            opportunity.modelProbability
                          )} vs implied ${asPct(opportunity.impliedProbability)} • fair ${
                            opportunity.americanOdds > 0
                              ? `+${opportunity.americanOdds}`
                              : opportunity.americanOdds
                          }`}
                        />
                      </ListItem>
                    ))}
                  </List>
                )}
              </Stack>
            </CardContent>
          </Card>
        </Grid>

        <Grid size={{ md: 4, xs: 12 }}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Stack spacing={1.2}>
                <Typography variant="h6">Latest Training Report</Typography>
                <Divider />
                {modelTrainingQuery.data ? (
                  <>
                    <Typography variant="body2">
                      Champion <strong>{modelTrainingQuery.data.championFamily}</strong> trained on{' '}
                      <strong>{modelTrainingQuery.data.samples}</strong> samples with{' '}
                      <strong>{modelTrainingQuery.data.features}</strong> engineered features.
                    </Typography>
                    <Typography color="text.secondary" variant="body2">
                      Window: {modelTrainingQuery.data.trainingFrom} to {modelTrainingQuery.data.trainingTo}
                    </Typography>
                    <Typography color="text.secondary" variant="body2">
                      Trained at {asLocalDate(modelTrainingQuery.data.trainedAt)}
                    </Typography>
                    <Stack direction="row" flexWrap="wrap" gap={1}>
                      <Chip label={`Champion ${modelTrainingQuery.data.championFamily}`} size="small" />
                      <Chip label={`Candidates ${modelTrainingQuery.data.candidates.length}`} size="small" />
                      <Chip
                        label={`Validation regimes ${modelTrainingQuery.data.validationRegimes.length}`}
                        size="small"
                      />
                      <Chip
                        label={`Operational regimes ${modelTrainingQuery.data.operationalRegimes.length}`}
                        size="small"
                      />
                      {champion ? <Chip label={`Accuracy ${asPct(champion.accuracy)}`} size="small" /> : null}
                    </Stack>
                  </>
                ) : (
                  <Typography color="text.secondary" variant="body2">
                    Train models to populate the current training readout.
                  </Typography>
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
              <Stack spacing={1.2}>
                <Typography variant="h6">Registry Health</Typography>
                <Divider />
                <Stack direction="row" flexWrap="wrap" gap={1}>
                  <Chip label={`Active ${activeRegistryEntries.length}`} size="small" />
                  <Chip label={`Visible ${modelRegistryQuery.data?.length ?? 0}`} size="small" />
                  {champion ? <Chip label={`Champion ${champion.family}`} size="small" /> : null}
                </Stack>
                {!registryHealthRows.length ? (
                  <Typography color="text.secondary" variant="body2">
                    No registry rows available for the current family filter.
                  </Typography>
                ) : (
                  <List dense disablePadding>
                    {registryHealthRows.map((entry) => (
                      <ListItem key={entry.id} sx={{ px: 0 }}>
                        <ListItemText primary={entry.label} secondary={entry.secondary} />
                      </ListItem>
                    ))}
                  </List>
                )}
              </Stack>
            </CardContent>
          </Card>
        </Grid>

        <Grid size={{ md: 3.5, xs: 12 }}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Stack spacing={1.2}>
                <Typography variant="h6">Settlement Integrity</Typography>
                <Divider />
                {integrity ? (
                  <>
                    <Typography variant="body2">
                      Score-backed settlements: <strong>{integrity.scoreBackedSettlements}</strong>
                    </Typography>
                    <Typography variant="body2">
                      Targeted completions: <strong>{integrity.targetedCompletionSettlements}</strong>
                    </Typography>
                    <Typography variant="body2">
                      Official result confirmations: <strong>{integrity.officialResultSettlements}</strong>
                    </Typography>
                    <Typography variant="body2">
                      Tracked-after-close observations:{' '}
                      <strong>{integrity.trackedAfterCloseObservations}</strong>
                    </Typography>
                    <Stack direction="row" flexWrap="wrap" gap={1}>
                      <Chip label={`Database ${integrity.databaseSettlements}`} size="small" />
                      <Chip label={`Heuristic ${integrity.heuristicSettlements}`} size="small" />
                      <Chip
                        color={integrity.voidedSettlements > 0 ? 'warning' : 'default'}
                        label={`Voided ${integrity.voidedSettlements}`}
                        size="small"
                      />
                    </Stack>
                  </>
                ) : (
                  <Typography color="text.secondary" variant="body2">
                    Integrity telemetry has not loaded yet.
                  </Typography>
                )}
              </Stack>
            </CardContent>
          </Card>
        </Grid>

        <Grid size={{ md: 3.5, xs: 12 }}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Stack spacing={1.2}>
                <Typography variant="h6">Query & Engine Readiness</Typography>
                <Divider />
                {benchmarkQuery.data ? (
                  <>
                    <Typography variant="body2">
                      Query speedup:{' '}
                      <strong>
                        {benchmarkQuery.data.speedupX === Number.POSITIVE_INFINITY
                          ? '∞'
                          : `${benchmarkQuery.data.speedupX.toFixed(2)}x`}
                      </strong>
                    </Typography>
                    <Typography variant="body2">
                      Optimized query time: <strong>{benchmarkQuery.data.optimizedMillis} ms</strong>
                    </Typography>
                    <Typography variant="body2">
                      Legacy scan: <strong>{benchmarkQuery.data.legacyScanMillis} ms</strong>
                    </Typography>
                    <Typography color="text.secondary" variant="body2">
                      Dataset: {benchmarkQuery.data.players} players • {benchmarkQuery.data.matches} matches
                    </Typography>
                  </>
                ) : (
                  <Typography color="text.secondary" variant="body2">
                    Benchmark telemetry is not available yet.
                  </Typography>
                )}
              </Stack>
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </Stack>
  )
}

interface MetricCardProps {
  label: string
  value: string
  secondary?: string
}

function MetricCard({ label, value, secondary }: MetricCardProps) {
  return (
    <Card>
      <CardContent>
        <Stack spacing={0.5}>
          <Typography color="text.secondary" variant="caption">
            {label}
          </Typography>
          <Typography sx={{ fontWeight: 700 }} variant="h5">
            {value}
          </Typography>
          {secondary ? (
            <Typography color="text.secondary" variant="caption">
              {secondary}
            </Typography>
          ) : null}
        </Stack>
      </CardContent>
    </Card>
  )
}

function abbreviateTrigger(value: string) {
  const raw = value.trim()
  if (raw.length <= 16) return raw
  return `${raw.slice(0, 13)}…`
}
