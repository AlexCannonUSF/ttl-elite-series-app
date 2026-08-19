package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.dto.PaperTradeBetDto;
import com.ttl.tabletennis.dto.PaperTradingSessionDto;
import com.ttl.tabletennis.repository.PaperTradeBetRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Function;

import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.EPS;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.clamp;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.round2;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.round4;

/**
 * Snapshot orchestrator — takes a {@link PaperTradeSession}, loads the
 * relevant bet windows from the repository, calls the five already-extracted
 * sub-builders, and assembles the {@link PaperTradingSessionDto}.
 *
 * <p>Twelfth §4 slice — completes the meaty part of the SessionService row.
 * The orchestrator is itself just glue; the math lives in the sub-builders
 * ({@link TriggerInsightsBuilder}, {@link EquityCurveBuilder},
 * {@link ClvMetricsBuilder}, {@link ExposureMetricsBuilder},
 * {@link DecisionTelemetryBuilder}). Two callers in {@code PaperTradingService}
 * delegate here today: {@code getSessionSnapshot()} (via
 * {@link SessionLifecycleService#getOrCreateActiveSession()}) and
 * {@code resetSession(...)}.
 *
 * <p>The {@code trackingStateResolver} lambda mirrors the pattern from
 * {@link IntegrityService}: the production resolver lives at
 * {@code PaperTradingService.deriveTrackingState} and transitively depends
 * on identity-drift bookkeeping the snapshot service has no business
 * touching. Passing it in keeps this slice behaviour-neutral.
 *
 * <p>The {@code exposureCaps} parameter is the same {@link ExposureMetricsBuilder.ExposureCaps}
 * the caller already assembles from its {@code @Value}-injected staking
 * properties — no new config injection here.
 */
@Service
public class SessionSnapshotService {

    private final PaperTradeBetRepository betRepository;
    private final DecisionTelemetryBuilder decisionTelemetryBuilder;
    private final ClvMetricsBuilder clvMetricsBuilder;

    public SessionSnapshotService(PaperTradeBetRepository betRepository,
                                  DecisionTelemetryBuilder decisionTelemetryBuilder,
                                  ClvMetricsBuilder clvMetricsBuilder) {
        this.betRepository = betRepository;
        this.decisionTelemetryBuilder = decisionTelemetryBuilder;
        this.clvMetricsBuilder = clvMetricsBuilder;
    }

    @Transactional(readOnly = true)
    public PaperTradingSessionDto buildSessionDto(PaperTradeSession session,
                                                  int openLimit,
                                                  int recentLimit,
                                                  ExposureMetricsBuilder.ExposureCaps exposureCaps,
                                                  Function<PaperTradeBet, String> trackingStateResolver) {
        int openTake = clamp(openLimit, 5, 100);
        int recentTake = clamp(recentLimit, 10, 200);

        List<PaperTradeBet> allOpenRows = betRepository.findBySessionIdAndStatusOrderByPlacedAtDesc(
                session.getId(),
                PaperTradeBet.STATUS_OPEN
        );
        List<PaperTradeBet> openRows = allOpenRows;
        if (openRows.size() > openTake) {
            openRows = openRows.subList(0, openTake);
        }

        List<PaperTradeBet> recentRows = betRepository.findBySessionIdOrderByPlacedAtDesc(
                session.getId(),
                PageRequest.of(0, recentTake)
        );

        List<PaperTradeBet> settledRows = betRepository.findBySessionIdAndStatusInOrderBySettledAtAsc(
                session.getId(),
                List.of(PaperTradeBet.STATUS_WON, PaperTradeBet.STATUS_LOST, PaperTradeBet.STATUS_PUSHED, PaperTradeBet.STATUS_VOIDED)
        );
        PaperTradingSessionDto.DecisionTelemetryDto decisionTelemetry =
                decisionTelemetryBuilder.buildDecisionTelemetry(session.getId());

        List<PaperTradeBetDto> openDtos = openRows.stream()
                .map(bet -> BetDtoMapper.toDto(bet, trackingStateResolver.apply(bet)))
                .toList();
        List<PaperTradeBetDto> recentDtos = recentRows.stream()
                .map(bet -> BetDtoMapper.toDto(bet, trackingStateResolver.apply(bet)))
                .toList();
        List<PaperTradingSessionDto.TriggerInsightDto> triggerInsights =
                TriggerInsightsBuilder.buildTopTriggers(settledRows);
        PaperTradingSessionDto.ExposureMetricsDto exposureMetrics =
                ExposureMetricsBuilder.buildExposureMetrics(session, allOpenRows, exposureCaps);
        PaperTradingSessionDto.ClvMetricsDto clvMetrics = clvMetricsBuilder.buildClvMetrics(recentRows);

        long openCount = betRepository.countBySessionIdAndStatus(session.getId(), PaperTradeBet.STATUS_OPEN);
        long voidedCount = betRepository.countBySessionIdAndStatus(session.getId(), PaperTradeBet.STATUS_VOIDED);
        double roiPct = session.getTotalStaked() <= EPS
                ? 0.0
                : (session.getRealizedPnl() / session.getTotalStaked()) * 100.0;
        int settledDecisions = session.getWins() + session.getLosses();
        double settledWinRate = settledDecisions == 0
                ? 0.0
                : session.getWins() / (double) settledDecisions;

        return new PaperTradingSessionDto(
                session.getId(),
                session.getLabel(),
                session.getStatus(),
                round2(session.getStartingBankroll()),
                round2(session.getCurrentBankroll()),
                round2(session.getPeakBankroll()),
                round2(session.getRealizedPnl()),
                round2(roiPct),
                round2(session.getTotalStaked()),
                round2(session.getTotalReturned()),
                session.getTotalBets(),
                (int) openCount,
                session.getWins(),
                session.getLosses(),
                session.getPushes(),
                (int) voidedCount,
                session.getSimulationRowsScanned(),
                session.getSimulationBetsPlaced(),
                session.getSimulationBetsSettled(),
                session.getSimulationBetsVoided(),
                settledWinRate,
                session.getCreatedAt(),
                session.getUpdatedAt(),
                session.getLastSyncAt(),
                new PaperTradingSessionDto.AdaptiveMetricsDto(
                        session.getAdaptiveSampleSize(),
                        round4(session.getAdaptiveEdgeShift() * 100.0),
                        round4(session.getAdaptiveSelectionScoreShift()),
                        round4(session.getAdaptiveStakeMultiplier()),
                        round4(session.getAdaptiveCalibrationError() * 100.0),
                        round4(session.getAdaptiveRoiSignal() * 100.0),
                        session.getAdaptiveUpdatedAt()
                ),
                decisionTelemetry,
                exposureMetrics,
                clvMetrics,
                openDtos,
                recentDtos,
                triggerInsights,
                EquityCurveBuilder.buildEquityCurve(session, settledRows)
        );
    }
}
