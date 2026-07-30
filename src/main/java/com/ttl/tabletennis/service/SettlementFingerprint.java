package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.settlement.Decision;
import com.ttl.tabletennis.settlement.Escalate;
import com.ttl.tabletennis.settlement.HoldOpen;
import com.ttl.tabletennis.settlement.ManualReview;
import com.ttl.tabletennis.settlement.Settle;
import com.ttl.tabletennis.settlement.SettlementEvidence;
import com.ttl.tabletennis.settlement.VoidDecision;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Stable semantic fingerprints for settlement persistence.
 *
 * <p>Polling timestamps and correlation ids are intentionally excluded. A new
 * row is warranted only when the evidence or resulting decision changes, not
 * merely because the same state was observed by another scheduler tick.
 */
final class SettlementFingerprint {

    private SettlementFingerprint() {
    }

    static String evidence(SettlementEvidence evidence) {
        if (evidence == null) {
            return sha256("evidence|null");
        }
        return sha256(String.join("|",
                "evidence-v1",
                String.valueOf(evidence.betId()),
                String.valueOf(evidence.trackedEventId()),
                String.valueOf(evidence.identityLock()),
                String.valueOf(evidence.liveObservations()),
                String.valueOf(evidence.mirrorObservations()),
                String.valueOf(evidence.streamObservations()),
                String.valueOf(evidence.officialCandidates()),
                String.valueOf(evidence.databaseCandidates()),
                String.valueOf(evidence.coverageState()),
                String.valueOf(evidence.contradictions()),
                stableDouble(evidence.ambiguityScore()),
                stableDouble(evidence.confidence())
        ));
    }

    static String decision(PaperTradeBet bet, String evidenceFingerprint, Decision decision) {
        return sha256(String.join("|",
                "decision-v1",
                bet == null || bet.getId() == null ? "unknown" : String.valueOf(bet.getId()),
                normalize(evidenceFingerprint),
                decisionType(decision),
                decision == null || decision.reason() == null ? "UNKNOWN" : decision.reason().name(),
                decisionDetails(decision)
        ));
    }

    static String noEvidenceDecision(PaperTradeBet bet, String reason) {
        return sha256(String.join("|",
                "no-evidence-v1",
                bet == null || bet.getId() == null ? "unknown" : String.valueOf(bet.getId()),
                normalize(reason),
                bet == null ? "" : normalize(bet.getStatus()),
                bet == null ? "" : normalize(bet.getLastObservedScore()),
                bet == null ? "" : normalize(bet.getLastObservedPhase()),
                bet == null || bet.getLastObservedAt() == null ? "" : bet.getLastObservedAt().toString(),
                bet == null ? "" : normalize(bet.getPendingEvidenceReason())
        ));
    }

    static String diff(Long betId,
                       String oldReason,
                       String newReason,
                       Long oldWinner,
                       Long newWinner,
                       String diffKind) {
        return sha256(String.join("|",
                "diff-v1",
                betId == null ? "unknown" : String.valueOf(betId),
                normalize(oldReason),
                normalize(newReason),
                oldWinner == null ? "" : String.valueOf(oldWinner),
                newWinner == null ? "" : String.valueOf(newWinner),
                normalize(diffKind)
        ));
    }

    private static String decisionType(Decision decision) {
        if (decision instanceof Settle) {
            return SettlementShadowAuditService.DECISION_SETTLE;
        }
        if (decision instanceof HoldOpen) {
            return SettlementShadowAuditService.DECISION_HOLD_OPEN;
        }
        if (decision instanceof Escalate) {
            return SettlementShadowAuditService.DECISION_ESCALATE;
        }
        if (decision instanceof VoidDecision) {
            return SettlementShadowAuditService.DECISION_VOID;
        }
        if (decision instanceof ManualReview) {
            return SettlementShadowAuditService.DECISION_MANUAL_REVIEW;
        }
        return "UNKNOWN";
    }

    private static String decisionDetails(Decision decision) {
        if (decision instanceof Settle settle) {
            return settle.winnerPlayerId() + "|" + stableDouble(settle.confidence());
        }
        if (decision instanceof Escalate escalate) {
            return String.valueOf(escalate.nextSources());
        }
        if (decision instanceof HoldOpen holdOpen) {
            return normalize(holdOpen.note());
        }
        if (decision instanceof ManualReview manualReview) {
            return String.valueOf(manualReview.contradictions());
        }
        return "";
    }

    private static String stableDouble(double value) {
        return String.format(Locale.ROOT, "%.8f", value);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    (value == null ? "" : value).getBytes(StandardCharsets.UTF_8)
            )).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required for settlement fingerprints", ex);
        }
    }
}
