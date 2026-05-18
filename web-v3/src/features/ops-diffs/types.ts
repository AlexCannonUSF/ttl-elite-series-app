export type OpsSettlementDiffFocus = 'ALL' | 'CONTRADICTION' | 'AMBIGUITY' | 'DISAGREEMENT'

export type OpsSettlementDiffRow = {
  betId: number
  diffKind: string
  oldReason: string | null
  newReason: string | null
  oldWinner: number | null
  newWinner: number | null
  decidedAt: string | null
  correlationId: string | null
}

export type OpsSettlementDiffSummary = {
  totalRows: number
  agreeRows: number
  disagreementRows: number
  contradictionRows: number
  outcomeDiffRows: number
}

export type OpsSettlementDiffsResponse = {
  generatedAt: string
  focus: OpsSettlementDiffFocus
  page: number
  size: number
  filteredRows: number
  totalPages: number
  hasPrevious: boolean
  hasNext: boolean
  summary: OpsSettlementDiffSummary
  rows: OpsSettlementDiffRow[]
}
