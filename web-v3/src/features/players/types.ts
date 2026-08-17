export type Player = {
  id: number
  firstName: string
  lastName: string
  fullName: string
}

export type PlayerStatistics = {
  playerId: number
  playerName: string
  wins: number
  losses: number
  matches: number
  winPct: number
}

export type PlayerMatch = {
  id: number
  externalId: string | null
  date: string
  player1: Player
  player2: Player
  result: string | null
  player1SetsWon: number | null
  player2SetsWon: number | null
  winnerPlayerId: number | null
  complete: boolean
}
