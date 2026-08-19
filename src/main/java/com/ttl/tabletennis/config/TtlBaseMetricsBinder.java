package com.ttl.tabletennis.config;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.repository.PaperTradeBetRepository;
import com.ttl.tabletennis.repository.PaperTradeBetShadowRepository;
import com.ttl.tabletennis.repository.PaperTradeSessionRepository;
import com.ttl.tabletennis.repository.PaperTradeSessionShadowRepository;
import com.ttl.tabletennis.repository.ScrapeErrorRepository;
import com.ttl.tabletennis.repository.ScrapeRunRepository;
import com.ttl.tabletennis.repository.SettlementDiffLogRepository;
import com.ttl.tabletennis.repository.TrackedMatchObservationRepository;
import com.ttl.tabletennis.domain.SettlementDiffLog;
import com.ttl.tabletennis.scrape.FeedClient;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class TtlBaseMetricsBinder implements MeterBinder {

    private final PaperTradeSessionRepository sessionRepository;
    private final PaperTradeBetRepository betRepository;
    private final PaperTradeSessionShadowRepository sessionShadowRepository;
    private final PaperTradeBetShadowRepository betShadowRepository;
    private final TrackedMatchObservationRepository trackedMatchObservationRepository;
    private final ScrapeRunRepository scrapeRunRepository;
    private final ScrapeErrorRepository scrapeErrorRepository;
    private final SettlementDiffLogRepository settlementDiffLogRepository;
    private final List<FeedClient<?>> feedClients;

    public TtlBaseMetricsBinder(PaperTradeSessionRepository sessionRepository,
                                PaperTradeBetRepository betRepository,
                                PaperTradeSessionShadowRepository sessionShadowRepository,
                                PaperTradeBetShadowRepository betShadowRepository,
                                TrackedMatchObservationRepository trackedMatchObservationRepository,
                                ScrapeRunRepository scrapeRunRepository,
                                ScrapeErrorRepository scrapeErrorRepository,
                                SettlementDiffLogRepository settlementDiffLogRepository,
                                List<FeedClient<?>> feedClients) {
        this.sessionRepository = sessionRepository;
        this.betRepository = betRepository;
        this.sessionShadowRepository = sessionShadowRepository;
        this.betShadowRepository = betShadowRepository;
        this.trackedMatchObservationRepository = trackedMatchObservationRepository;
        this.scrapeRunRepository = scrapeRunRepository;
        this.scrapeErrorRepository = scrapeErrorRepository;
        this.settlementDiffLogRepository = settlementDiffLogRepository;
        this.feedClients = feedClients == null ? List.of() : List.copyOf(feedClients);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("ttl.paper.sessions", sessionRepository, repository -> repository.count())
                .description("Total paper-trading sessions in the primary table")
                .tag("table", "primary")
                .tag("state", "all")
                .register(registry);
        Gauge.builder("ttl.paper.sessions", this, binder -> binder.activeSessionCount())
                .description("Active paper-trading sessions")
                .tag("table", "primary")
                .tag("state", "active")
                .register(registry);
        Gauge.builder("ttl.paper.sessions", sessionShadowRepository, repository -> repository.count())
                .description("Total paper-trading sessions in the shadow table")
                .tag("table", "shadow")
                .tag("state", "all")
                .register(registry);

        registerBetGauge(registry, PaperTradeBet.STATUS_OPEN);
        registerBetGauge(registry, PaperTradeBet.STATUS_WON);
        registerBetGauge(registry, PaperTradeBet.STATUS_LOST);
        registerBetGauge(registry, PaperTradeBet.STATUS_PUSHED);
        registerBetGauge(registry, PaperTradeBet.STATUS_VOIDED);
        registerBetGauge(registry, PaperTradeBet.STATUS_PENDING_EVIDENCE);

        Gauge.builder("ttl.paper.bankroll.current", this, binder -> binder.activeCurrentBankroll())
                .description("Current bankroll for the latest active paper session")
                .register(registry);
        Gauge.builder("ttl.paper.pnl.realized", this, binder -> binder.activeRealizedPnl())
                .description("Realized P&L for the latest active paper session")
                .register(registry);
        Gauge.builder("ttl.paper.roi.current", this, binder -> binder.activeRoi())
                .description("Realized ROI for the latest active paper session")
                .register(registry);

        Gauge.builder("ttl.shadow.bets", betShadowRepository, repository -> repository.count())
                .description("Mirrored paper-trade bets in the shadow table")
                .register(registry);
        Gauge.builder("ttl.tracked.observations", trackedMatchObservationRepository, repository -> repository.count())
                .description("Tracked match observations captured for score continuity")
                .register(registry);
        Gauge.builder("ttl.scrape.runs", scrapeRunRepository, repository -> repository.count())
                .description("Persisted scrape runs")
                .register(registry);
        Gauge.builder("ttl.scrape.errors", scrapeErrorRepository, repository -> repository.count())
                .description("Persisted scrape errors")
                .register(registry);
        Gauge.builder("ttl.settlement.diff.rows", settlementDiffLogRepository, repository -> repository.count())
                .description("Persisted settlement shadow-diff rows")
                .register(registry);
        Gauge.builder("ttl.settlement.diff.disagreements", this, binder -> binder.settlementDiffDisagreements())
                .description("Settlement shadow-diff rows that are not AGREE")
                .register(registry);
        Gauge.builder("ttl.settlement.diff.contradictions", settlementDiffLogRepository,
                        repository -> repository.countByDiffKind(SettlementDiffLog.DIFF_KIND_CONTRADICTION))
                .description("Settlement shadow-diff rows blocked by score-truth contradictions")
                .register(registry);

        for (FeedClient<?> feedClient : feedClients) {
            Gauge.builder("ttl.feed.success.rate", feedClient, client -> client.currentHealth().rollingSuccessRate5m())
                    .description("Rolling five-minute success rate for feed pulls")
                    .tag("source", feedClient.source().id())
                    .register(registry);
            Gauge.builder("ttl.feed.latency.p50.ms", feedClient, client -> client.currentHealth().rollingP50LatencyMs())
                    .description("Rolling one-minute p50 feed pull latency")
                    .tag("source", feedClient.source().id())
                    .register(registry);
            Gauge.builder("ttl.feed.latency.p95.ms", feedClient, client -> client.currentHealth().rollingP95LatencyMs())
                    .description("Rolling one-minute p95 feed pull latency")
                    .tag("source", feedClient.source().id())
                    .register(registry);
            Gauge.builder("ttl.feed.inflight", feedClient, client -> client.currentHealth().inFlight())
                    .description("In-flight pulls for a feed")
                    .tag("source", feedClient.source().id())
                    .register(registry);
            Gauge.builder("ttl.feed.staleness.seconds", feedClient, client -> client.currentHealth().stalenessSeconds())
                    .description("Seconds since the last successful feed pull")
                    .tag("source", feedClient.source().id())
                    .register(registry);
            Gauge.builder("ttl.feed.last.success.age.seconds", feedClient, client -> ageSeconds(client.currentHealth().lastSuccess()))
                    .description("Seconds since the last successful pull")
                    .tag("source", feedClient.source().id())
                    .register(registry);
            Gauge.builder("ttl.feed.last.failure.age.seconds", feedClient, client -> ageSeconds(client.currentHealth().lastFailure()))
                    .description("Seconds since the last failed pull")
                    .tag("source", feedClient.source().id())
                    .register(registry);
        }
    }

    private void registerBetGauge(MeterRegistry registry, String status) {
        Gauge.builder("ttl.paper.bets", this, binder -> binder.activeSessionBetCount(status))
                .description("Paper-trade bet counts for the latest active session")
                .tag("status", status.toLowerCase())
                .register(registry);
    }

    private double activeSessionCount() {
        return sessionRepository.findByStatusOrderByIdDesc(PaperTradeSession.STATUS_ACTIVE).size();
    }

    private double activeSessionBetCount(String status) {
        return activeSession()
                .map(session -> (double) betRepository.countBySessionIdAndStatus(session.getId(), status))
                .orElse(0.0);
    }

    private double activeCurrentBankroll() {
        return activeSession().map(PaperTradeSession::getCurrentBankroll).orElse(0.0);
    }

    private double activeRealizedPnl() {
        return activeSession().map(PaperTradeSession::getRealizedPnl).orElse(0.0);
    }

    private double activeRoi() {
        return activeSession()
                .map(session -> {
                    double totalStaked = session.getTotalStaked();
                    if (totalStaked <= 0.0) {
                        return 0.0;
                    }
                    return session.getRealizedPnl() / totalStaked;
                })
                .orElse(0.0);
    }

    private Optional<PaperTradeSession> activeSession() {
        return sessionRepository.findFirstByStatusOrderByIdDesc(PaperTradeSession.STATUS_ACTIVE);
    }

    private double ageSeconds(Instant instant) {
        if (instant == null) {
            return -1.0;
        }
        return Math.max(0.0, Duration.between(instant, Instant.now()).toMillis() / 1000.0);
    }

    private double settlementDiffDisagreements() {
        long total = settlementDiffLogRepository.count();
        long agrees = settlementDiffLogRepository.countByDiffKind(SettlementDiffLog.DIFF_KIND_AGREE);
        return Math.max(0L, total - agrees);
    }
}
