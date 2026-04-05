import BoltRoundedIcon from '@mui/icons-material/BoltRounded'
import DashboardRoundedIcon from '@mui/icons-material/DashboardRounded'
import Groups2RoundedIcon from '@mui/icons-material/Groups2Rounded'
import InsightsRoundedIcon from '@mui/icons-material/InsightsRounded'
import ManageAccountsRoundedIcon from '@mui/icons-material/ManageAccountsRounded'
import SportsTennisRoundedIcon from '@mui/icons-material/SportsTennisRounded'
import { AppBar, Box, Chip, Container, IconButton, Stack, Toolbar, Tooltip, Typography } from '@mui/material'
import { NavLink, Outlet } from 'react-router-dom'

const navItems = [
  {
    to: '/',
    label: 'Live Studio',
    icon: <BoltRoundedIcon fontSize="small" />,
    hint: 'Real-time board, tracked scores, paper positions, and settlement health.',
  },
  {
    to: '/dashboard',
    label: 'Overview',
    icon: <DashboardRoundedIcon fontSize="small" />,
    hint: 'High-level product, scrape, and model readiness overview.',
  },
  {
    to: '/players',
    label: 'Players Intelligence',
    icon: <Groups2RoundedIcon fontSize="small" />,
    hint: 'Search players, inspect aliases, and open deeper scouting profiles.',
  },
  {
    to: '/matchup',
    label: 'Matchup Lab',
    icon: <SportsTennisRoundedIcon fontSize="small" />,
    hint: 'Compare two players with model probabilities, support depth, and decision notes.',
  },
  {
    to: '/analytics',
    label: 'Analytics Lab',
    icon: <InsightsRoundedIcon fontSize="small" />,
    hint: 'Inspect calibration, regime drift, trigger behavior, and model readiness.',
  },
  {
    to: '/admin',
    label: 'Operations',
    icon: <ManageAccountsRoundedIcon fontSize="small" />,
    hint: 'Monitor source health, scrape reliability, aliases, and maintenance jobs.',
  },
]

export function AppShell() {
  return (
    <Box className="app-shell">
      <AppBar
        color="transparent"
        elevation={0}
        position="sticky"
        sx={{
          backdropFilter: 'blur(12px)',
          borderBottom: '1px solid rgba(15, 24, 24, 0.08)',
          backgroundColor: 'rgba(255, 255, 255, 0.55)',
        }}
      >
        <Container maxWidth="xl">
          <Toolbar disableGutters sx={{ gap: 2, justifyContent: 'space-between' }}>
            <Stack alignItems="center" direction="row" spacing={1.5}>
              <Tooltip title="TTL Elite Series studio home.">
                <IconButton
                  aria-label="TTL Elite home"
                  color="primary"
                  sx={{ bgcolor: 'rgba(15,127,118,0.12)' }}
                >
                  <SportsTennisRoundedIcon />
                </IconButton>
              </Tooltip>
              <Box>
                <Typography variant="h6">TTL Elite Series 2.0</Typography>
                <Typography color="text.secondary" variant="caption">
                  Live Studio, intelligence, analytics, and operations
                </Typography>
              </Box>
            </Stack>

            <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', justifyContent: 'flex-end' }}>
              {navItems.map((item) => (
                <NavLink key={item.to} to={item.to}>
                  {({ isActive }) => (
                    <Tooltip title={item.hint}>
                      <Chip
                        color={isActive ? 'primary' : 'default'}
                        icon={item.icon}
                        label={item.label}
                        sx={{
                          border: '1px solid rgba(27,39,39,0.12)',
                          bgcolor: isActive ? 'primary.main' : 'rgba(255,253,248,0.9)',
                          color: isActive ? '#fff' : 'text.primary',
                          fontWeight: 600,
                          transition: 'all 160ms ease',
                          '&:hover': {
                            transform: 'translateY(-1px)',
                            boxShadow: '0 10px 16px -12px rgba(6, 25, 25, 0.55)',
                          },
                        }}
                      />
                    </Tooltip>
                  )}
                </NavLink>
              ))}
            </Stack>
          </Toolbar>
        </Container>
      </AppBar>

      <Container maxWidth="xl" sx={{ pb: 6, pt: 2 }}>
        <Outlet />
      </Container>
    </Box>
  )
}
