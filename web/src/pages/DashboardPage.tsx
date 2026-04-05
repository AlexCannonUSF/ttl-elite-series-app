import AutorenewRoundedIcon from '@mui/icons-material/AutorenewRounded'
import BoltRoundedIcon from '@mui/icons-material/BoltRounded'
import InsightsRoundedIcon from '@mui/icons-material/InsightsRounded'
import QueryStatsRoundedIcon from '@mui/icons-material/QueryStatsRounded'
import SearchRoundedIcon from '@mui/icons-material/SearchRounded'
import SpeedRoundedIcon from '@mui/icons-material/SpeedRounded'
import TaskAltRoundedIcon from '@mui/icons-material/TaskAltRounded'
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Divider,
  Grid,
  InputAdornment,
  List,
  ListItem,
  ListItemText,
  Skeleton,
  Stack,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { type ReactNode, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'

import { apiClient } from '../lib/api'
import { asDurationSeconds, asLocalDate, asPct } from '../lib/format'

export function DashboardPage() {
  const [search, setSearch] = useState('')
  const [valueStrategy, setValueStrategy] = useState<'CONSERVATIVE' | 'AGGRESSIVE'>('CONSERVATIVE')

  const playersQuery = useQuery({
    queryKey: ['players'],
    queryFn: apiClient.getPlayers,
  })

  const statsQuery = useQuery({
    queryKey: ['player-stats'],
    queryFn: apiClient.getPlayerStats,
  })

  const scrapeStatusQuery = useQuery({
    queryKey: ['scrape-status'],
    queryFn: apiClient.getScrapeStatus,
    refetchInterval: 5000,
  })

  const scrapeMetricsQuery = useQuery({
    queryKey: ['scrape-metrics'],
    queryFn: () => apiClient.getScrapeMetrics(300),
    refetchInterval: 15000,
  })

  const opportunitiesQuery = useQuery({
    queryKey: ['value-opportunities', valueStrategy],
    queryFn: () => apiClient.getValueOpportunities(valueStrategy, 8),
    refetchInterval: 15000,
  })

  const filteredPlayers = useMemo(() => {
    const players = playersQuery.data ?? []
    const term = search.trim().toLowerCase()
    if (!term) return players.slice(0, 8)
    return players.filter((p) => p.fullName.toLowerCase().includes(term)).slice(0, 8)
  }, [playersQuery.data, search])

  const totalMatches = useMemo(() => {
    const rows = statsQuery.data ?? []
    const counted = rows.reduce((sum, row) => sum + row.matches, 0)
    return Math.floor(counted / 2)
  }, [statsQuery.data])

  return (
    <Stack spacing={2.5}>
      <Box>
        <Typography variant="h4">Studio Overview</Typography>
        <Typography color="text.secondary" sx={{ maxWidth: 700 }}>
          Broader product overview across scrape health, value flow, player coverage, and model readiness.
        </Typography>
      </Box>

      <Alert severity="info">
        This page blends broader platform health with the latest live-system snapshot. For current-session
        trading state, use <strong>Live Studio</strong>. For model holdout and regime diagnostics, use{' '}
        <strong>Analytics Lab</strong>.
      </Alert>

      <Grid container spacing={2}>
        <Grid size={{ md: 2, xs: 12 }}>
          <MetricCard
            icon={<QueryStatsRoundedIcon />}
            label="Players"
            value={`${playersQuery.data?.length ?? 0}`}
          />
        </Grid>
        <Grid size={{ md: 2, xs: 12 }}>
          <MetricCard icon={<InsightsRoundedIcon />} label="Matches" value={`${totalMatches}`} />
        </Grid>
        <Grid size={{ md: 2, xs: 12 }}>
          <MetricCard
            icon={<BoltRoundedIcon />}
            label="Last Scrape"
            value={scrapeStatusQuery.data?.running ? 'Running' : 'Idle'}
            subValue={asLocalDate(scrapeStatusQuery.data?.finishedAt ?? null)}
          />
        </Grid>
        <Grid size={{ md: 2, xs: 12 }}>
          <MetricCard
            icon={<AutorenewRoundedIcon />}
            label="Saved This Run"
            value={`${scrapeStatusQuery.data?.savedMatches ?? 0}`}
            subValue={scrapeStatusQuery.data?.mode ?? 'IDLE'}
          />
        </Grid>
        <Grid size={{ md: 2, xs: 12 }}>
          <MetricCard
            icon={<TaskAltRoundedIcon />}
            label="Scrape Success"
            value={asPct(scrapeMetricsQuery.data?.successRate ?? 0)}
            subValue={`${scrapeMetricsQuery.data?.successRuns ?? 0}/${scrapeMetricsQuery.data?.totalRuns ?? 0} runs`}
          />
        </Grid>
        <Grid size={{ md: 2, xs: 12 }}>
          <MetricCard
            icon={<SpeedRoundedIcon />}
            label="Avg Run Time"
            value={asDurationSeconds(scrapeMetricsQuery.data?.averageDurationSeconds)}
            subValue={`P95 ${asDurationSeconds(scrapeMetricsQuery.data?.p95DurationSeconds)}`}
          />
        </Grid>
      </Grid>

      {scrapeStatusQuery.data?.error ? (
        <Alert severity="warning">Last scrape error: {scrapeStatusQuery.data.error}</Alert>
      ) : null}

      <Grid container spacing={2}>
        <Grid size={{ md: 6, xs: 12 }}>
          <Card>
            <CardContent>
              <Stack spacing={2}>
                <Typography variant="h6">Quick Player Search</Typography>
                <TextField
                  fullWidth
                  onChange={(event) => setSearch(event.target.value)}
                  placeholder="Search by player name"
                  slotProps={{
                    input: {
                      startAdornment: (
                        <InputAdornment position="start">
                          <SearchRoundedIcon fontSize="small" />
                        </InputAdornment>
                      ),
                    },
                  }}
                  value={search}
                />
                <Divider />
                {playersQuery.isLoading ? (
                  <Stack spacing={1}>
                    <Skeleton height={30} />
                    <Skeleton height={30} />
                    <Skeleton height={30} />
                  </Stack>
                ) : (
                  <List dense disablePadding>
                    {filteredPlayers.map((player) => (
                      <ListItem
                        component={Link}
                        disablePadding
                        key={player.id}
                        sx={{ py: 0.5, textDecoration: 'none' }}
                        to={`/players/${player.id}`}
                      >
                        <ListItemText
                          primary={player.fullName}
                          secondary={`${player.firstName} ${player.lastName}`}
                          slotProps={{ primary: { color: 'text.primary' } }}
                        />
                      </ListItem>
                    ))}
                  </List>
                )}
              </Stack>
            </CardContent>
          </Card>
        </Grid>

        <Grid size={{ md: 6, xs: 12 }}>
          <Card>
            <CardContent>
              <Stack spacing={2}>
                <Typography variant="h6">Top Value Opportunities (Current Model)</Typography>
                <Typography color="text.secondary" variant="body2">
                  {valueStrategy === 'CONSERVATIVE' ? 'Conservative' : 'Aggressive'} edge feed from live odds
                  normalization versus calibrated model probabilities.
                </Typography>
                <ToggleButtonGroup
                  color="primary"
                  exclusive
                  onChange={(_, next) => {
                    if (next) setValueStrategy(next)
                  }}
                  size="small"
                  value={valueStrategy}
                >
                  <ToggleButton value="CONSERVATIVE">Conservative</ToggleButton>
                  <ToggleButton value="AGGRESSIVE">Aggressive</ToggleButton>
                </ToggleButtonGroup>
                <Divider />
                {opportunitiesQuery.isLoading ? (
                  <Stack spacing={1}>
                    <Skeleton height={30} />
                    <Skeleton height={30} />
                    <Skeleton height={30} />
                  </Stack>
                ) : (
                  <List dense disablePadding>
                    {(opportunitiesQuery.data ?? []).map((opp, index) => (
                      <ListItem key={opp.id} sx={{ px: 0 }}>
                        <ListItemText
                          primary={`${index + 1}. ${opp.playerSideName}`}
                          secondary={`Edge ${asPct(opp.edge)} | Model ${asPct(opp.modelProbability)} vs Implied ${asPct(
                            opp.impliedProbability
                          )} | Odds ${opp.americanOdds > 0 ? `+${opp.americanOdds}` : opp.americanOdds}`}
                        />
                      </ListItem>
                    ))}
                  </List>
                )}
                <Box>
                  <Button component={Link} to="/matchup" variant="contained">
                    Open Matchup Lab
                  </Button>
                </Box>
              </Stack>
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </Stack>
  )
}

interface MetricCardProps {
  icon: ReactNode
  label: string
  value: string
  subValue?: string
}

function MetricCard({ icon, label, value, subValue }: MetricCardProps) {
  return (
    <Card sx={{ height: '100%' }}>
      <CardContent>
        <Stack spacing={1.2}>
          <Box aria-hidden sx={{ color: 'primary.main' }}>
            {icon}
          </Box>
          <Typography color="text.secondary" variant="body2">
            {label}
          </Typography>
          <Typography variant="h5">{value}</Typography>
          {subValue ? (
            <Typography color="text.secondary" variant="caption">
              {subValue}
            </Typography>
          ) : null}
        </Stack>
      </CardContent>
    </Card>
  )
}
