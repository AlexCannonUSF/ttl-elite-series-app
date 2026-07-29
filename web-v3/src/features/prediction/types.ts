export type PredictionProbability = {
  value: number
  intervalLow: number
  intervalHigh: number
}

export type PredictionConformal = {
  coverage: number
  alpha: number
  label: string
  intervalLow: number
  intervalHigh: number
  quantile: number
  method: string
  predictionSet: string[]
  groupKey: string
}

export type PredictionContribution = {
  feature: string
  contribution: number
}

export type ReliabilityBin = {
  lowerBound: number
  upperBound: number
  count: number
  meanPredicted: number
  observedRate: number
}

export type PredictionPanelResponse = {
  matchKey: string
  player1Id: number
  player2Id: number
  modelFamily: string
  modelVersion: string
  calibrationMethod: string
  pTop: PredictionProbability
  pBot: PredictionProbability
  conformal: PredictionConformal
  topContributions: PredictionContribution[]
  reliabilityCurve: ReliabilityBin[]
  computedAtUtc: string
}

export type PredictionPanelQuery = {
  player1Id: number
  player2Id: number
  asOfDate?: string
  modelFamily?: string
  topK?: number
}
