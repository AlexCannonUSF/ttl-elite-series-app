package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.SettlementDiffLog;
import com.ttl.tabletennis.repository.SettlementDiffLogRepository;
import com.ttl.tabletennis.settlement.BetSettlementPolicyCatalog;
import com.ttl.tabletennis.settlement.Contradiction;
import com.ttl.tabletennis.settlement.ContradictionGuard;
import com.ttl.tabletennis.settlement.Decision;
import com.ttl.tabletennis.settlement.Escalate;
import com.ttl.tabletennis.settlement.HoldOpen;
import com.ttl.tabletennis.settlement.ManualReview;
import com.ttl.tabletennis.settlement.Settle;
import com.ttl.tabletennis.settlement.SettlementEvidence;
import com.ttl.tabletennis.settlement.SettlementEvidenceBuilder;
import com.ttl.tabletennis.settlement.SettlementEngine;
import com.ttl.tabletennis.settlement.SettlementPolicy;
import com.ttl.tabletennis.settlement.VoidDecision;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class SettlementDiffLogService {

    static final String STATUS_SETTLE = "SETTLE";
    static final String STATUS_VOID = "VOID";
    static final String STATUS_HOLD_OPEN = "HOLD_OPEN";
    static final String STATUS_ESCALATE = "ESCALATE";
    static final String STATUS_MANUAL_REVIEW = "MANUAL_REVIEW";
    static final String SHADOW_SKIPPED_NO_EVIDENCE = "SHADOW_SKIPPED_NO_EVIDENCE";

    private final SettlementDiffLogRepository settlementDiffLogRepository;
    private final SettlementEvidenceBuilder settlementEvidenceBuilder;
    private final ContradictionGuard contradictionGuard;
    private final SettlementEngine settlementEngine;
    private final SettlementShadowAuditService settlementShadowAuditService;
    private final BetSettlementPolicyCatalog betSettlementPolicyCatalog;

    @Autowired
    public SettlementDiffLogService(SettlementDiffLogRepository settlementDiffLogRepository,
                                    SettlementEvidenceBuilder settlementEvidenceBuilder,
                                    ContradictionGuard contradictionGuard,
                                    SettlementEngine settlementEngine,
                                    SettlementShadowAuditService settlementShadowAuditService,
                                    BetSettlementPolicyCatalog betSettlementPolicyCatalog) {
        this.settlementDiffLogRepository = settlementDiffLogRepository;
        this.settlementEvidenceBuilder = settlementEvidenceBuilder;
        this.contradictionGuard = contradictionGuard;
        this.settlementEngine = settlementEngine;
        this.settlementShadowAuditService = settlementShadowAuditService;
        this.betSettlementPolicyCatalog = betSettlementPolicyCatalog;
    }

    SettlementDiffLogService(SettlementDiffLogRepository settlementDiffLogRepository,
                             SettlementEvidenceBuilder settlementEvidenceBuilder,
                             ContradictionGuard contradictionGuard,
                             SettlementEngine settlementEngine,
                             SettlementShadowAuditService settlementShadowAuditService) {
        this(
                settlementDiffLogRepository,
                settlementEvidenceBuilder,
                contradictionGuard,
                settlementEngine,
                settlementShadowAuditService,
                null
        );
    }

    public int recordIdentityReplay(List<PaperTradeBet> trackedOpenBets) {
        if (trackedOpenBets == null || trackedOpenBets.isEmpty()) {
            return 0;
        }

        List<SettlementDiffLog> rows = new ArrayList<>();
        for (PaperTradeBet bet : trackedOpenBets) {
            if (!isTrackedAttempt(bet)) {
                continue;
            }
            rows.add(toIdentityAgreeRow(bet));
        }

        if (rows.isEmpty()) {
            return 0;
        }

        return saveNewRows(rows);
    }

    public int recordScoreTruthReplay(List<PaperTradeBet> trackedOpenBets) {
        return recordScoreTruthReplay(trackedOpenBets, true);
    }

    public int recordScoreTruthReplay(List<PaperTradeBet> trackedOpenBets, boolean recordAudit) {
        if (trackedOpenBets == null || trackedOpenBets.isEmpty()
                || settlementEvidenceBuilder == null
                || settlementEngine == null) {
            return 0;
        }

        List<SettlementDiffLog> rows = new ArrayList<>();
        for (PaperTradeBet bet : trackedOpenBets) {
            if (!isTrackedAttempt(bet)) {
                continue;
            }
            Optional<SettlementEvidence> evidence = settlementEvidenceBuilder.buildForBet(bet);
            if (evidence.isEmpty()) {
                if (recordAudit && settlementShadowAuditService != null) {
                    settlementShadowAuditService.recordNoEvidenceAttempt(bet, SHADOW_SKIPPED_NO_EVIDENCE);
                }
                rows.add(toScoreTruthRow(bet, SettlementDecisionSnapshot.shadowSkipped(bet)));
                continue;
            }
            Decision shadowDecision = settlementEngine.decide(evidence.get(), currentPolicy());
            if (recordAudit && settlementShadowAuditService != null) {
                settlementShadowAuditService.recordAttempt(bet, evidence.get(), shadowDecision);
            }
            rows.add(toScoreTruthRow(bet, shadowDecision));
        }

        if (rows.isEmpty()) {
            return 0;
        }

        return saveNewRows(rows);
    }

    private SettlementDiffLog toIdentityAgreeRow(PaperTradeBet bet) {
        SettlementDecisionSnapshot legacyDecision = SettlementDecisionSnapshot.fromBet(bet);
        SettlementDecisionSnapshot shadowDecision = legacyDecision;

        SettlementDiffLog row = new SettlementDiffLog();
        row.setBetId(bet.getId());
        row.setOldReason(legacyDecision.reason());
        row.setNewReason(shadowDecision.reason());
        row.setOldWinner(legacyDecision.winnerPlayerId());
        row.setNewWinner(shadowDecision.winnerPlayerId());
        row.setDiffKind(diffKind(legacyDecision, shadowDecision));
        row.setDecidedAt(legacyDecision.decidedAt() == null ? LocalDateTime.now() : legacyDecision.decidedAt());
        attachFingerprint(row);
        return row;
    }

    private SettlementDiffLog toScoreTruthRow(PaperTradeBet bet,
                                              Decision shadowDecision) {
        SettlementDecisionSnapshot legacyDecision = SettlementDecisionSnapshot.fromBet(bet);
        SettlementDecisionSnapshot scoreTruthDecision = SettlementDecisionSnapshot.fromDecision(shadowDecision);
        return toScoreTruthRow(bet, legacyDecision, scoreTruthDecision, scoreTruthDiffKind(legacyDecision, scoreTruthDecision, shadowDecision));
    }

    private SettlementDiffLog toScoreTruthRow(PaperTradeBet bet,
                                              SettlementDecisionSnapshot scoreTruthDecision) {
        SettlementDecisionSnapshot legacyDecision = SettlementDecisionSnapshot.fromBet(bet);
        return toScoreTruthRow(bet, legacyDecision, scoreTruthDecision, SettlementDiffLog.DIFF_KIND_OUTCOME_DIFF);
    }

    private SettlementDiffLog toScoreTruthRow(PaperTradeBet bet,
                                              SettlementDecisionSnapshot legacyDecision,
                                              SettlementDecisionSnapshot scoreTruthDecision,
                                              String diffKind) {
        SettlementDiffLog row = new SettlementDiffLog();
        row.setBetId(bet.getId());
        row.setOldReason(legacyDecision.reason());
        row.setNewReason(scoreTruthDecision.reason());
        row.setOldWinner(legacyDecision.winnerPlayerId());
        row.setNewWinner(scoreTruthDecision.winnerPlayerId());
        row.setDiffKind(diffKind);
        row.setDecidedAt(scoreTruthDecision.decidedAt() == null
                ? (legacyDecision.decidedAt() == null ? LocalDateTime.now() : legacyDecision.decidedAt())
                : scoreTruthDecision.decidedAt());
        attachFingerprint(row);
        return row;
    }

    private int saveNewRows(List<SettlementDiffLog> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        Set<String> seenThisBatch = new HashSet<>();
        List<SettlementDiffLog> newRows = candidates.stream()
                .filter(Objects::nonNull)
                .filter(row -> StringUtils.hasText(row.getDiffFingerprint()))
                .filter(row -> seenThisBatch.add(row.getDiffFingerprint()))
                .filter(row -> !settlementDiffLogRepository.existsByDiffFingerprint(row.getDiffFingerprint()))
                .toList();
        if (newRows.isEmpty()) {
            return 0;
        }
        settlementDiffLogRepository.saveAll(newRows);
        return newRows.size();
    }

    private void attachFingerprint(SettlementDiffLog row) {
        row.setDiffFingerprint(SettlementFingerprint.diff(
                row.getBetId(),
                row.getOldReason(),
                row.getNewReason(),
                row.getOldWinner(),
                row.getNewWinner(),
                row.getDiffKind()
        ));
    }

    private String scoreTruthDiffKind(SettlementDecisionSnapshot legacyDecision,
                                      SettlementDecisionSnapshot shadowDecision,
                                      Decision shadowDecisionValue) {
        if (shadowDecisionValue instanceof ManualReview manualReview && !manualReview.contradictions().isEmpty()) {
            return SettlementDiffLog.DIFF_KIND_CONTRADICTION;
        }
        if (!Objects.equals(legacyDecision.status(), shadowDecision.status())
                || !Objects.equals(legacyDecision.winnerPlayerId(), shadowDecision.winnerPlayerId())) {
            return SettlementDiffLog.DIFF_KIND_OUTCOME_DIFF;
        }
        if (STATUS_HOLD_OPEN.equals(legacyDecision.status()) && STATUS_HOLD_OPEN.equals(shadowDecision.status())) {
            return SettlementDiffLog.DIFF_KIND_AGREE;
        }
        if (!Objects.equals(legacyDecision.reason(), shadowDecision.reason())) {
            return SettlementDiffLog.DIFF_KIND_OUTCOME_DIFF;
        }
        return SettlementDiffLog.DIFF_KIND_AGREE;
    }

    private SettlementPolicy currentPolicy() {
        return betSettlementPolicyCatalog == null ? SettlementPolicy.defaults() : betSettlementPolicyCatalog.currentPolicy();
    }

    private String diffKind(SettlementDecisionSnapshot legacyDecision, SettlementDecisionSnapshot shadowDecision) {
        if (!Objects.equals(legacyDecision.status(), shadowDecision.status())
                || !Objects.equals(legacyDecision.winnerPlayerId(), shadowDecision.winnerPlayerId())
                || !Objects.equals(legacyDecision.reason(), shadowDecision.reason())) {
            return SettlementDiffLog.DIFF_KIND_OUTCOME_DIFF;
        }
        return SettlementDiffLog.DIFF_KIND_AGREE;
    }

    private boolean isTrackedAttempt(PaperTradeBet bet) {
        return bet != null && bet.getId() != null;
    }

    private record SettlementDecisionSnapshot(String status,
                                              String reason,
                                              Long winnerPlayerId,
                                              LocalDateTime decidedAt) {

        private static SettlementDecisionSnapshot fromBet(PaperTradeBet bet) {
            String normalizedStatus = normalizeBetStatus(bet);
            return new SettlementDecisionSnapshot(
                    normalizedStatus,
                    StringUtils.hasText(bet.getSettlementReason()) ? bet.getSettlementReason() : normalizedStatus,
                    bet.getWinnerPlayerId(),
                    bet.getSettledAt()
            );
        }

        private static SettlementDecisionSnapshot fromDecision(Decision decision) {
            if (decision instanceof Settle settle) {
                return new SettlementDecisionSnapshot(
                        STATUS_SETTLE,
                        settle.reason().name(),
                        settle.winnerPlayerId() == 0L ? null : settle.winnerPlayerId(),
                        LocalDateTime.ofInstant(settle.evidence().bundleAsOf(), java.time.ZoneId.systemDefault())
                );
            }
            if (decision instanceof VoidDecision voidDecision) {
                return new SettlementDecisionSnapshot(
                        STATUS_VOID,
                        voidDecision.reason().name(),
                        null,
                        LocalDateTime.ofInstant(voidDecision.evidence().bundleAsOf(), java.time.ZoneId.systemDefault())
                );
            }
            if (decision instanceof ManualReview manualReview) {
                return new SettlementDecisionSnapshot(
                        STATUS_MANUAL_REVIEW,
                        manualReview.reason().name(),
                        null,
                        LocalDateTime.ofInstant(manualReview.evidence().bundleAsOf(), java.time.ZoneId.systemDefault())
                );
            }
            if (decision instanceof Escalate escalate) {
                return new SettlementDecisionSnapshot(
                        STATUS_ESCALATE,
                        escalate.reason().name(),
                        null,
                        LocalDateTime.ofInstant(escalate.evidence().bundleAsOf(), java.time.ZoneId.systemDefault())
                );
            }
            if (decision instanceof HoldOpen holdOpen) {
                return new SettlementDecisionSnapshot(
                        STATUS_HOLD_OPEN,
                        holdOpen.reason().name(),
                        null,
                        LocalDateTime.ofInstant(holdOpen.evidence().bundleAsOf(), java.time.ZoneId.systemDefault())
                );
            }
            throw new IllegalArgumentException("Unsupported decision type: " + decision.getClass().getName());
        }

        private static SettlementDecisionSnapshot shadowSkipped(PaperTradeBet bet) {
            return new SettlementDecisionSnapshot(
                    STATUS_MANUAL_REVIEW,
                    SHADOW_SKIPPED_NO_EVIDENCE,
                    null,
                    bet.getSettledAt() == null ? LocalDateTime.now() : bet.getSettledAt()
            );
        }

        private static String normalizeBetStatus(PaperTradeBet bet) {
            String status = bet.getStatus();
            if (!StringUtils.hasText(status)) {
                return STATUS_HOLD_OPEN;
            }
            if (PaperTradeBet.STATUS_WON.equalsIgnoreCase(status)
                    || PaperTradeBet.STATUS_LOST.equalsIgnoreCase(status)
                    || PaperTradeBet.STATUS_PUSHED.equalsIgnoreCase(status)) {
                return STATUS_SETTLE;
            }
            if (PaperTradeBet.STATUS_VOIDED.equalsIgnoreCase(status)) {
                return STATUS_VOID;
            }
            return STATUS_HOLD_OPEN;
        }
    }
}
