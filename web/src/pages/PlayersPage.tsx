import {
  Alert,
  Box,
  Card,
  CardContent,
  Chip,
  FormControl,
  Grid,
  InputLabel,
  LinearProgress,
  MenuItem,
  Pagination,
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
import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  Bar,
  CartesianGrid,
  ComposedChart,
  ResponsiveContainer,
  Tooltip as ReTooltip,
  XAxis,
  YAxis,
} from 'recharts'

import { apiClient } from '../lib/api'
import { asPct } from '../lib/format'

type SortField = 'name' | 'matches' | 'wins' | 'winPct' | 'trustedWinPct' | 'aliasCount'
type ProfileFilter = 'ALL' | 'TRUSTED' | 'HIGH_VOLUME' | 'THIN' | 'MULTI_ALIAS'

export function PlayersPage() {
  const [sortField, setSortField] = useState<SortField>('winPct')
  const [page, setPage] = useState(1)
  const [search, setSearch] = useState('')
  const [profileFilter, setProfileFilter] = useState<ProfileFilter>('ALL')

  const statsQuery = useQuery({
    queryKey: ['player-stats'],
    queryFn: apiClient.getPlayerStats,
  })
  const aliasesQuery = useQuery({
    queryKey: ['aliases', 'all-players'],
    queryFn: () => apiClient.getAliases(),
  })

  const pageSize = 12

  const aliasCounts = useMemo(() => {
    const counts = new Map<number, number>()
    ;(aliasesQuery.data ?? []).forEach((alias) => {
      counts.set(alias.playerId, (counts.get(alias.playerId) ?? 0) + 1)
    })
    return counts
  }, [aliasesQuery.data])

  const rows = useMemo(() => {
    const term = search.trim().toLowerCase()
    const items = [...(statsQuery.data ?? [])]
      .map((row) => {
        const reliability = Math.min(1, row.matches / 120)
        const trustedWinPct = row.winPct * (0.45 + 0.55 * reliability)
        const aliasCount = aliasCounts.get(row.playerId) ?? 0
        const profile =
          row.matches >= 150
            ? 'Elite sample'
            : row.matches >= 50
              ? 'High volume'
              : row.matches >= 20
                ? 'Developing sample'
                : 'Thin sample'
        return {
          ...row,
          aliasCount,
          profile,
          reliability,
          trustedWinPct,
        }
      })
      .filter((row) => {
        if (!term) return true
        return row.playerName.toLowerCase().includes(term)
      })
      .filter((row) => {
        if (profileFilter === 'ALL') return true
        if (profileFilter === 'TRUSTED') return row.matches >= 30
        if (profileFilter === 'HIGH_VOLUME') return row.matches >= 50
        if (profileFilter === 'THIN') return row.matches < 20
        return row.aliasCount >= 2
      })

    items.sort((a, b) => {
      if (sortField === 'name') return a.playerName.localeCompare(b.playerName)
      if (sortField === 'matches') return b.matches - a.matches
      if (sortField === 'wins') return b.wins - a.wins
      if (sortField === 'trustedWinPct') return b.trustedWinPct - a.trustedWinPct
      if (sortField === 'aliasCount') return b.aliasCount - a.aliasCount
      return b.winPct - a.winPct
    })

    return items
  }, [aliasCounts, profileFilter, statsQuery.data, sortField, search])

  const totalPages = Math.max(1, Math.ceil(rows.length / pageSize))
  const currentPage = Math.min(page, totalPages)
  const pageRows = rows.slice((currentPage - 1) * pageSize, currentPage * pageSize)
  const displayStart = rows.length ? (currentPage - 1) * pageSize + 1 : 0
  const displayEnd = rows.length ? Math.min(currentPage * pageSize, rows.length) : 0
  const highVolumeCount = rows.filter((row) => row.matches >= 50).length
  const avgWinPct = rows.length ? rows.reduce((sum, row) => sum + row.winPct, 0) / rows.length : 0
  const totalMatches = rows.reduce((sum, row) => sum + row.matches, 0)
  const multiAliasCount = rows.filter((row) => row.aliasCount >= 2).length
  const bestTrusted =
    [...rows]
      .filter((row) => row.matches >= 30)
      .sort((left, right) => right.trustedWinPct - left.trustedWinPct)[0] ?? null
  const highestVolume = [...rows].sort((left, right) => right.matches - left.matches)[0] ?? null
  const mostAliases = [...rows].sort((left, right) => right.aliasCount - left.aliasCount)[0] ?? null
  const topVolumeChartData = useMemo(
    () =>
      [...rows]
        .filter((row) => row.matches >= 10)
        .sort((a, b) => b.trustedWinPct - a.trustedWinPct)
        .slice(0, 8)
        .map((row) => ({
          player: row.playerName.length > 14 ? `${row.playerName.slice(0, 12)}…` : row.playerName,
          matches: row.matches,
          winPct: Number((row.winPct * 100).toFixed(1)),
          trustedWinPct: Number((row.trustedWinPct * 100).toFixed(1)),
        })),
    [rows]
  )

  return (
    <Stack spacing={2}>
      <Card>
        <CardContent>
          <Stack spacing={2}>
            <Stack direction={{ md: 'row', xs: 'column' }} justifyContent="space-between" spacing={2}>
              <BoxTitle
                title="Players Intelligence"
                subtitle="Searchable player board with reliability context, alias coverage, and scouting shortcuts"
              />
              <Stack direction={{ sm: 'row', xs: 'column' }} spacing={1}>
                <TextField
                  onChange={(event) => {
                    setSearch(event.target.value)
                    setPage(1)
                  }}
                  placeholder="Search player"
                  size="small"
                  value={search}
                />
                <FormControl size="small" sx={{ minWidth: 190 }}>
                  <InputLabel id="players-profile-filter-label">Profile view</InputLabel>
                  <Select
                    label="Profile view"
                    labelId="players-profile-filter-label"
                    onChange={(event) => {
                      setProfileFilter(event.target.value as ProfileFilter)
                      setPage(1)
                    }}
                    value={profileFilter}
                  >
                    <MenuItem value="ALL">All players</MenuItem>
                    <MenuItem value="TRUSTED">Trusted sample (30+)</MenuItem>
                    <MenuItem value="HIGH_VOLUME">High volume (50+)</MenuItem>
                    <MenuItem value="THIN">Thin sample (&lt;20)</MenuItem>
                    <MenuItem value="MULTI_ALIAS">Multi-alias watch</MenuItem>
                  </Select>
                </FormControl>
                <FormControl size="small" sx={{ minWidth: 170 }}>
                  <InputLabel id="players-sort-label">Sort by</InputLabel>
                  <Select
                    label="Sort by"
                    labelId="players-sort-label"
                    onChange={(event) => setSortField(event.target.value as SortField)}
                    value={sortField}
                  >
                    <MenuItem value="winPct">Win %</MenuItem>
                    <MenuItem value="trustedWinPct">Trusted Win %</MenuItem>
                    <MenuItem value="matches">Matches</MenuItem>
                    <MenuItem value="wins">Wins</MenuItem>
                    <MenuItem value="aliasCount">Alias depth</MenuItem>
                    <MenuItem value="name">Name</MenuItem>
                  </Select>
                </FormControl>
              </Stack>
            </Stack>

            {statsQuery.isLoading || aliasesQuery.isLoading ? <LinearProgress /> : null}

            <Grid container spacing={1}>
              <Grid size={{ md: 3, xs: 6 }}>
                <MetricTile label="Visible Players" value={`${rows.length}`} />
              </Grid>
              <Grid size={{ md: 3, xs: 6 }}>
                <MetricTile label="High Volume (50+)" value={`${highVolumeCount}`} />
              </Grid>
              <Grid size={{ md: 3, xs: 6 }}>
                <MetricTile label="Avg Win %" value={asPct(avgWinPct)} />
              </Grid>
              <Grid size={{ md: 3, xs: 6 }}>
                <MetricTile label="Match Samples" value={`${totalMatches}`} />
              </Grid>
              <Grid size={{ md: 3, xs: 6 }}>
                <MetricTile label="Multi-Alias Watch" value={`${multiAliasCount}`} />
              </Grid>
              <Grid size={{ md: 3, xs: 6 }}>
                <MetricTile
                  label="Best Trusted Profile"
                  value={bestTrusted ? bestTrusted.playerName : 'N/A'}
                  secondary={
                    bestTrusted
                      ? `${asPct(bestTrusted.trustedWinPct)} adjusted • ${bestTrusted.matches} matches`
                      : undefined
                  }
                />
              </Grid>
              <Grid size={{ md: 3, xs: 6 }}>
                <MetricTile
                  label="Largest Sample"
                  value={highestVolume ? highestVolume.playerName : 'N/A'}
                  secondary={highestVolume ? `${highestVolume.matches} matches` : undefined}
                />
              </Grid>
              <Grid size={{ md: 3, xs: 6 }}>
                <MetricTile
                  label="Most Alias Coverage"
                  value={mostAliases ? mostAliases.playerName : 'N/A'}
                  secondary={mostAliases ? `${mostAliases.aliasCount} aliases linked` : undefined}
                />
              </Grid>
            </Grid>

            <Alert severity="info">
              Player surfaces use the broader archived match history we have loaded, not just the active live
              session. <strong>Trusted Win %</strong> intentionally shrinks thin samples so a hot small sample
              does not read like established player quality.
            </Alert>
          </Stack>
        </CardContent>
      </Card>

      <Card>
        <CardContent>
          <Stack spacing={1}>
            <Typography variant="h6">Trusted Leader Snapshot</Typography>
            <Typography color="text.secondary" variant="body2">
              Reliability-adjusted leaders currently in view. This surfaces players who combine winning and
              usable sample depth instead of just raw win rate.
            </Typography>
            {!topVolumeChartData.length ? (
              <Alert severity="info">Need at least 10 matches for charted player reliability.</Alert>
            ) : (
              <Box sx={{ height: 260 }}>
                <ResponsiveContainer height="100%" width="100%">
                  <ComposedChart data={topVolumeChartData}>
                    <CartesianGrid strokeDasharray="3 3" />
                    <XAxis dataKey="player" interval={0} tick={{ fontSize: 12 }} />
                    <YAxis tickFormatter={(value) => `${value}`} width={56} yAxisId="matches" />
                    <YAxis
                      tickFormatter={(value) => `${value}%`}
                      width={52}
                      yAxisId="winPct"
                      orientation="right"
                    />
                    <ReTooltip
                      formatter={(value, name) => [
                        name === 'matches'
                          ? `${Number(value ?? 0)} matches`
                          : `${Number(value ?? 0).toFixed(1)}%`,
                        name === 'matches' ? 'Matches' : name === 'trustedWinPct' ? 'Trusted Win %' : 'Win %',
                      ]}
                    />
                    <Bar dataKey="matches" fill="#0f7f76" yAxisId="matches" radius={[8, 8, 0, 0]} />
                    <Bar dataKey="winPct" fill="#d95d39" yAxisId="winPct" radius={[8, 8, 0, 0]} />
                    <Bar dataKey="trustedWinPct" fill="#1d4ed8" yAxisId="winPct" radius={[8, 8, 0, 0]} />
                  </ComposedChart>
                </ResponsiveContainer>
              </Box>
            )}
          </Stack>
        </CardContent>
      </Card>

      {statsQuery.isError ? <Alert severity="error">Unable to load player stats.</Alert> : null}
      {aliasesQuery.isError ? (
        <Alert severity="warning">
          Alias intelligence is temporarily unavailable. Player identity depth and sportsbook coverage chips
          may be incomplete.
        </Alert>
      ) : null}

      <Card>
        <CardContent>
          <Table aria-label="players table" size="small">
            <TableHead>
              <TableRow>
                <TableCell>#</TableCell>
                <TableCell>Player</TableCell>
                <TableCell align="right">Matches</TableCell>
                <TableCell align="right">Wins</TableCell>
                <TableCell align="right">Losses</TableCell>
                <TableCell align="right">Win %</TableCell>
                <TableCell align="right">Trusted Win %</TableCell>
                <TableCell align="right">Reliability</TableCell>
                <TableCell align="right">Aliases</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {pageRows.map((row, idx) => {
                const ranking = (currentPage - 1) * pageSize + idx + 1
                const reliabilityPct = Math.min(100, Math.round(row.reliability * 100))
                return (
                  <TableRow hover key={row.playerId}>
                    <TableCell>{ranking}</TableCell>
                    <TableCell>
                      <Stack alignItems="center" direction="row" spacing={1}>
                        <Typography
                          component={Link}
                          sx={{ color: 'primary.dark', fontWeight: 700, textDecoration: 'none' }}
                          to={`/players/${row.playerId}`}
                        >
                          {row.playerName}
                        </Typography>
                        <Chip
                          color={
                            row.matches >= 150
                              ? 'success'
                              : row.matches >= 50
                                ? 'primary'
                                : row.matches >= 20
                                  ? 'warning'
                                  : 'default'
                          }
                          label={row.profile}
                          size="small"
                        />
                        {row.aliasCount >= 2 ? (
                          <Chip color="secondary" label="Alias-rich" size="small" />
                        ) : null}
                      </Stack>
                    </TableCell>
                    <TableCell align="right">{row.matches}</TableCell>
                    <TableCell align="right">{row.wins}</TableCell>
                    <TableCell align="right">{row.losses}</TableCell>
                    <TableCell align="right">{asPct(row.winPct)}</TableCell>
                    <TableCell align="right">{asPct(row.trustedWinPct)}</TableCell>
                    <TableCell align="right">
                      <Chip
                        color={
                          reliabilityPct >= 80 ? 'success' : reliabilityPct >= 45 ? 'warning' : 'default'
                        }
                        label={`${reliabilityPct}%`}
                        size="small"
                      />
                    </TableCell>
                    <TableCell align="right">
                      <Chip
                        color={row.aliasCount >= 2 ? 'primary' : 'default'}
                        label={row.aliasCount ? `${row.aliasCount}` : '0'}
                        size="small"
                      />
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <Stack alignItems="center" direction="row" justifyContent="space-between">
        <Typography color="text.secondary" variant="body2">
          Showing {displayStart}-{displayEnd} of {rows.length}
        </Typography>
        <Pagination count={totalPages} onChange={(_, value) => setPage(value)} page={currentPage} />
      </Stack>
    </Stack>
  )
}

interface BoxTitleProps {
  title: string
  subtitle: string
}

function BoxTitle({ title, subtitle }: BoxTitleProps) {
  return (
    <Stack>
      <Typography variant="h4">{title}</Typography>
      <Typography color="text.secondary">{subtitle}</Typography>
    </Stack>
  )
}

interface MetricTileProps {
  label: string
  value: string
  secondary?: string
}

function MetricTile({ label, value, secondary }: MetricTileProps) {
  return (
    <Stack>
      <Typography color="text.secondary" variant="caption">
        {label}
      </Typography>
      <Typography sx={{ fontWeight: 700 }} variant="h6">
        {value}
      </Typography>
      {secondary ? (
        <Typography color="text.secondary" variant="caption">
          {secondary}
        </Typography>
      ) : null}
    </Stack>
  )
}
