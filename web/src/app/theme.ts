import { createTheme } from '@mui/material/styles'

export const appTheme = createTheme({
  palette: {
    mode: 'light',
    primary: {
      main: '#0f7f76',
      dark: '#07544f',
      light: '#4bb8af',
    },
    secondary: {
      main: '#d95d39',
    },
    background: {
      default: '#f6f4ef',
      paper: '#fffdf8',
    },
    text: {
      primary: '#1b2727',
      secondary: '#45504f',
    },
  },
  shape: {
    borderRadius: 16,
  },
  typography: {
    fontFamily: '"Space Grotesk", "Segoe UI", sans-serif',
    h4: {
      fontFamily: '"Bricolage Grotesque", "Space Grotesk", sans-serif',
      fontWeight: 700,
      letterSpacing: '-0.015em',
    },
    h1: {
      fontFamily: '"Bricolage Grotesque", "Space Grotesk", sans-serif',
      fontWeight: 700,
      letterSpacing: '-0.02em',
    },
    h2: {
      fontFamily: '"Bricolage Grotesque", "Space Grotesk", sans-serif',
      fontWeight: 700,
      letterSpacing: '-0.01em',
    },
  },
  components: {
    MuiPaper: {
      styleOverrides: {
        root: {
          border: '1px solid rgba(27, 39, 39, 0.08)',
          boxShadow: '0 20px 36px -26px rgba(16, 29, 29, 0.32)',
        },
      },
    },
    MuiCard: {
      styleOverrides: {
        root: {
          borderRadius: 18,
        },
      },
    },
    MuiButton: {
      styleOverrides: {
        root: {
          textTransform: 'none',
          fontWeight: 700,
          borderRadius: 999,
        },
      },
    },
    MuiChip: {
      styleOverrides: {
        root: {
          fontWeight: 600,
        },
      },
    },
    MuiTableCell: {
      styleOverrides: {
        head: {
          fontWeight: 700,
          letterSpacing: '0.01em',
        },
      },
    },
  },
})
