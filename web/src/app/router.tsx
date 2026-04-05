import { createBrowserRouter } from 'react-router-dom'

import { AppShell } from '../components/AppShell'

export const router = createBrowserRouter([
  {
    path: '/',
    element: <AppShell />,
    children: [
      {
        index: true,
        lazy: async () => {
          const module = await import('../pages/LiveOddsPage')
          return { Component: module.LiveOddsPage }
        },
      },
      {
        path: 'dashboard',
        lazy: async () => {
          const module = await import('../pages/DashboardPage')
          return { Component: module.DashboardPage }
        },
      },
      {
        path: 'players',
        lazy: async () => {
          const module = await import('../pages/PlayersPage')
          return { Component: module.PlayersPage }
        },
      },
      {
        path: 'players/:playerId',
        lazy: async () => {
          const module = await import('../pages/PlayerDetailPage')
          return { Component: module.PlayerDetailPage }
        },
      },
      {
        path: 'matchup',
        lazy: async () => {
          const module = await import('../pages/MatchupPage')
          return { Component: module.MatchupPage }
        },
      },
      {
        path: 'live-odds',
        lazy: async () => {
          const module = await import('../pages/LiveOddsPage')
          return { Component: module.LiveOddsPage }
        },
      },
      {
        path: 'analytics',
        lazy: async () => {
          const module = await import('../pages/AnalyticsPage')
          return { Component: module.AnalyticsPage }
        },
      },
      {
        path: 'admin',
        lazy: async () => {
          const module = await import('../pages/AdminPage')
          return { Component: module.AdminPage }
        },
      },
    ],
  },
])
