package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeBet;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Objects;

/**
 * Central label-provenance policy for model learning.
 *
 * <p>Only outcomes backed by sufficiently trustworthy evidence may alter
 * calibration or adaptive thresholds. Heuristic score guesses remain useful
 * telemetry, but never silently become training truth.
 */
public final class LearningSampleQuality {

    /**
     * Archive ambiguity enters the scorer's REQUIRES_STRONG_EVIDENCE band at
     * 0.30. Such a result may still be safe enough to close a paper position,
     * but it is not clean enough to become a learning label.
     */
    static final double MAX_LEARNING_AMBIGUITY = 0.30;

    private LearningSampleQuality() {
    }

    public static Assessment assess(PaperTradeBet bet) {
        if (bet == null) {
            return new Assessment(0.0, false, "MISSING_BET");
        }
        String status = upper(bet.getStatus());
        boolean resolved = PaperTradeBet.STATUS_WON.equals(status) || PaperTradeBet.STATUS_LOST.equals(status);
        boolean validWinner = bet.getWinnerPlayerId() != null
                && (Objects.equals(bet.getWinnerPlayerId(), bet.getPlayer1Id())
                || Objects.equals(bet.getWinnerPlayerId(), bet.getPlayer2Id()));
        boolean validSide = bet.getSidePlayerId() != null
                && (Objects.equals(bet.getSidePlayerId(), bet.getPlayer1Id())
                || Objects.equals(bet.getSidePlayerId(), bet.getPlayer2Id()));

        String source = upper(bet.getSettlementSource());
        String reason = upper(bet.getSettlementReason());
        double confidence;
        if (source.contains("OFFICIAL") || reason.contains("OFFICIAL")) {
            confidence = 1.0;
        } else if (source.contains("DATABASE") || reason.contains("DATABASE")) {
            confidence = bet.getResultMatchId() == null ? 0.82 : 0.96;
        } else if (source.contains("TARGETED_MATCH_COMPLETED")
                || reason.contains("TARGETED_MATCH_COMPLETED")
                || reason.contains("TARGETED_COMPLETION_SIGNAL")) {
            confidence = scoreLabelConfidence(bet, true);
        } else if (source.contains("HEURISTIC")
                || reason.contains("LAST_SCORE")
                || reason.contains("NEAR_FINISH")
                || reason.contains("STALE_ONBOARD")) {
            confidence = Math.min(0.70, Math.max(0.45, finiteOrZero(bet.getLastScoreConfidence())));
        } else if (source.contains("DECISIVE_LIVE_SCORE")
                || source.contains("SCORE_BACKED")
                || source.contains("STREAM_CV")
                || reason.contains("FINISHED_LIVE_SCORE")
                || reason.contains("DECISIVE_LIVE_SCORE")
                || reason.contains("SCORE_BACKED")
                || reason.contains("STREAM_CV")) {
            confidence = scoreLabelConfidence(bet, false);
        } else {
            confidence = 0.35;
        }
        if (bet.getSettlementConfidence() != null && Double.isFinite(bet.getSettlementConfidence())) {
            confidence = Math.min(confidence, clamp01(bet.getSettlementConfidence()));
        }

        boolean archiveSettlement = source.contains("OFFICIAL")
                || source.contains("DATABASE")
                || source.contains("ARCHIVE")
                || reason.contains("OFFICIAL")
                || reason.contains("DATABASE")
                || reason.contains("ARCHIVE");
        boolean archiveIdentityVerified = (bet.getSettlementAmbiguityScore() != null
                && Double.isFinite(bet.getSettlementAmbiguityScore()))
                || reason.contains("FEED_IDENTITY");
        boolean archiveAmbiguous = archiveSettlement
                && archiveIdentityVerified
                && bet.getSettlementAmbiguityScore() != null
                && bet.getSettlementAmbiguityScore() >= MAX_LEARNING_AMBIGUITY;
        boolean evidenceWinnerConflict = bet.getScoreEvidenceInferredWinnerId() != null
                && validWinner
                && !Objects.equals(bet.getScoreEvidenceInferredWinnerId(), bet.getWinnerPlayerId());

        String exclusion = !resolved ? "NON_BINARY_OUTCOME"
                : !validWinner ? "INVALID_WINNER_IDENTITY"
                : !validSide ? "INVALID_SIDE_IDENTITY"
                : archiveSettlement && !archiveIdentityVerified ? "UNVERIFIED_ARCHIVE_SETTLEMENT"
                : archiveAmbiguous ? "AMBIGUOUS_ARCHIVE_SETTLEMENT"
                : bet.isScoreEvidenceContradictory() ? "CONTRADICTORY_SCORE_EVIDENCE"
                : evidenceWinnerConflict ? "EVIDENCE_WINNER_CONFLICT"
                : confidence < 0.90 ? "LOW_CONFIDENCE_SETTLEMENT"
                : null;
        boolean eligible = exclusion == null;
        return new Assessment(round4(confidence), eligible, exclusion);
    }

    /**
     * Score evidence can be strong enough to close a position before it is
     * strong enough to become a model label. Calibration requires two
     * agreeing sources (or the equivalent legacy evidence count), a
     * decision-grade score assessment, and no recorded contradiction.
     */
    private static double scoreLabelConfidence(PaperTradeBet bet, boolean targeted) {
        int supportingSources = Math.max(
                integerOrZero(bet.getScoreEvidenceAgreeingSources()),
                integerOrZero(bet.getSettlementEvidenceSourceCount())
        );
        boolean independentlySupported = supportingSources >= 2;
        boolean decisionGrade = "DECISION_GRADE".equals(upper(bet.getScoreEvidenceQuality()))
                || bet.getScoreEvidenceQuality() == null;
        boolean trustedForLearning = independentlySupported
                && decisionGrade
                && !bet.isScoreEvidenceContradictory();
        double observedConfidence = Math.max(
                finiteOrZero(bet.getLastScoreConfidence()),
                finiteOrZero(bet.getScoreEvidenceConfidence())
        );
        if (trustedForLearning) {
            return Math.max(0.90, observedConfidence);
        }
        double floor = targeted ? 0.88 : 0.82;
        return Math.min(0.89, Math.max(floor, observedConfidence));
    }

    public static String priceRegime(double impliedProbability) {
        if (impliedProbability >= 0.55) {
            return "FAVORITE";
        }
        if (impliedProbability <= 0.45) {
            return "UNDERDOG";
        }
        return "BALANCED";
    }

    private static String upper(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }

    private static double finiteOrZero(Double value) {
        return value != null && Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : 0.0;
    }

    private static int integerOrZero(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    public record Assessment(double confidence, boolean learningEligible, String exclusionReason) {

        /** Compatibility alias for older callers while learning eligibility
         * becomes the single persisted contract. */
        public boolean calibrationEligible() {
            return learningEligible;
        }
    }
}
