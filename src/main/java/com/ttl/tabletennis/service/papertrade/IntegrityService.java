package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.domain.TrackedMatchObservation;
import com.ttl.tabletennis.dto.LiveStudioIntegrityDto;
import com.ttl.tabletennis.dto.PaperTradeBetDto;
import com.ttl.tabletennis.repository.PaperTradeBetRepository;
import com.ttl.tabletennis.repository.TrackedMatchObservationRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.clamp;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.safeText;

/**
 * Read-only observability for the operator console: settlement-source
 * classification, tracked-observation counts, and the integrity DTO that
 * powers {@code /v3/ops/integrity}.
 *
 * <p>Seventh §4 slice and the start of the {@code IntegrityService} extract
 * called out in the plan. This first slice carries
 * {@link #getLiveStudioIntegrity(PaperTradeSession)} + its two single-use
 * helpers ({@code matchesSettlementSource}, {@code isTargetedCompletionSettlement}).
 * The remaining {@code getLiveStudioOpenBets} / {@code getLiveStudioSettledTape}
 * methods stay in {@code PaperTradingService} for now because they use
 * {@code toDto}, which transitively depends on the tracked-observation
 * lookup chain that still lives there.
 *
 * <p>The settlement-source string constants are intentionally re-declared
 * here as package-private statics. {@code String} literals get inlined at
 * compile time, so the duplication is cohesion-free at runtime; promoting
 * them to a shared {@code PaperTradingConstants} class can come later if
 * we want a single source of truth.
 */
@Service
public class IntegrityService {

    static final String OBSERVATION_SOURCE_MARKET_BOARD = "MARKET_BOARD";
    static final String OBSERVATION_SOURCE_SCORE_FEED = "SCORE_FEED";
    static final String SETTLEMENT_SOURCE_DECISIVE_LIVE_SCORE = "DECISIVE_LIVE_SCORE";
    static final String SETTLEMENT_SOURCE_OFFICIAL_RESULT = "OFFICIAL_RESULT";
    static final String SETTLEMENT_SOURCE_DATABASE_RESULT = "DATABASE_RESULT";
    static final String SETTLEMENT_SOURCE_HEURISTIC_FALLBACK = "HEURISTIC_FALLBACK";
    static final String SETTLEMENT_SOURCE_TIMEOUT_VOID = "TIMEOUT_VOID";

    /** Cap on the tracked-after-close scan so a long-running session doesn't pull a giant page. */
    private static final int TRACKED_AFTER_CLOSE_SCAN_LIMIT = 5000;

    private final PaperTradeBetRepository betRepository;
    private final TrackedMatchObservationRepository trackedMatchObservationRepository;

    public IntegrityService(PaperTradeBetRepository betRepository,
                            TrackedMatchObservationRepository trackedMatchObservationRepository) {
        this.betRepository = betRepository;
        this.trackedMatchObservationRepository = trackedMatchObservationRepository;
    }

    /**
     * Open-bet rows for the live-studio "open tape" panel.
     *
     * <p>The {@code trackingStateResolver} is injected because the production
     * resolver ({@code PaperTradingService.deriveTrackingState}) transitively
     * depends on identity-drift bookkeeping used in 7+ unrelated call paths.
     * Keeping the resolver at the caller keeps this slice behaviour-neutral.
     */
    @Transactional(readOnly = true)
    public List<PaperTradeBetDto> getLiveStudioOpenBets(PaperTradeSession session,
                                                        Function<PaperTradeBet, String> trackingStateResolver) {
        if (session == null || session.getId() == null) {
            return List.of();
        }
        return betRepository.findBySessionIdAndStatusOrderByPlacedAtDesc(session.getId(), PaperTradeBet.STATUS_OPEN)
                .stream()
                .map(bet -> BetDtoMapper.toDto(bet, trackingStateResolver.apply(bet)))
                .toList();
    }

    /**
     * Settled-bet rows for the live-studio "settled tape" panel.
     *
     * <p>{@code limit} mirrors the legacy {@code clamp(limit, 5, 200)} guard
     * exactly so paging behaviour stays identical.
     */
    @Transactional(readOnly = true)
    public List<PaperTradeBetDto> getLiveStudioSettledTape(PaperTradeSession session,
                                                            int limit,
                                                            Function<PaperTradeBet, String> trackingStateResolver) {
        if (session == null || session.getId() == null) {
            return List.of();
        }
        int take = clamp(limit, 5, 200);
        return betRepository.findBySessionIdAndStatusInOrderByPlacedAtDesc(
                        session.getId(),
                        List.of(
                                PaperTradeBet.STATUS_WON,
                                PaperTradeBet.STATUS_LOST,
                                PaperTradeBet.STATUS_PUSHED,
                                PaperTradeBet.STATUS_VOIDED
                        ),
                        PageRequest.of(0, take)
                )
                .stream()
                .map(bet -> BetDtoMapper.toDto(bet, trackingStateResolver.apply(bet)))
                .toList();
    }

