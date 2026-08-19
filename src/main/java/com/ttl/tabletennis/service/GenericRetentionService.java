package com.ttl.tabletennis.service;

import com.ttl.tabletennis.repository.PaperTradeDecisionSampleRepository;
import com.ttl.tabletennis.repository.PaperTradeLearningSampleRepository;
import com.ttl.tabletennis.repository.PredictionDiffLogRepository;
import com.ttl.tabletennis.repository.ScrapeErrorRepository;
import com.ttl.tabletennis.repository.SettlementAuditRecordRepository;
import com.ttl.tabletennis.repository.SettlementDiffLogRepository;
import com.ttl.tabletennis.repository.TrackedMatchObservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * #124 — Generic retention sweep for the six high-traffic tables that were
 * growing without bound:
 * <ul>
 *   <li>{@code scrape_error} (per-fetch failures, 7414 lifetime rows at the time of this fix)</li>
 *   <li>{@code settlement_diff_log} (~19k lifetime, every shadow-vs-legacy disagreement)</li>
 *   <li>{@code settlement_audit} (multi-KB JSON per row, written every 30s per bet by
 *       stale-live-recovery + every v3 closure attempt — fastest-growing offender)</li>
 *   <li>{@code tracked_match_observation} (every paper-trade sync persists ~per-bet rows)</li>
 *   <li>{@code paper_trade_decision_sample} (every considered pick)</li>
 *   <li>{@code paper_trade_learning_sample} (every settled bet — adaptive learner only
 *       reads the last 150, anything beyond a 90-day window is dead weight)</li>
 *   <li>{@code prediction_diff_log} (Variant A vs B shadow rows)</li>
 * </ul>
 *
 * <p>Without this service the 1.2GB H2 file just kept growing — and the
 * settlement_audit payloadJson LONGTEXT was projected to dominate the
 * MVStore inside weeks. The existing
 * {@link OddsSnapshotRetentionService} was the model for this pattern.
 *
 * <p>Defaults are conservative (most tables 90 days, settlement_audit 30
 * days because its rows are largest). All thresholds are
 * {@code @Value}-driven so an operator can dial them up or down without a
 * rebuild.
 *
 * <p>Cron defaults to 03:30 daily so the prune lands AFTER the existing
 * 03:15 odds-snapshot prune — gives H2 a chance to compact between sweeps.
 */
@Service
public class GenericRetentionService {

    private static final Logger log = LoggerFactory.getLogger(GenericRetentionService.class);

    private final ScrapeErrorRepository scrapeErrorRepository;
    private final SettlementDiffLogRepository settlementDiffLogRepository;
    private final SettlementAuditRecordRepository settlementAuditRecordRepository;
    private final TrackedMatchObservationRepository trackedMatchObservationRepository;
    private final PaperTradeDecisionSampleRepository decisionSampleRepository;
    private final PaperTradeLearningSampleRepository learningSampleRepository;
    private final PredictionDiffLogRepository predictionDiffLogRepository;

    @Value("${ttl.retention.scrapeErrorDays:60}")
    private int scrapeErrorDays;

    @Value("${ttl.retention.settlementDiffDays:90}")
    private int settlementDiffDays;

    @Value("${ttl.retention.settlementAuditDays:30}")
    private int settlementAuditDays;

    @Value("${ttl.retention.trackedObservationDays:14}")
    private int trackedObservationDays;

    @Value("${ttl.retention.decisionSampleDays:60}")
    private int decisionSampleDays;

    @Value("${ttl.retention.learningSampleDays:120}")
    private int learningSampleDays;

    @Value("${ttl.retention.predictionDiffDays:60}")
    private int predictionDiffDays;

    public GenericRetentionService(ScrapeErrorRepository scrapeErrorRepository,
                                    SettlementDiffLogRepository settlementDiffLogRepository,
                                    SettlementAuditRecordRepository settlementAuditRecordRepository,
                                    TrackedMatchObservationRepository trackedMatchObservationRepository,
                                    PaperTradeDecisionSampleRepository decisionSampleRepository,
                                    PaperTradeLearningSampleRepository learningSampleRepository,
                                    PredictionDiffLogRepository predictionDiffLogRepository) {
        this.scrapeErrorRepository = scrapeErrorRepository;
        this.settlementDiffLogRepository = settlementDiffLogRepository;
        this.settlementAuditRecordRepository = settlementAuditRecordRepository;
        this.trackedMatchObservationRepository = trackedMatchObservationRepository;
        this.decisionSampleRepository = decisionSampleRepository;
        this.learningSampleRepository = learningSampleRepository;
        this.predictionDiffLogRepository = predictionDiffLogRepository;
    }

