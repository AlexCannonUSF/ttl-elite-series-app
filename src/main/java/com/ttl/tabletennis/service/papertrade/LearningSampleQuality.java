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
        } else if (source.contains("DECISIVE_LIVE_SCORE")
                || reason.contains("FINISHED_LIVE_SCORE")
                || reason.contains("TARGETED_MATCH_COMPLETED")) {
            confidence = Math.max(0.88, finiteOrZero(bet.getLastScoreConfidence()));
        } else if (source.contains("HEURISTIC")
                || reason.contains("LAST_SCORE")
                || reason.contains("NEAR_FINISH")
                || reason.contains("STALE_ONBOARD")) {
            confidence = Math.min(0.70, Math.max(0.45, finiteOrZero(bet.getLastScoreConfidence())));
        } else {
            confidence = 0.35;
        }

        boolean eligible = resolved && validWinner && validSide && confidence >= 0.90;
        String exclusion = eligible ? null
                : !resolved ? "NON_BINARY_OUTCOME"
                : !validWinner ? "INVALID_WINNER_IDENTITY"
                : !validSide ? "INVALID_SIDE_IDENTITY"
                : "LOW_CONFIDENCE_SETTLEMENT";
        return new Assessment(round4(confidence), eligible, exclusion);
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

    private static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    public record Assessment(double confidence, boolean calibrationEligible, String exclusionReason) {
    }
}
