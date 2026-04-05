import BoltRoundedIcon from '@mui/icons-material/BoltRounded'
import CheckCircleRoundedIcon from '@mui/icons-material/CheckCircleRounded'
import {
  Alert,
  Autocomplete,
  Box,
  Card,
  CardContent,
  Chip,
  Divider,
  FormControl,
  Grid,
  InputLabel,
  LinearProgress,
  List,
  ListItem,
  ListItemText,
  MenuItem,
  Select,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { Link as RouterLink, useSearchParams } from 'react-router-dom'

import { apiClient, apiErrorMessage } from '../lib/api'
import { asPct, asSigned } from '../lib/format'
import type { MatchupAnalysisDto, MatchupFeatureVectorDto } from '../types/api'

export function MatchupPage() {
  const [player1OverrideId, setPlayer1OverrideId] = useState<number | null | undefined>(undefined)
  const [player2OverrideId, setPlayer2OverrideId] = useState<number | null | undefined>(undefined)
  const [modelVersionOverride, setModelVersionOverride] = useState<string | null>(null)
  const [searchParams] = useSearchParams()

  const paramsPlayer1Id = parseSearchPlayerId(searchParams.get('player1Id'))
  const paramsPlayer2Id = parseSearchPlayerId(searchParams.get('player2Id'))
  const paramsModelVersion = searchParams.get('modelVersion')?.trim() || null
  const effectivePlayer1Id = player1OverrideId === undefined ? paramsPlayer1Id : player1OverrideId
  const effectivePlayer2Id = player2OverrideId === undefined ? paramsPlayer2Id : player2OverrideId
  const modelVersion = modelVersionOverride ?? paramsModelVersion ?? 'ENSEMBLE'

  const playersQuery = useQuery({
    queryKey: ['players'],
    queryFn: apiClient.getPlayers,
  })

  const player1 = useMemo(() => {
    if (!playersQuery.data || effectivePlayer1Id == null) return null
    return playersQuery.data.find((player) => player.id === effectivePlayer1Id) ?? null
  }, [playersQuery.data, effectivePlayer1Id])

  const player2 = useMemo(() => {
    if (!playersQuery.data || effectivePlayer2Id == null) return null
    return playersQuery.data.find((player) => player.id === effectivePlayer2Id) ?? null
  }, [playersQuery.data, effectivePlayer2Id])

  const matchupQuery = useQuery({
    queryKey: ['matchup', player1?.id, player2?.id, modelVersion],
    queryFn: () => apiClient.getMatchupAnalysis(Number(player1?.id), Number(player2?.id), modelVersion),
    enabled: Boolean(player1?.id) && Boolean(player2?.id) && player1?.id !== player2?.id,
  })

  const featuresQuery = useQuery({
    queryKey: ['matchup-features', player1?.id, player2?.id],
    queryFn: () => apiClient.getMatchupFeatures(Number(player1?.id), Number(player2?.id)),
    enabled: Boolean(player1?.id) && Boolean(player2?.id) && player1?.id !== player2?.id,
  })

  const modelRegistryQuery = useQuery({
    queryKey: ['model-registry', 'matchup-models'],
    queryFn: () => apiClient.getModelRegistry(undefined, 30),
  })

  const selectedReady = Boolean(player1?.id) && Boolean(player2?.id)
  const selectedSame = selectedReady && player1?.id === player2?.id

  const selectedPlayerNames = useMemo(() => {
    const p1 = player1?.fullName ?? 'Player 1'
    const p2 = player2?.fullName ?? 'Player 2'
    return { p1, p2 }
  }, [player1, player2])

  const modelOptions = useMemo(() => {
    const options = [
      { label: 'Ensemble (Recommended)', value: 'ENSEMBLE' },
      { label: 'Logistic Regression', value: 'LOGISTIC' },
      { label: 'RF-Like', value: 'RF_LIKE' },
      { label: 'GBT-Like', value: 'GBT_LIKE' },
      { label: 'Baseline', value: 'BASELINE' },
    ]
    const registryRows = modelRegistryQuery.data ?? []
    registryRows
      .filter((row) => row.active)
      .forEach((row) =>
        options.push({ label: `${row.modelFamily} (${row.modelVersion})`, value: row.modelVersion })
      )
    return options.filter(
      (row, index, arr) => arr.findIndex((candidate) => candidate.value === row.value) === index
    )
  }, [modelRegistryQuery.data])

  const sortedContributions = useMemo(
    () =>
      matchupQuery.data
        ? [...matchupQuery.data.featureContributions].sort(
            (left, right) => Math.abs(right.contribution) - Math.abs(left.contribution)
          )
        : [],
    [matchupQuery.data]
  )

  const matchupInsights = useMemo(
    () => buildMatchupInsights(matchupQuery.data, featuresQuery.data, selectedPlayerNames),
    [featuresQuery.data, matchupQuery.data, selectedPlayerNames]
  )

  return (
    <Stack spacing={2}>
      <Box>
        <Typography variant="h4">Matchup Lab</Typography>
        <Typography color="text.secondary">
          Compare two players with rating baseline, sample depth, support quality, and model probability.
        </Typography>
      </Box>

      <Alert severity="info">
        Matchup Lab uses the latest available rating and form snapshot for each player. Confidence and support
        depth reflect modeling uncertainty and sample quality, not whether a sportsbook market is currently
        visible.
      </Alert>

      <Card>
        <CardContent>
          <Grid container spacing={2}>
            <Grid size={{ md: 5, xs: 12 }}>
              <Tooltip title="Search and choose the first player in this matchup.">
                <Autocomplete
                  getOptionLabel={(option) => option.fullName}
                  onChange={(_, value) => setPlayer1OverrideId(value?.id ?? null)}
                  options={playersQuery.data ?? []}
                  renderInput={(params) => (
                    <TextField {...params} label="Player 1 (search)" placeholder="Type player name" />
                  )}
                  value={player1}
                />
              </Tooltip>
            </Grid>
            <Grid size={{ md: 5, xs: 12 }}>
              <Tooltip title="Search and choose the second player in this matchup.">
                <Autocomplete
                  getOptionLabel={(option) => option.fullName}
                  onChange={(_, value) => setPlayer2OverrideId(value?.id ?? null)}
                  options={playersQuery.data ?? []}
                  renderInput={(params) => (
                    <TextField {...params} label="Player 2 (search)" placeholder="Type player name" />
                  )}
                  value={player2}
                />
              </Tooltip>
            </Grid>
            <Grid size={{ md: 4, xs: 12 }}>
              <Tooltip title="Select which model generates matchup win probabilities.">
                <FormControl fullWidth>
                  <InputLabel id="model-label">Model</InputLabel>
                  <Select
                    label="Model"
                    labelId="model-label"
                    onChange={(event) => setModelVersionOverride(String(event.target.value))}
                    value={modelVersion}
                  >
                    {modelOptions.map((option) => (
                      <MenuItem key={option.value} value={option.value}>
                        {option.label}
                      </MenuItem>
                    ))}
                  </Select>
                </FormControl>
              </Tooltip>
            </Grid>
          </Grid>
        </CardContent>
      </Card>

      {selectedSame ? <Alert severity="warning">Choose two different players.</Alert> : null}
      {matchupQuery.isFetching || featuresQuery.isFetching ? <LinearProgress /> : null}
      {matchupQuery.error ? (
        <Alert severity="error">
          {apiErrorMessage(matchupQuery.error, 'Unable to load matchup analysis.')}
        </Alert>
      ) : null}
      {featuresQuery.error ? (
        <Alert severity="warning">
          {apiErrorMessage(featuresQuery.error, 'Feature reliability detail is temporarily unavailable.')}
        </Alert>
      ) : null}

      {matchupQuery.data ? (
        <Grid container spacing={2}>
          <Grid size={{ md: 6, xs: 12 }}>
            <ProbabilityCard
              confidenceHigh={matchupQuery.data.player1Probability.confidenceHigh}
              confidenceLow={matchupQuery.data.player1Probability.confidenceLow}
              label={selectedPlayerNames.p1}
              odds={matchupQuery.data.player1Probability.americanOdds}
              playerId={player1?.id}
              probability={matchupQuery.data.player1Probability.probability}
            />
          </Grid>
          <Grid size={{ md: 6, xs: 12 }}>
            <ProbabilityCard
              confidenceHigh={matchupQuery.data.player2Probability.confidenceHigh}
              confidenceLow={matchupQuery.data.player2Probability.confidenceLow}
              label={selectedPlayerNames.p2}
              odds={matchupQuery.data.player2Probability.americanOdds}
              playerId={player2?.id}
              probability={matchupQuery.data.player2Probability.probability}
            />
          </Grid>

          <Grid size={{ md: 6, xs: 12 }}>
            <Card sx={{ height: '100%' }}>
              <CardContent>
                <Stack spacing={1.25}>
                  <Typography gutterBottom variant="h6">
                    Decision Lens
                  </Typography>
                  <Typography variant="body2">
                    Lean: <strong>{matchupInsights.favoriteName}</strong> at{' '}
                    {asPct(matchupInsights.favoriteProbability)} with a confidence width of{' '}
                    <strong>{asPct(matchupInsights.favoriteConfidenceWidth)}</strong>.
                  </Typography>
                  <Typography variant="body2">
                    Fair line sits at{' '}
                    <strong>
                      {matchupInsights.favoriteOdds > 0
                        ? `+${matchupInsights.favoriteOdds}`
                        : matchupInsights.favoriteOdds}
                    </strong>{' '}
                    and the baseline rating view leans {matchupInsights.ratingLean}.
                  </Typography>
                  <Divider />
                  <Box>
                    <Typography color="text.secondary" gutterBottom variant="caption">
                      Why this matchup leans this way
                    </Typography>
                    <List dense disablePadding>
                      {matchupInsights.rationale.map((item) => (
                        <ListItem key={item} disableGutters sx={{ py: 0.25 }}>
                          <ListItemText primary={item} primaryTypographyProps={{ variant: 'body2' }} />
                        </ListItem>
                      ))}
                    </List>
                  </Box>
                  <Box>
                    <Typography color="text.secondary" gutterBottom variant="caption">
                      Cautions before acting
                    </Typography>
                    <List dense disablePadding>
                      {matchupInsights.cautions.map((item) => (
                        <ListItem key={item} disableGutters sx={{ py: 0.25 }}>
                          <ListItemText primary={item} primaryTypographyProps={{ variant: 'body2' }} />
                        </ListItem>
                      ))}
                    </List>
                  </Box>
                </Stack>
              </CardContent>
            </Card>
          </Grid>

          <Grid size={{ md: 6, xs: 12 }}>
            <Card sx={{ height: '100%' }}>
              <CardContent>
                <Stack spacing={1.1}>
                  <Typography gutterBottom variant="h6">
                    Rating Baseline
                  </Typography>
                  {featuresQuery.data ? (
                    <>
                      <RatingSnapshotLine
                        ciHigh={featuresQuery.data.player1Rating95PctInterval.high}
                        ciLow={featuresQuery.data.player1Rating95PctInterval.low}
                        elo={featuresQuery.data.player1.eloRating}
                        glicko={featuresQuery.data.player1.glickoRating}
                        label={selectedPlayerNames.p1}
                        rd={featuresQuery.data.player1.glickoRatingDeviation}
                        stability={featuresQuery.data.player1.ratingStability}
                      />
                      <RatingSnapshotLine
                        ciHigh={featuresQuery.data.player2Rating95PctInterval.high}
                        ciLow={featuresQuery.data.player2Rating95PctInterval.low}
                        elo={featuresQuery.data.player2.eloRating}
                        glicko={featuresQuery.data.player2.glickoRating}
                        label={selectedPlayerNames.p2}
                        rd={featuresQuery.data.player2.glickoRatingDeviation}
                        stability={featuresQuery.data.player2.ratingStability}
                      />
                      <Divider sx={{ my: 0.5 }} />
                      <Typography variant="body2">
                        Elo model lean (Player 1):{' '}
                        <strong>{asPct(featuresQuery.data.eloProbabilityPlayer1)}</strong>
                      </Typography>
                      <Typography variant="body2">
                        Glicko model lean (Player 1):{' '}
                        <strong>{asPct(featuresQuery.data.glickoProbabilityPlayer1)}</strong>
                      </Typography>
                    </>
                  ) : (
                    <Typography color="text.secondary" variant="body2">
                      Rating baselines appear once the matchup feature vector is available.
                    </Typography>
                  )}
                </Stack>
              </CardContent>
            </Card>
          </Grid>

          <Grid size={{ md: 6, xs: 12 }}>
            <Card sx={{ height: '100%' }}>
              <CardContent>
                <Stack spacing={1.1}>
                  <Typography gutterBottom variant="h6">
                    Sample Depth & Support Map
                  </Typography>
                  {featuresQuery.data ? (
                    <>
                      <ReliabilityRow
                        helper={`This is the blended depth of the matchup itself after shrinking low-sample inputs. ${featuresQuery.data.significanceSummary.thinSignalCount} thin signals and ${featuresQuery.data.significanceSummary.strongSignalCount} strong signals are in the current stack.`}
                        label="Blended sample depth"
                        value={featuresQuery.data.significanceSummary.sampleDepth}
                      />
                      <ReliabilityRow
                        helper="Head-to-head support only gets credit when the sample is deep enough to move us away from a 50/50 prior."
                        label="Head-to-Head support"
                        value={featuresQuery.data.significanceSummary.headToHeadSupport}
                      />
                      <ReliabilityRow
                        helper="Recent form support blends both players and answers whether recent results are deep enough to matter."
                        label="Recent form support"
                        value={featuresQuery.data.significanceSummary.recentFormSupport}
                      />
                      <ReliabilityRow
                        helper="Opponent-adjusted form asks if those recent wins came against strong enough opposition to trust."
                        label="Opponent-adjusted support"
                        value={featuresQuery.data.significanceSummary.opponentAdjustedSupport}
                      />
                      <ReliabilityRow
                        helper="Schedule support tells us whether recent opponent quality is deep enough to be a real separator."
                        label="Schedule support"
                        value={featuresQuery.data.significanceSummary.scheduleStrengthSupport}
                      />
                      <ReliabilityRow
                        helper="Baseline support combines rating stability with Elo/Glicko agreement, so it is our most structural read."
                        label="Baseline support"
                        value={featuresQuery.data.significanceSummary.baselineSupport}
                      />
                      <Divider sx={{ my: 0.5 }} />
                      <Stack direction="row" flexWrap="wrap" gap={1}>
                        <Chip
                          label={`Strongest: ${featuresQuery.data.significanceSummary.strongestSupportLabel} (${asPct(featuresQuery.data.significanceSummary.strongestSupportValue)})`}
                          size="small"
                        />
                        <Chip
                          color={
                            featuresQuery.data.significanceSummary.weakestSupportValue < 0.3
                              ? 'warning'
                              : 'default'
                          }
                          label={`Weakest: ${featuresQuery.data.significanceSummary.weakestSupportLabel} (${asPct(featuresQuery.data.significanceSummary.weakestSupportValue)})`}
                          size="small"
                        />
                        <Chip
                          label={`${featuresQuery.data.significanceSummary.usableSignalCount} usable signals`}
                          size="small"
                        />
                        <Chip
                          color={
                            featuresQuery.data.significanceSummary.thinSignalCount > 0 ? 'warning' : 'default'
                          }
                          label={`${featuresQuery.data.significanceSummary.thinSignalCount} thin signals`}
                          size="small"
                        />
                      </Stack>
                    </>
                  ) : (
                    <Typography color="text.secondary" variant="body2">
                      Sample depth and support tiers unlock with the matchup feature vector.
                    </Typography>
                  )}
                </Stack>
              </CardContent>
            </Card>
          </Grid>

          <Grid size={{ md: 6, xs: 12 }}>
            <Card sx={{ height: '100%' }}>
              <CardContent>
                <Stack spacing={1.1}>
                  <Typography gutterBottom variant="h6">
                    Signal Reliability
                  </Typography>
                  {featuresQuery.data ? (
                    <>
                      <ReliabilityRow
                        helper="Blends sample support and baseline stability across the full matchup feature set."
                        label="Overall matchup reliability"
                        value={featuresQuery.data.reliabilitySummary.overallReliability}
                      />
                      <ReliabilityRow
                        helper="How closely the Elo and Glicko baselines agree on the matchup lean."
                        label="Rating model agreement"
                        value={featuresQuery.data.reliabilitySummary.ratingAgreement}
                      />
                      <ReliabilityRow
                        helper={`Head-to-head should be treated lightly unless the sample is deep. Weight says ${reliabilityLabel(featuresQuery.data.headToHeadSampleWeight)} and stabilized reliability sits at ${reliabilityLabel(featuresQuery.data.headToHeadReliability)}.`}
                        label="Head-to-Head"
                        value={featuresQuery.data.headToHeadReliability}
                      />
                      <ReliabilityRow
                        helper={`Recent form for ${selectedPlayerNames.p1} is based on a ${reliabilityLabel(featuresQuery.data.player1.recentFormSampleWeight)} sample and stabilizes to ${reliabilityLabel(featuresQuery.data.player1.recentFormReliability)} confidence.`}
                        label={`${selectedPlayerNames.p1} recent form`}
                        value={featuresQuery.data.player1.recentFormReliability}
                      />
                      <ReliabilityRow
                        helper={`Recent form for ${selectedPlayerNames.p2} is based on a ${reliabilityLabel(featuresQuery.data.player2.recentFormSampleWeight)} sample and stabilizes to ${reliabilityLabel(featuresQuery.data.player2.recentFormReliability)} confidence.`}
                        label={`${selectedPlayerNames.p2} recent form`}
                        value={featuresQuery.data.player2.recentFormReliability}
                      />
                      <ReliabilityRow
                        helper="Opponent-adjusted form tells us whether recent wins came against stronger schedules, then shrinks that edge by the available support."
                        label="Opponent-adjusted form"
                        value={
                          (featuresQuery.data.player1.opponentAdjustedReliability +
                            featuresQuery.data.player2.opponentAdjustedReliability) /
                          2
                        }
                      />
                      <ReliabilityRow
                        helper="Schedule strength only matters if both players have enough recent opponent quality data, so this view highlights stabilized schedule confidence instead of raw match count alone."
                        label="Schedule strength"
                        value={
                          (featuresQuery.data.player1.scheduleStrengthReliability +
                            featuresQuery.data.player2.scheduleStrengthReliability) /
                          2
                        }
                      />
                      <ReliabilityRow
                        helper={`${selectedPlayerNames.p1} baseline stability reflects how settled the rating estimate is after accounting for rating deviation.`}
                        label={`${selectedPlayerNames.p1} baseline stability`}
                        value={featuresQuery.data.player1.ratingStability}
                      />
                      <ReliabilityRow
                        helper={`${selectedPlayerNames.p2} baseline stability reflects how settled the rating estimate is after accounting for rating deviation.`}
                        label={`${selectedPlayerNames.p2} baseline stability`}
                        value={featuresQuery.data.player2.ratingStability}
                      />
                    </>
                  ) : (
                    <Typography color="text.secondary" variant="body2">
                      Reliability weighting loads with the matchup feature vector.
                    </Typography>
                  )}
                </Stack>
              </CardContent>
            </Card>
          </Grid>

          <Grid size={{ md: 6, xs: 12 }}>
            <Card sx={{ height: '100%' }}>
              <CardContent>
                <Stack spacing={1.1}>
                  <Typography gutterBottom variant="h6">
                    Feature Snapshot
                  </Typography>
                  {featuresQuery.data ? (
                    <>
                      <FeatureSnapshotRow
                        helper={`${selectedPlayerNames.p1} minus ${selectedPlayerNames.p2}`}
                        label="Recent form delta"
                        value={featuresQuery.data.player1.recentForm - featuresQuery.data.player2.recentForm}
                      />
                      <FeatureSnapshotRow
                        helper="Opponent-adjusted delta rewards strong wins more than soft schedules."
                        label="Opponent-adjusted delta"
                        value={
                          featuresQuery.data.player1.opponentAdjustedForm -
                          featuresQuery.data.player2.opponentAdjustedForm
                        }
                      />
                      <FeatureSnapshotRow
                        helper="Positive values mean Player 1 has faced a tougher recent schedule."
                        label="Schedule strength delta"
                        value={
                          featuresQuery.data.player1.scheduleStrength -
                          featuresQuery.data.player2.scheduleStrength
                        }
                      />
                      <FeatureSnapshotRow
                        helper="Higher rating means stronger long-run baseline."
                        label="Elo rating delta"
                        value={featuresQuery.data.player1.eloRating - featuresQuery.data.player2.eloRating}
                      />
                      <FeatureSnapshotRow
                        helper="Lower Glicko rating deviation means the estimate is more stable."
                        label="Rating stability edge"
                        value={
                          featuresQuery.data.player2.glickoRatingDeviation -
                          featuresQuery.data.player1.glickoRatingDeviation
                        }
                      />
                    </>
                  ) : (
                    <Typography color="text.secondary" variant="body2">
                      Feature deltas unlock when the matchup feature vector is ready.
                    </Typography>
                  )}
                </Stack>
              </CardContent>
            </Card>
          </Grid>

          <Grid size={{ md: 6, xs: 12 }}>
            <Card>
              <CardContent>
                <Typography gutterBottom variant="h6">
                  Head-to-Head
                </Typography>
                <Typography>
                  {matchupQuery.data.headToHead.player1Name}: {matchupQuery.data.headToHead.player1Wins} wins
                </Typography>
                <Typography>
                  {matchupQuery.data.headToHead.player2Name}: {matchupQuery.data.headToHead.player2Wins} wins
                </Typography>
                <Typography color="text.secondary" sx={{ mt: 1 }} variant="body2">
                  Total complete matches: {matchupQuery.data.headToHead.totalMatches}
                </Typography>
                {featuresQuery.data ? (
                  <Typography color="text.secondary" sx={{ mt: 0.75 }} variant="body2">
                    Reliability weight: <strong>{asPct(featuresQuery.data.headToHeadSampleWeight)}</strong>{' '}
                    raw / <strong>{asPct(featuresQuery.data.headToHeadReliability)}</strong> stabilized (
                    {reliabilityLabel(featuresQuery.data.headToHeadReliability)}) • blended support{' '}
                    <strong>{asPct(featuresQuery.data.significanceSummary.headToHeadSupport)}</strong>
                  </Typography>
                ) : null}
              </CardContent>
            </Card>
          </Grid>

          <Grid size={{ md: 6, xs: 12 }}>
            <Card>
              <CardContent>
                <Typography gutterBottom variant="h6">
                  Recent Form Snapshot
                </Typography>
                <Stack spacing={0.75}>
                  <Typography variant="body2">
                    {selectedPlayerNames.p1}: {matchupQuery.data.player1Form.recentWins}/
                    {matchupQuery.data.player1Form.recentMatches} wins (
                    {asPct(matchupQuery.data.player1Form.recentWinPct)}) • set margin{' '}
                    {asSigned(matchupQuery.data.player1Form.averageSetMargin, 2)}
                  </Typography>
                  <Typography variant="body2">
                    {selectedPlayerNames.p2}: {matchupQuery.data.player2Form.recentWins}/
                    {matchupQuery.data.player2Form.recentMatches} wins (
                    {asPct(matchupQuery.data.player2Form.recentWinPct)}) • set margin{' '}
                    {asSigned(matchupQuery.data.player2Form.averageSetMargin, 2)}
                  </Typography>
                  <Typography color="text.secondary" variant="caption">
                    Streaks: {selectedPlayerNames.p1} {matchupQuery.data.player1Form.streakWin ? 'W' : 'L'}
                    {matchupQuery.data.player1Form.streak} • {selectedPlayerNames.p2}{' '}
                    {matchupQuery.data.player2Form.streakWin ? 'W' : 'L'}
                    {matchupQuery.data.player2Form.streak}
                  </Typography>
                </Stack>
              </CardContent>
            </Card>
          </Grid>

          <Grid size={{ md: 6, xs: 12 }}>
            <Card>
              <CardContent>
                <Typography gutterBottom variant="h6">
                  Feature Contributions
                </Typography>
                <Stack spacing={1}>
                  {sortedContributions.map((feature) => (
                    <ContributionBar
                      contribution={feature.contribution}
                      key={feature.feature}
                      label={feature.feature}
                    />
                  ))}
                </Stack>
              </CardContent>
            </Card>
          </Grid>

          {matchupQuery.data.modelComparison ? (
            <Grid size={{ xs: 12 }}>
              <Card>
                <CardContent>
                  <Typography gutterBottom variant="h6">
                    Model Comparison (Player 1)
                  </Typography>
                  <Stack direction={{ md: 'row', xs: 'column' }} spacing={1.5}>
                    <Typography variant="body2">
                      Baseline: {asPct(matchupQuery.data.modelComparison.baselineProbabilityPlayer1)}
                    </Typography>
                    <Typography variant="body2">
                      Elo: {asPct(matchupQuery.data.modelComparison.eloProbabilityPlayer1)}
                    </Typography>
                    <Typography variant="body2">
                      Glicko: {asPct(matchupQuery.data.modelComparison.glickoProbabilityPlayer1)}
                    </Typography>
                  </Stack>
                </CardContent>
              </Card>
            </Grid>
          ) : null}

          <Grid size={{ xs: 12 }}>
            <Alert icon={<CheckCircleRoundedIcon fontSize="inherit" />} severity="success">
              {matchupQuery.data.explanation}
            </Alert>
          </Grid>
        </Grid>
      ) : (
        <Card>
          <CardContent>
            <Stack alignItems="center" direction="row" spacing={1}>
              <BoltRoundedIcon color="primary" />
              <Typography color="text.secondary">Select two players to generate analysis.</Typography>
            </Stack>
          </CardContent>
        </Card>
      )}
    </Stack>
  )
}

interface ProbabilityCardProps {
  label: string
  probability: number
  confidenceLow: number
  confidenceHigh: number
  odds: number
  playerId?: number
}

function ProbabilityCard({
  label,
  probability,
  confidenceLow,
  confidenceHigh,
  odds,
  playerId,
}: ProbabilityCardProps) {
  return (
    <Card>
      <CardContent>
        <Stack spacing={1}>
          {playerId != null ? (
            <Typography
              component={RouterLink}
              sx={{ color: 'primary.dark', fontWeight: 700, textDecoration: 'none' }}
              to={`/players/${playerId}`}
              variant="h6"
            >
              {label}
            </Typography>
          ) : (
            <Typography variant="h6">{label}</Typography>
          )}
          <Typography variant="h4">{asPct(probability)}</Typography>
          <Divider />
          <Typography color="text.secondary" variant="body2">
            95% CI: {asPct(confidenceLow)} to {asPct(confidenceHigh)}
          </Typography>
          <Typography color="text.secondary" variant="body2">
            Fair American Odds: {odds > 0 ? `+${odds}` : odds}
          </Typography>
        </Stack>
      </CardContent>
    </Card>
  )
}

interface ContributionBarProps {
  label: string
  contribution: number
}

function ContributionBar({ label, contribution }: ContributionBarProps) {
  const width = Math.min(100, Math.abs(contribution) * 100)
  const positive = contribution >= 0
  return (
    <Stack spacing={0.5}>
      <Stack alignItems="center" direction="row" justifyContent="space-between">
        <Typography variant="body2">{label}</Typography>
        <Chip color={positive ? 'success' : 'default'} label={asSigned(contribution)} size="small" />
      </Stack>
      <Box sx={{ backgroundColor: 'action.hover', borderRadius: 8, height: 8, overflow: 'hidden' }}>
        <Box
          sx={{
            backgroundColor: positive ? 'success.main' : 'warning.main',
            height: '100%',
            transition: 'width 300ms ease',
            width: `${width}%`,
          }}
        />
      </Box>
    </Stack>
  )
}

interface MatchupInsights {
  favoriteName: string
  favoriteProbability: number
  favoriteConfidenceWidth: number
  favoriteOdds: number
  ratingLean: string
  rationale: string[]
  cautions: string[]
}

interface MatchupPlayerNames {
  p1: string
  p2: string
}

function buildMatchupInsights(
  matchup: MatchupAnalysisDto | undefined,
  features: MatchupFeatureVectorDto | undefined,
  names: MatchupPlayerNames
): MatchupInsights {
  const fallback: MatchupInsights = {
    favoriteName: names.p1,
    favoriteProbability: 0,
    favoriteConfidenceWidth: 0,
    favoriteOdds: 0,
    ratingLean: 'neutral',
    rationale: ['Select two players to unlock decision context.'],
    cautions: ['No matchup has been loaded yet.'],
  }
  if (!matchup) return fallback

  const player1Fav = matchup.player1Probability.probability >= matchup.player2Probability.probability
  const favoriteName = player1Fav ? names.p1 : names.p2
  const favoriteProbability = player1Fav
    ? matchup.player1Probability.probability
    : matchup.player2Probability.probability
  const favoriteConfidenceWidth = player1Fav
    ? matchup.player1Probability.confidenceHigh - matchup.player1Probability.confidenceLow
    : matchup.player2Probability.confidenceHigh - matchup.player2Probability.confidenceLow
  const favoriteOdds = player1Fav
    ? matchup.player1Probability.americanOdds
    : matchup.player2Probability.americanOdds

  const positiveSignals = matchup.featureContributions
    .filter((feature) => feature.contribution > 0)
    .sort((left, right) => right.contribution - left.contribution)
    .slice(0, 3)
    .map((feature) => `${feature.feature} supports ${names.p1} by ${asSigned(feature.contribution)}.`)
  const negativeSignals = matchup.featureContributions
    .filter((feature) => feature.contribution < 0)
    .sort((left, right) => left.contribution - right.contribution)
    .slice(0, 2)
    .map(
      (feature) => `${feature.feature} pushes back toward ${names.p2} by ${asSigned(feature.contribution)}.`
    )

  const cautions = [...negativeSignals]
  if (favoriteConfidenceWidth >= 0.28) {
    cautions.push(
      `Confidence band is still wide at ${asPct(favoriteConfidenceWidth)}, so stake discipline matters.`
    )
  }
  if (features && features.headToHeadSampleWeight < 0.35) {
    cautions.push(
      `Head-to-head sample weight is only ${asPct(features.headToHeadSampleWeight)}, so that angle should be treated lightly.`
    )
  }
  if (features && features.reliabilitySummary.overallReliability < 0.5) {
    cautions.push(
      `Overall matchup reliability is only ${asPct(features.reliabilitySummary.overallReliability)}, so this should be treated as a thin-read spot.`
    )
  }
  if (features && features.significanceSummary.thinSignalCount >= 2) {
    cautions.push(
      `${features.significanceSummary.thinSignalCount} signals are still thin, so this matchup leans more on prior baselines than deep supporting evidence.`
    )
  }
  if (features && features.significanceSummary.weakestSupportValue < 0.25) {
    cautions.push(
      `${features.significanceSummary.weakestSupportLabel} is especially thin at ${asPct(features.significanceSummary.weakestSupportValue)}, so treat that angle lightly.`
    )
  }
  if (features && features.reliabilitySummary.ratingAgreement < 0.55) {
    cautions.push(
      `Elo and Glicko disagree more than usual, which means the baseline model view is less settled.`
    )
  }
  if (
    features &&
    Math.max(features.player1.glickoRatingDeviation, features.player2.glickoRatingDeviation) > 120
  ) {
    cautions.push(
      'One side carries a high rating deviation, which means the baseline rating is less settled than usual.'
    )
  }

  let ratingLean = 'neutral'
  if (features) {
    const eloDelta = features.player1.eloRating - features.player2.eloRating
    if (Math.abs(eloDelta) < 20) ratingLean = 'essentially even'
    else ratingLean = eloDelta > 0 ? `${names.p1} by rating baseline` : `${names.p2} by rating baseline`
    positiveSignals.unshift(
      `${features.significanceSummary.strongestSupportLabel} is the deepest support layer at ${asPct(features.significanceSummary.strongestSupportValue)}.`
    )
  }

  return {
    favoriteName,
    favoriteProbability,
    favoriteConfidenceWidth,
    favoriteOdds,
    ratingLean,
    rationale: positiveSignals.length
      ? positiveSignals
      : ['The current model output leans on blended probability and form inputs.'],
    cautions: cautions.length ? cautions : ['No major caution flags surfaced from the current model inputs.'],
  }
}

function reliabilityLabel(value: number) {
  if (value >= 0.75) return 'strong'
  if (value >= 0.5) return 'usable'
  if (value >= 0.3) return 'developing'
  return 'thin'
}

interface RatingSnapshotLineProps {
  label: string
  elo: number
  glicko: number
  rd: number
  ciLow: number
  ciHigh: number
  stability?: number
}

function RatingSnapshotLine({ label, elo, glicko, rd, ciLow, ciHigh, stability }: RatingSnapshotLineProps) {
  return (
    <Stack spacing={0.3}>
      <Typography sx={{ fontWeight: 700 }} variant="body2">
        {label}
      </Typography>
      <Typography variant="body2">
        Elo <strong>{elo.toFixed(0)}</strong> • Glicko <strong>{glicko.toFixed(0)}</strong> • RD{' '}
        <strong>{rd.toFixed(1)}</strong>
      </Typography>
      <Typography color="text.secondary" variant="caption">
        Rating 95% interval: {ciLow.toFixed(0)} to {ciHigh.toFixed(0)}
        {stability != null ? ` • Stability ${asPct(stability)} (${reliabilityLabel(stability)})` : ''}
      </Typography>
    </Stack>
  )
}

interface ReliabilityRowProps {
  label: string
  value: number
  helper: string
}

function ReliabilityRow({ label, value, helper }: ReliabilityRowProps) {
  return (
    <Stack spacing={0.25}>
      <Stack alignItems="center" direction="row" justifyContent="space-between">
        <Typography variant="body2">{label}</Typography>
        <Chip label={`${asPct(value)} • ${reliabilityLabel(value)}`} size="small" />
      </Stack>
      <Typography color="text.secondary" variant="caption">
        {helper}
      </Typography>
    </Stack>
  )
}

interface FeatureSnapshotRowProps {
  label: string
  value: number
  helper: string
}

function FeatureSnapshotRow({ label, value, helper }: FeatureSnapshotRowProps) {
  const positive = value >= 0
  return (
    <Stack spacing={0.2}>
      <Stack alignItems="center" direction="row" justifyContent="space-between">
        <Typography variant="body2">{label}</Typography>
        <Chip color={positive ? 'success' : 'default'} label={asSigned(value, 2)} size="small" />
      </Stack>
      <Typography color="text.secondary" variant="caption">
        {helper}
      </Typography>
    </Stack>
  )
}

function parseSearchPlayerId(value: string | null): number | null {
  if (!value) return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}