    @Transactional(readOnly = true)
    public LiveStudioIntegrityDto getLiveStudioIntegrity(PaperTradeSession session) {
        if (session == null || session.getId() == null) {
            return emptyIntegrity();
        }
        Long sessionId = session.getId();

        long trackedObservations = trackedMatchObservationRepository.countBySessionIdAndSourceKind(
                sessionId,
                OBSERVATION_SOURCE_MARKET_BOARD
        ) + trackedMatchObservationRepository.countBySessionIdAndSourceKind(
                sessionId,
                OBSERVATION_SOURCE_SCORE_FEED
        );
        long boardObservations = trackedMatchObservationRepository.countBySessionIdAndSourceKind(
                sessionId,
                OBSERVATION_SOURCE_MARKET_BOARD
        );
        long scoreFeedObservations = trackedMatchObservationRepository.countBySessionIdAndSourceKind(
                sessionId,
                OBSERVATION_SOURCE_SCORE_FEED
        );
        long trackedAfterCloseObservations = trackedMatchObservationRepository.findBySessionIdOrderByObservedAtDesc(
                        sessionId,
                        PageRequest.of(0, TRACKED_AFTER_CLOSE_SCAN_LIMIT)
                ).stream()
                .filter(TrackedMatchObservation::isTrackedAfterClose)
                .count();

        List<PaperTradeBet> settledBets = betRepository.findBySessionIdAndStatusInOrderBySettledAtAsc(
                sessionId,
                List.of(
                        PaperTradeBet.STATUS_WON,
                        PaperTradeBet.STATUS_LOST,
                        PaperTradeBet.STATUS_PUSHED,
                        PaperTradeBet.STATUS_VOIDED
                )
        );
        long scoreBacked = settledBets.stream()
                .filter(IntegrityService::isScoreBackedSettlement)
                .filter(bet -> !isTargetedCompletionSettlement(bet))
                .count();
        long targetedCompletion = settledBets.stream()
                .filter(IntegrityService::isTargetedCompletionSettlement)
                .count();
        long officialResult = settledBets.stream()
                .filter(bet -> matchesSettlementSource(bet, SETTLEMENT_SOURCE_OFFICIAL_RESULT))
                .count();
        long databaseResult = settledBets.stream()
                .filter(bet -> matchesSettlementSource(bet, SETTLEMENT_SOURCE_DATABASE_RESULT))
                .count();
        long heuristic = settledBets.stream()
                .filter(bet -> matchesSettlementSource(bet, SETTLEMENT_SOURCE_HEURISTIC_FALLBACK))
                .count();
        long voided = settledBets.stream()
                .filter(bet -> matchesSettlementSource(bet, SETTLEMENT_SOURCE_TIMEOUT_VOID)
                        || PaperTradeBet.STATUS_VOIDED.equalsIgnoreCase(safeText(bet.getStatus(), "")))
                .count();

        return new LiveStudioIntegrityDto(
                trackedObservations,
                boardObservations,
                scoreFeedObservations,
                trackedAfterCloseObservations,
                scoreBacked,
                targetedCompletion,
                officialResult,
                databaseResult,
                heuristic,
                voided
        );
    }

    static boolean matchesSettlementSource(PaperTradeBet bet, String expectedSource) {
        if (bet == null || !StringUtils.hasText(expectedSource)) {
            return false;
        }
        String expected = expectedSource.trim().toUpperCase(Locale.ROOT);
        String observed = (safeText(bet.getSettlementSource(), "") + " "
                + safeText(bet.getSettlementReason(), "")).toUpperCase(Locale.ROOT);
        if (expected.equals(safeText(bet.getSettlementSource(), "").toUpperCase(Locale.ROOT))) {
            return true;
        }
        return switch (expected) {
            case SETTLEMENT_SOURCE_DECISIVE_LIVE_SCORE ->
                    observed.contains("DECISIVE_LIVE_SCORE")
                            || observed.contains("FINISHED_LIVE_SCORE")
                            || observed.contains("SCORE_BACKED")
                            || observed.contains("TARGETED_MATCH_COMPLETED")
                            || observed.contains("TARGETED_COMPLETION_SIGNAL")
                            || observed.contains("STREAM_CV");
            case SETTLEMENT_SOURCE_OFFICIAL_RESULT -> observed.contains("OFFICIAL");
            case SETTLEMENT_SOURCE_DATABASE_RESULT -> observed.contains("DATABASE");
            case SETTLEMENT_SOURCE_HEURISTIC_FALLBACK ->
                    observed.contains("HEURISTIC")
                            || observed.contains("LAST_SCORE")
                            || observed.contains("NEAR_FINISH")
                            || observed.contains("STALE_ONBOARD");
            case SETTLEMENT_SOURCE_TIMEOUT_VOID -> observed.contains("VOID");
            default -> false;
        };
    }

    static boolean isTargetedCompletionSettlement(PaperTradeBet bet) {
        if (bet == null) {
            return false;
        }
        String observed = (safeText(bet.getSettlementSource(), "") + " "
                + safeText(bet.getSettlementReason(), "")).toUpperCase(Locale.ROOT);
        return observed.contains("TARGETED_MATCH_COMPLETED")
                || observed.contains("TARGETED_COMPLETION_SIGNAL");
    }

    static boolean isScoreBackedSettlement(PaperTradeBet bet) {
        return matchesSettlementSource(bet, SETTLEMENT_SOURCE_DECISIVE_LIVE_SCORE)
                && !matchesSettlementSource(bet, SETTLEMENT_SOURCE_HEURISTIC_FALLBACK);
    }

    private static LiveStudioIntegrityDto emptyIntegrity() {
        return new LiveStudioIntegrityDto(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }
}