    /**
     * Nightly retention sweep. Each prune runs in its own transaction
     * (delegating to the @Transactional helper below) so a single repo
     * timeout or constraint failure doesn't cascade and block the others.
     */
    @Scheduled(cron = "${ttl.retention.cron:0 30 3 * * *}")
    public void scheduledRetentionSweep() {
        Map<String, Integer> deleted = runSweep(LocalDateTime.now());
        log.info("[retention] sweep complete: {}", deleted);
    }

    /**
     * Run all prunes. Exposed for tests / ops endpoints. Each prune is
     * isolated in its own transaction; if one fails, others still run.
     * Returns a per-table deleted-row count for logging / ops visibility.
     */
    public Map<String, Integer> runSweep(LocalDateTime now) {
        LocalDateTime safeNow = now == null ? LocalDateTime.now() : now;
        Map<String, Integer> deletedByTable = new LinkedHashMap<>();
        deletedByTable.put("scrape_error",
                safePrune("scrape_error", () -> pruneScrapeErrors(safeNow)));
        deletedByTable.put("settlement_diff_log",
                safePrune("settlement_diff_log", () -> pruneSettlementDiffLog(safeNow)));
        deletedByTable.put("settlement_audit",
                safePrune("settlement_audit", () -> pruneSettlementAudit(safeNow)));
        deletedByTable.put("tracked_match_observation",
                safePrune("tracked_match_observation", () -> pruneTrackedObservations(safeNow)));
        deletedByTable.put("paper_trade_decision_sample",
                safePrune("paper_trade_decision_sample", () -> pruneDecisionSamples(safeNow)));
        deletedByTable.put("paper_trade_learning_sample",
                safePrune("paper_trade_learning_sample", () -> pruneLearningSamples(safeNow)));
        deletedByTable.put("prediction_diff_log",
                safePrune("prediction_diff_log", () -> prunePredictionDiff(safeNow)));
        return deletedByTable;
    }

    private int safePrune(String label, java.util.function.IntSupplier prune) {
        try {
            int n = prune.getAsInt();
            if (n > 0) {
                log.info("[retention] pruned {} rows from {}", n, label);
            }
            return n;
        } catch (RuntimeException ex) {
            log.warn("[retention] prune of {} FAILED: {} (other tables still ran)", label, ex.toString());
            return -1;
        }
    }

    @Transactional
    public int pruneScrapeErrors(LocalDateTime now) {
        LocalDateTime cutoff = now.minusDays(Math.max(1, scrapeErrorDays));
        return scrapeErrorRepository.deleteByOccurredAtBefore(cutoff);
    }

    @Transactional
    public int pruneSettlementDiffLog(LocalDateTime now) {
        LocalDateTime cutoff = now.minusDays(Math.max(1, settlementDiffDays));
        return settlementDiffLogRepository.deleteByDecidedAtBefore(cutoff);
    }

    @Transactional
    public int pruneSettlementAudit(LocalDateTime now) {
        LocalDateTime cutoff = now.minusDays(Math.max(1, settlementAuditDays));
        return settlementAuditRecordRepository.deleteByDecidedAtBefore(cutoff);
    }

    @Transactional
    public int pruneTrackedObservations(LocalDateTime now) {
        LocalDateTime cutoff = now.minusDays(Math.max(1, trackedObservationDays));
        return trackedMatchObservationRepository.deleteByObservedAtBefore(cutoff);
    }

    @Transactional
    public int pruneDecisionSamples(LocalDateTime now) {
        LocalDateTime cutoff = now.minusDays(Math.max(1, decisionSampleDays));
        return decisionSampleRepository.deleteByCreatedAtBefore(cutoff);
    }

    @Transactional
    public int pruneLearningSamples(LocalDateTime now) {
        LocalDateTime cutoff = now.minusDays(Math.max(1, learningSampleDays));
        return learningSampleRepository.deleteBySettledAtBefore(cutoff);
    }

    @Transactional
    public int prunePredictionDiff(LocalDateTime now) {
        LocalDateTime cutoff = now.minusDays(Math.max(1, predictionDiffDays));
        return predictionDiffLogRepository.deleteByComputedAtUtcBefore(cutoff);
    }
}
