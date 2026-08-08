import axios from 'axios'

import type {
  AdaptiveRegimeProfileDto,
  CompletedMatchLogDto,
  DryRunPreviewDto,
  DuplicatePlayerCandidateDto,
  Glicko2RebuildDto,
  Glicko2TauTuningDto,
  LiveOddsRecommendationDto,
  LiveStudioIntegrityDto,
  MatchDto,
  MatchupAnalysisDto,
  MatchupFeatureVectorDto,
  ModelRegistryEntryDto,
  ModelTrainingReportDto,
  OddsRefreshResultDto,
  PaperTradeBetDto,
  PaperTradingSessionDto,
  PaperTradingSyncResultDto,
  PlayerAliasDto,
  PlayerDto,
  PlayerStatisticsDto,
  RatingSnapshotDto,
  ScrapeErrorRecordDto,
  ScrapeMetricsDto,
  ScrapeRunRecordDto,
  ScrapeStatusDto,
  SettlementReviewPageDto,
  StatisticsBenchmarkDto,
  TrackedMatchObservationDto,
  ValueOpportunityDto,
} from '../types/api'

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

export const api = axios.create({
  baseURL: apiBaseUrl,
  timeout: 15000,
})

export function apiErrorMessage(error: unknown, fallback = 'Request failed') {
  if (axios.isAxiosError(error)) {
    const payload = error.response?.data
    if (typeof payload === 'string' && payload.trim()) {
      return payload.trim()
    }
    const fromApi = payload?.message ?? payload?.error
    if (typeof fromApi === 'string' && fromApi.trim()) {
      return fromApi.trim()
    }
    if (typeof error.message === 'string' && error.message.trim()) {
      return error.message.trim()
    }
  }
  return fallback
}

