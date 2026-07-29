package com.ttl.tabletennis.prediction.staking;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.PaperTradeLearningSample;
import com.ttl.tabletennis.repository.PaperTradeBetRepository;
import com.ttl.tabletennis.repository.PaperTradeLearningSampleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * One-shot helper that fills {@code closing_decimal_odds} +
 * {@code closing_observed_at} on existing {@link PaperTradeLearningSample}
 * rows that pre-date the §5 capture wiring.
 *
 * <p>Invoked manually via {@code POST /api/admin/clv/backfill?limit=N}.
 * Idempotent: rows that already carry a closing snapshot are skipped on
 * the query side, so re-running is safe.
 */
@Service
public class ClosingLineBackfillService {

    private static final Logger log = LoggerFactory.getLogger(ClosingLineBackfillService.class);
    private static final int DEFAULT_LIMIT = 500;
    private static final int MAX_LIMIT = 5_000;

    private final PaperTradeLearningSampleRepository learningSampleRepository;
    private final PaperTradeBetRepository betRepository;
    private final ClosingLineLookupService lookup;

    public ClosingLineBackfillService(PaperTradeLearningSampleRepository learningSampleRepository,
                                      PaperTradeBetRepository betRepository,
                                      ClosingLineLookupService lookup) {
        this.learningSampleRepository = learningSampleRepository;
        this.betRepository = betRepository;
        this.lookup = lookup;
    }

    /**
     * Walk up to {@code limit} samples that are missing closing-line data,
     * look each one up against {@code odds_snapshot}, and persist matches.
     * Returns a small summary so the admin caller can see progress.
     */
    public BackfillResult backfill(int requestedLimit) {
        int limit = clamp(requestedLimit, 1, MAX_LIMIT);
        List<PaperTradeLearningSample> candidates = learningSampleRepository
                .findByClosingDecimalOddsIsNullOrderBySettledAtDesc(PageRequest.of(0, limit));
        int scanned = 0;
        int filled = 0;
        int skippedNoBet = 0;
        int skippedNoSnapshot = 0;
        for (PaperTradeLearningSample sample : candidates) {
            if (sample == null || sample.getBetId() == null) {
                continue;
            }
            scanned++;
            Optional<PaperTradeBet> betOpt = betRepository.findById(sample.getBetId());
            if (betOpt.isEmpty()) {
                skippedNoBet++;
                continue;
            }
            Optional<ClosingLineLookupService.ClosingLine> line = lookup.findFor(betOpt.get());
            if (line.isEmpty()) {
                skippedNoSnapshot++;
                continue;
            }
            sample.setClosingDecimalOdds(line.get().decimalOdds());
            sample.setClosingObservedAt(line.get().observedAt());
            learningSampleRepository.save(sample);
            filled++;
        }
        if (scanned > 0) {
            log.info("[closing-line backfill] scanned={} filled={} skipped_no_bet={} skipped_no_snapshot={}",
                    scanned, filled, skippedNoBet, skippedNoSnapshot);
        }
        return new BackfillResult(scanned, filled, skippedNoBet, skippedNoSnapshot, limit);
    }

    public BackfillResult backfillDefault() {
        return backfill(DEFAULT_LIMIT);
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }

    public record BackfillResult(int scanned,
                                 int filled,
                                 int skippedNoBet,
                                 int skippedNoSnapshot,
                                 int limit) {
    }
}
