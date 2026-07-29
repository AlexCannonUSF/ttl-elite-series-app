export type ReliabilityBin = {
  lowerBound: number
  upperBound: number
  count: number
  meanPredicted: number
  observedRate: number
}

export type ReliabilitySnapshot = {
  label: string
  sampleCount: number
  ece: number | null
  maxBinDeviation: number | null
  brierScore: number | null
  bins: ReliabilityBin[]
}

export type HistogramBin = {
  lowerBound: number
  upperBound: number
  count: number
}

export type DailyCount = {
  date: string
  predictions: number
}

export type DriftSummary = {
  eceDelta: number | null
  meanPredictedDelta: number | null
  meanObservedDelta: number | null
  severity: 'GREEN' | 'AMBER' | 'RED' | 'UNKNOWN'
}

export type MlQualityResponse = {
  windowDays: number
  computedAtUtc: string
  training: ReliabilitySnapshot
  recent: ReliabilitySnapshot
  probabilityHistogram: HistogramBin[]
  dailyVolume: DailyCount[]
  drift: DriftSummary
}