export const apiClient = {
  getPlayers: async () => (await api.get<PlayerDto[]>('/api/players')).data,
  getPlayerStats: async () => (await api.get<PlayerStatisticsDto[]>('/api/statistics/players')).data,
  getRecentMatchesForPlayer: async (playerId: number, limit = 25) =>
    (await api.get<MatchDto[]>(`/api/matches/recent/player/${playerId}`, { params: { limit } })).data,
  getMatchupAnalysis: async (player1Id: number, player2Id: number, modelVersion?: string) =>
    (
      await api.get<MatchupAnalysisDto>('/api/analytics/matchup', {
        params: { player1Id, player2Id, modelVersion },
      })
    ).data,
  getMatchupFeatures: async (player1Id: number, player2Id: number, asOfDate?: string) =>
    (
      await api.get<MatchupFeatureVectorDto>('/api/analytics/features', {
        params: { player1Id, player2Id, asOfDate },
      })
    ).data,
  getScrapeStatus: async () => (await api.get<ScrapeStatusDto>('/api/scrape/status')).data,
  getScrapeRuns: async (params?: { status?: string; mode?: string; limit?: number }) =>
    (await api.get<ScrapeRunRecordDto[]>('/api/scrape/runs', { params })).data,
  getScrapeErrors: async (limit = 25) =>
    (await api.get<ScrapeErrorRecordDto[]>('/api/scrape/errors', { params: { limit } })).data,
  getScrapeMetrics: async (limit = 200) =>
    (await api.get<ScrapeMetricsDto>('/api/scrape/metrics', { params: { limit } })).data,
  dryRunScrapeSelectors: async (page = 1) =>
    (await api.get<DryRunPreviewDto>('/api/scrape/dry-run', { params: { page } })).data,
  runScrape: async () => (await api.post<string>('/api/scrape/run')).data,
  runScrapeRange: async (fromPage: number, toPage: number) =>
    (await api.post<string>('/api/scrape/range', null, { params: { fromPage, toPage } })).data,
  backfillMatchResults: async () =>
    (await api.post<{ updatedMatches: number }>('/api/admin/backfill/match-results')).data,
  benchmarkStats: async (iterations = 20) =>
    (
      await api.get<StatisticsBenchmarkDto>('/api/admin/benchmark/statistics', {
        params: { iterations },
      })
    ).data,
  getAliases: async (playerId?: number) =>
    (await api.get<PlayerAliasDto[]>('/api/admin/aliases', { params: { playerId } })).data,
  getRatingHistory: async (playerId: number) =>
    (await api.get<RatingSnapshotDto[]>(`/api/admin/ratings/player/${playerId}`)).data,
  rebuildGlicko2: async (fromDate?: string, toDate?: string) =>
    (
      await api.post<Glicko2RebuildDto>('/api/admin/ratings/glicko2/rebuild', null, {
        params: { fromDate, toDate },
      })
    ).data,
  tuneGlicko2Tau: async (fromDate?: string, toDate?: string, tau?: number[]) =>
    (
      await api.post<Glicko2TauTuningDto>('/api/admin/ratings/glicko2/tune-tau', null, {
        params: { fromDate, toDate, tau },
      })
    ).data,
  trainPredictionModels: async (fromDate?: string, toDate?: string) =>
    (
      await api.post<ModelTrainingReportDto>('/api/admin/models/train', null, {
        params: { fromDate, toDate },
      })
    ).data,
  getLastModelTrainingReport: async () =>
    (await api.get<ModelTrainingReportDto>('/api/admin/models/last-report')).data,
  getModelRegistry: async (family?: string, limit = 50) =>
    (await api.get<ModelRegistryEntryDto[]>('/api/analytics/models/registry', { params: { family, limit } }))
      .data,
  getAdaptiveRegimeProfiles: async () =>
    (await api.get<AdaptiveRegimeProfileDto[]>('/api/analytics/models/adaptive-regimes')).data,
  refreshOddsValueEngine: async (strategy = 'CONSERVATIVE', modelVersion?: string) =>
    (
      await api.post<OddsRefreshResultDto>('/api/admin/odds/refresh', null, {
        params: { strategy, modelVersion },
      })
    ).data,
  getValueOpportunities: async (strategy = 'CONSERVATIVE', limit = 30) =>
    (
      await api.get<ValueOpportunityDto[]>('/api/analytics/value-opportunities', {
        params: { strategy, limit },
      })
    ).data,
  getLiveStudioBoard: async (
    strategy = 'CONSERVATIVE',
    modelVersion?: string,
    limit = 40,
    includeUnresolved = false
  ) =>
    (
      await api.get<LiveOddsRecommendationDto[]>('/api/live-studio/board', {
        params: { strategy, modelVersion, limit, includeUnresolved },
      })
    ).data,
  getLiveStudioSession: async () => (await api.get<PaperTradingSessionDto>('/api/live-studio/session')).data,
  getLiveStudioOpenBets: async () => (await api.get<PaperTradeBetDto[]>('/api/live-studio/open-bets')).data,
  getLiveStudioSettledTape: async (limit = 40) =>
    (await api.get<PaperTradeBetDto[]>('/api/live-studio/settled-tape', { params: { limit } })).data,
  getLiveStudioCompletedMatches: async (days = 3, limit = 120) =>
    (await api.get<CompletedMatchLogDto[]>('/api/live-studio/completed-matches', { params: { days, limit } }))
      .data,
  getLiveStudioIntegrity: async () =>
    (await api.get<LiveStudioIntegrityDto>('/api/live-studio/integrity')).data,
  getSettlementReview: async (page = 0, size = 20, suspiciousOnly = false) =>
    (
      await api.get<SettlementReviewPageDto>('/api/score-truth/settlement-review', {
        params: { page, size, suspiciousOnly },
      })
    ).data,
  getLiveStudioMatchTimeline: async (eventKey: string) =>
    (
      await api.get<TrackedMatchObservationDto[]>(
        `/api/live-studio/match/${encodeURIComponent(eventKey)}/timeline`
      )
    ).data,
  syncLiveStudio: async (strategy = 'CONSERVATIVE', modelVersion?: string, limit = 80) =>
    (
      await api.post<PaperTradingSyncResultDto>('/api/live-studio/sync', null, {
        params: { strategy, modelVersion, limit },
      })
    ).data,
  resetLiveStudio: async (startingBankroll?: number, label?: string, clearHistory = true) =>
    (
      await api.post<PaperTradingSessionDto>('/api/live-studio/reset', null, {
        params: { startingBankroll, label, clearHistory },
      })
    ).data,
  getPotentialDuplicates: async (minSimilarity = 0.82, limit = 50) =>
    (
      await api.get<DuplicatePlayerCandidateDto[]>('/api/admin/players/potential-duplicates', {
        params: { minSimilarity, limit },
      })
    ).data,
}
