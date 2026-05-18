package com.ttl.tabletennis.config;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.repository.PaperTradeBetRepository;
import com.ttl.tabletennis.service.ScoreTruthAdvisoryService;
import com.ttl.tabletennis.service.ScoreTruthReviewService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

@Component
public class ScoreTruthMetricsBinder implements MeterBinder {

    private final ScoreTruthReviewService scoreTruthReviewService;
    private final PaperTradeBetRepository betRepository;
    private final Clock clock;

    @Autowired
    public ScoreTruthMetricsBinder(ScoreTruthReviewService scoreTruthReviewService,
                                   PaperTradeBetRepository betRepository) {
        this(scoreTruthReviewService, betRepository, Clock.systemDefaultZone());
    }

    ScoreTruthMetricsBinder(ScoreTruthReviewService scoreTruthReviewService,
                            PaperTradeBetRepository betRepository,
                            Clock clock) {
        this.scoreTruthReviewService = scoreTruthReviewService;
        this.betRepository = betRepository;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("ttl.score_truth.manual_review.queue.depth", this, ScoreTruthMetricsBinder::manualReviewQueueDepth)
                .description("Unresolved score-truth manual review decisions")
                .register(registry);
        Gauge.builder("ttl.score_truth.pending_evidence.ttl_expired.last_hour", this, ScoreTruthMetricsBinder::ttlExpiriesLastHour)
                .description("Pending-evidence bets whose score-truth TTL expired in the last hour")
                .register(registry);
    }

    private double manualReviewQueueDepth() {
        return scoreTruthReviewService == null ? 0.0 : scoreTruthReviewService.unresolvedQueueDepth();
    }

    private double ttlExpiriesLastHour() {
        if (betRepository == null) {
            return 0.0;
        }
        return betRepository.countByStatusAndPendingEvidenceReasonAndPendingEvidenceUpdatedAtGreaterThanEqual(
                PaperTradeBet.STATUS_PENDING_EVIDENCE,
                ScoreTruthAdvisoryService.PENDING_EVIDENCE_TTL_EXPIRED,
                LocalDateTime.now(clock).minusHours(1)
        );
    }
}
