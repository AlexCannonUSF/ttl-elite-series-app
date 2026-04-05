import ArrowBackRoundedIcon from '@mui/icons-material/ArrowBackRounded'
import {
  Alert,
  Button,
  Card,
  CardContent,
  Chip,
  Divider,
  Grid,
  Skeleton,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'

import { apiClient } from '../lib/api'
import { asLocalDate, asPct } from '../lib/format'

export function PlayerDetailPage() {
  const params = useParams<{ playerId: string }>()
  const playerId = Number(params.playerId)

  const playersQuery = useQuery({ queryKey: ['players'], queryFn: apiClient.getPlayers })
  const matchesQuery = useQuery({
    queryKey: ['recent-matches', playerId],
    queryFn: () => apiClient.getRecentMatchesForPlayer(playerId, 250),
    enabled: Number.isFinite(playerId),
  })
  const ratingsQuery = useQuery({
    queryKey: ['ratings', playerId],
    queryFn: () => apiClient.getRatingHistory(playerId),
    enabled: Number.isFinite(playerId),
  })
  const aliasesQuery = useQuery({
    queryKey: ['aliases', playerId],
    queryFn: () => apiClient.getAliases(playerId),
    enabled: Number.isFinite(playerId),
  })
  const playerStatsQuery = useQuery({ queryKey: ['player-stats'], queryFn: apiClient.getPlayerStats })

  const player = (playersQuery.data ?? []).find((row) => row.id === playerId)

  if (!Number.isFinite(playerId)) {
    return <Alert severity="error">Invalid player id.</Alert>
  }

  const allMatches = matchesQuery.data ?? []
  const completedMatches = allMatches.filter((match) => match.complete && match.winnerPlayerId != null)

  const outcomeFor = (winnerPlayerId: number | null | undefined) => winnerPlayerId === playerId

  const recentWinRate = (window: number) => {
    const sample = completedMatches.slice(0, window)
    if (!sample.length) return 0
    const wins = sample.filter((match) => outcomeFor(match.winnerPlayerId)).length
    return wins / sample.length
  }

  const averageSetMargin = (() => {
    const sample = completedMatches.filter(
      (match) => match.player1SetsWon != null && match.player2SetsWon != null
    )
    if (!sample.length) return 0
    const total = sample.reduce((sum, match) => {
      const isP1 = match.player1.id === playerId
      const won = isP1 ? (match.player1SetsWon ?? 0) : (match.player2SetsWon ?? 0)
      const lost = isP1 ? (match.player2SetsWon ?? 0) : (match.player1SetsWon ?? 0)
      return sum + (won - lost)
    }, 0)
    return total / sample.length
  })()

  const { currentStreak, longestStreak } = (() => {
    let current = 0
    let longest = 0
    let running = 0

    completedMatches.forEach((match, index) => {
      if (outcomeFor(match.winnerPlayerId)) {
        running += 1
      } else {
        if (index === 0) {
          current = 0
        }
        running = 0
      }
      if (index === 0) {
        current = running
      }
      longest = Math.max(longest, running)
    })

    return { currentStreak: current, longestStreak: longest }
  })()

  const decidingSetMatches = completedMatches.filter((match) => {
    const a = match.player1SetsWon ?? 0
    const b = match.player2SetsWon ?? 0
    return a + b >= 5 && Math.abs(a - b) === 1
  }).length

  const dominantWins = completedMatches.filter((match) => {
    if (!outcomeFor(match.winnerPlayerId)) return false
    const isP1 = match.player1.id === playerId
    const won = isP1 ? (match.player1SetsWon ?? 0) : (match.player2SetsWon ?? 0)
    const lost = isP1 ? (match.player2SetsWon ?? 0) : (match.player1SetsWon ?? 0)
    return won >= 3 && lost <= 1
  }).length

  const playerTotals = (playerStatsQuery.data ?? []).find((row) => row.playerId === playerId)

  const resultsSeries = completedMatches.slice(0, 60).map((match, index) => {
    const isP1 = match.player1.id === playerId
    const won = outcomeFor(match.winnerPlayerId)
    const setsWon = isP1 ? (match.player1SetsWon ?? 0) : (match.player2SetsWon ?? 0)
    const setsLost = isP1 ? (match.player2SetsWon ?? 0) : (match.player1SetsWon ?? 0)
    const upToThis = completedMatches.slice(0, index + 1)
    const rollingWins = upToThis.filter((m) => outcomeFor(m.winnerPlayerId)).length

    return {
      date: match.date,
      matchNo: `${index + 1}`,
      rollingWinPct: rollingWins / upToThis.length,
      setsWon,
      setsLost,
      won: won ? 1 : 0,
    }
  })

  const ratingSeries = (ratingsQuery.data ?? []).map((snapshot) => ({
    snapshotDate: snapshot.snapshotDate,
    rating: snapshot.rating,
    rd: snapshot.ratingDeviation ?? 0,
    low: snapshot.confidenceLow ?? snapshot.rating - 2 * (snapshot.ratingDeviation ?? 0),
    high: snapshot.confidenceHigh ?? snapshot.rating + 2 * (snapshot.ratingDeviation ?? 0),
  }))

  const latestElo = (ratingsQuery.data ?? []).find(
    (snapshot) => snapshot.ratingSystem?.toUpperCase() === 'ELO'
  )
  const latestGlicko = (ratingsQuery.data ?? []).find((snapshot) =>
    snapshot.ratingSystem?.toUpperCase().includes('GLICKO')
  )
  const playerAliases = aliasesQuery.data ?? []

  return (
    <Stack spacing={2}>
      <Stack alignItems="center" direction="row" justifyContent="space-between">
        <Stack>
          <Typography variant="h4">{player ? player.fullName : 'Player Intelligence'}</Typography>
          <Typography color="text.secondary">
            Rolling archive profile with recent form, alias coverage, and rating stability.
          </Typography>
        </Stack>
        <Button component={Link} startIcon={<ArrowBackRoundedIcon />} to="/players" variant="outlined">
          Back to Players
        </Button>
      </Stack>

      <Alert severity="info">
        This profile combines the latest loaded match archive with the latest rating snapshots we have on
        file. Recent windows are rolling player form samples; rating stability reflects confidence in the
        rating, not guaranteed future performance.
      </Alert>

      <Grid container spacing={2}>
        <Grid size={{ md: 2, xs: 6 }}>
          <MetricCard label="Career Win %" value={asPct(playerTotals?.winPct ?? recentWinRate(50))} />
        </Grid>
        <Grid size={{ md: 2, xs: 6 }}>
          <MetricCard label="Career Matches" value={`${playerTotals?.matches ?? completedMatches.length}`} />
        </Grid>
        <Grid size={{ md: 2, xs: 6 }}>
          <MetricCard label="Recent 10" value={asPct(recentWinRate(10))} />
        </Grid>
        <Grid size={{ md: 2, xs: 6 }}>
          <MetricCard label="Recent 20" value={asPct(recentWinRate(20))} />
        </Grid>
        <Grid size={{ md: 2, xs: 6 }}>
          <MetricCard label="Set Margin" value={averageSetMargin.toFixed(2)} />
        </Grid>
        <Grid size={{ md: 2, xs: 6 }}>
          <MetricCard
            label="Current Streak"
            value={`${currentStreak}W`}
            secondary={`Best ${longestStreak}W`}
          />
        </Grid>
      </Grid>

      <Grid container spacing={2}>
        <Grid size={{ md: 8, xs: 12 }}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Typography gutterBottom variant="h6">
                Recent Performance Trend
              </Typography>
              <ChartShell height={280}>
                <LineChart data={resultsSeries}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="matchNo" />
                  <YAxis domain={[0, 1]} yAxisId="win" />
                  <YAxis domain={[0, 3]} orientation="right" yAxisId="sets" />
                  <Tooltip />
                  <Legend />
                  <Line
                    dataKey="rollingWinPct"
                    dot={false}
                    name="Rolling Win %"
                    stroke="#0f7f76"
                    strokeWidth={2.2}
                    type="monotone"
                    yAxisId="win"
                  />
                  <Line
                    dataKey="setsWon"
                    dot={false}
                    name="Sets Won"
                    stroke="#1d4ed8"
                    strokeWidth={1.8}
                    type="monotone"
                    yAxisId="sets"
                  />
                  <Line
                    dataKey="setsLost"
                    dot={false}
                    name="Sets Lost"
                    stroke="#d95d39"
                    strokeWidth={1.8}
                    type="monotone"
                    yAxisId="sets"
                  />
                </LineChart>
              </ChartShell>
            </CardContent>
          </Card>
        </Grid>

        <Grid size={{ md: 4, xs: 12 }}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Stack spacing={1.2}>
                <Typography variant="h6">Pressure Indicators</Typography>
                <Divider />
                <Typography>
                  Dominant wins (3:0 / 3:1 style): <strong>{dominantWins}</strong>
                </Typography>
                <Typography>
                  Deciding-set matches: <strong>{decidingSetMatches}</strong>
                </Typography>
                <Typography>
                  Loaded match sample: <strong>{completedMatches.length}</strong>
                </Typography>
                <Typography>
                  Latest Elo: <strong>{latestElo ? latestElo.rating.toFixed(0) : 'N/A'}</strong>
                  {latestElo ? ` (${asLocalDate(latestElo.snapshotDate)})` : ''}
                </Typography>
                <Typography>
                  Latest Glicko: <strong>{latestGlicko ? latestGlicko.rating.toFixed(0) : 'N/A'}</strong>
                  {latestGlicko ? ` (${asLocalDate(latestGlicko.snapshotDate)})` : ''}
                </Typography>
                <Typography color="text.secondary" variant="body2">
                  These indicators help gauge stability, resilience, and style against opponents.
                </Typography>
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
                <Typography variant="h6">Sportsbook Identity</Typography>
                {aliasesQuery.isLoading ? (
                  <Skeleton height={88} />
                ) : playerAliases.length === 0 ? (
                  <Typography color="text.secondary" variant="body2">
                    No sportsbook aliases are linked yet. When names diverge across feeds, this is the first
                    place to check.
                  </Typography>
                ) : (
                  <>
                    <Stack direction="row" flexWrap="wrap" gap={1}>
                      {playerAliases.slice(0, 8).map((alias) => (
                        <Chip key={alias.id} label={alias.aliasName} size="small" />
                      ))}
                    </Stack>
                    <Typography color="text.secondary" variant="body2">
                      Normalized aliases help connect live sportsbook names, tracked scores, and settled
                      results without relying on weak fuzzy matching.
                    </Typography>
                  </>
                )}
              </Stack>
            </CardContent>
          </Card>
        </Grid>
        <Grid size={{ md: 7, xs: 12 }}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Stack spacing={1.1}>
                <Typography variant="h6">Rating Stability Readout</Typography>
                <Typography variant="body2">
                  Elo is the steady long-run baseline. Glicko adds uncertainty through rating deviation and
                  volatility so we can see whether a player is genuinely stable or just currently hot.
                </Typography>
                <Divider />
                <Typography variant="body2">
                  Latest Elo confidence read:{' '}
                  <strong>{latestElo ? latestElo.rating.toFixed(0) : 'N/A'}</strong>
                </Typography>
                <Typography variant="body2">
                  Latest Glicko deviation:{' '}
                  <strong>
                    {latestGlicko?.ratingDeviation != null ? latestGlicko.ratingDeviation.toFixed(1) : 'N/A'}
                  </strong>
                </Typography>
                <Typography variant="body2">
                  Latest volatility:{' '}
                  <strong>
                    {latestGlicko?.volatility != null ? latestGlicko.volatility.toFixed(4) : 'N/A'}
                  </strong>
                </Typography>
                <Typography color="text.secondary" variant="body2">
                  Lower deviation means the rating is more trustworthy. Higher deviation means we should
                  respect the upside but keep more uncertainty in pricing.
                </Typography>
              </Stack>
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      <Card>
        <CardContent>
          <Typography gutterBottom variant="h6">
            Rating + Confidence Bands
          </Typography>
          {ratingsQuery.isLoading ? (
            <Skeleton height={260} />
          ) : ratingSeries.length === 0 ? (
            <Alert severity="info">
              No rating snapshots yet. Build Glicko/Elo snapshots to unlock this chart.
            </Alert>
          ) : (
            <ChartShell height={300}>
              <LineChart data={ratingSeries}>
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis dataKey="snapshotDate" />
                <YAxis />
                <Tooltip />
                <Legend />
                <Line
                  dataKey="rating"
                  dot={false}
                  name="Rating"
                  stroke="#0f7f76"
                  strokeWidth={2.4}
                  type="monotone"
                />
                <Line
                  dataKey="low"
                  dot={false}
                  name="CI Low"
                  stroke="#a855f7"
                  strokeWidth={1.4}
                  type="monotone"
                />
                <Line
                  dataKey="high"
                  dot={false}
                  name="CI High"
                  stroke="#f97316"
                  strokeWidth={1.4}
                  type="monotone"
                />
              </LineChart>
            </ChartShell>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardContent>
          <Typography gutterBottom variant="h6">
            Recent Match Log
          </Typography>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Date</TableCell>
                <TableCell>Opponent</TableCell>
                <TableCell align="right">Result</TableCell>
                <TableCell align="right">Scoreline</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {completedMatches.slice(0, 20).map((match) => {
                const isP1 = match.player1.id === playerId
                const opponent = isP1 ? match.player2.fullName : match.player1.fullName
                const won = outcomeFor(match.winnerPlayerId)
                const wonSets = isP1 ? match.player1SetsWon : match.player2SetsWon
                const lostSets = isP1 ? match.player2SetsWon : match.player1SetsWon
                return (
                  <TableRow key={match.id}>
                    <TableCell>{asLocalDate(match.date)}</TableCell>
                    <TableCell>{opponent}</TableCell>
                    <TableCell align="right">
                      <Chip color={won ? 'success' : 'default'} label={won ? 'W' : 'L'} size="small" />
                    </TableCell>
                    <TableCell align="right">
                      {wonSets ?? '-'}:{lostSets ?? '-'}
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
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
        <Stack spacing={0.8}>
          <Typography color="text.secondary" variant="body2">
            {label}
          </Typography>
          <Typography variant="h5">{value}</Typography>
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

interface ChartShellProps {
  children: ReactNode
  height: number
}

function ChartShell({ children, height }: ChartShellProps) {
  return (
    <ResponsiveContainer height={height} width="100%">
      {children}
    </ResponsiveContainer>
  )
}
