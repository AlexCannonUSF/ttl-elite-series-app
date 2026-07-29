package com.ttl.tabletennis.prediction.staking;

import com.ttl.tabletennis.domain.PaperTradeLearningSample;
import com.ttl.tabletennis.repository.PaperTradeLearningSampleRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class StakingClvWatcherTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-19T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void computesPositiveClvWhenWindowProfitable() {
        StakingClvWatcher.ClvSnapshot snapshot = StakingClvWatcher.compute(List.of(
                settled(100.0, 80.0),
                settled(100.0, -100.0),
                settled(200.0, 180.0)
        ));
        // pnl = 80 - 100 + 180 = 160; stake = 400; clv = 0.40
        assertEquals(0.40, snapshot.clv(), 1e-9);
        assertEquals(3, snapshot.sampleCount());
        assertEquals(400.0, snapshot.totalStake(), 1e-9);
        assertEquals(160.0, snapshot.totalPnL(), 1e-9);
    }

    @Test
    void emptyOrZeroStakeYieldsEmptySnapshot() {
        assertEquals(StakingClvWatcher.ClvSnapshot.empty(), StakingClvWatcher.compute(null));
        assertEquals(StakingClvWatcher.ClvSnapshot.empty(), StakingClvWatcher.compute(List.of()));
        assertEquals(StakingClvWatcher.ClvSnapshot.empty(), StakingClvWatcher.compute(List.of(settled(0.0, 0.0))));
    }

    @Test
    void refreshUpdatesGaugesFromRepository() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PaperTradeLearningSampleRepository repo = Mockito.mock(PaperTradeLearningSampleRepository.class);
        when(repo.findBySettledAtAfter(any(LocalDateTime.class))).thenReturn(List.of(
                settled(50.0, -20.0),
                settled(50.0, -10.0)
        ));
        StakingClvWatcher watcher = new StakingClvWatcher(repo, registry, CLOCK);

        StakingClvWatcher.ClvSnapshot snapshot = watcher.refresh();

        // pnl = -30, stake = 100, clv = -0.30
        assertEquals(-0.30, snapshot.clv(), 1e-9);
        assertEquals(2, snapshot.sampleCount());
        assertEquals(-0.30, watcher.currentClv(), 1e-9);
        assertEquals(2, watcher.currentSampleCount());

        assertNotNull(registry.find(StakingClvWatcher.METRIC_CLV).gauge());
        assertEquals(-0.30, registry.find(StakingClvWatcher.METRIC_CLV).gauge().value(), 1e-9);
        assertEquals(2.0, registry.find(StakingClvWatcher.METRIC_SAMPLES).gauge().value(), 1e-9);
    }

    @Test
    void refreshSwallowsRepositoryErrors() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PaperTradeLearningSampleRepository repo = Mockito.mock(PaperTradeLearningSampleRepository.class);
        when(repo.findBySettledAtAfter(any(LocalDateTime.class))).thenThrow(new RuntimeException("db down"));
        StakingClvWatcher watcher = new StakingClvWatcher(repo, registry, CLOCK);

        StakingClvWatcher.ClvSnapshot snapshot = watcher.refresh();
        assertEquals(StakingClvWatcher.ClvSnapshot.empty(), snapshot);
        assertEquals(0.0, watcher.currentClv(), 1e-9);
    }

    @Test
    void refreshUsesSevenDayCutoff() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PaperTradeLearningSampleRepository repo = Mockito.mock(PaperTradeLearningSampleRepository.class);
        org.mockito.ArgumentCaptor<LocalDateTime> captor = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        when(repo.findBySettledAtAfter(captor.capture())).thenReturn(List.of());

        StakingClvWatcher watcher = new StakingClvWatcher(repo, registry, CLOCK);
        watcher.refresh();

        LocalDateTime cutoff = captor.getValue();
        LocalDateTime expected = LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC).minusDays(7);
        assertEquals(expected, cutoff);
    }

    @Test
    void usesTrueClvWhenClosingLinePresent() {
        // Bet placed at decimal 2.50 → implied 0.40
        // Market closed at decimal 2.00 → implied 0.50
        // True per-bet CLV = (0.50 - 0.40) / 0.40 = +0.25 (we beat the close)
        PaperTradeLearningSample row = settledWithClose(100.0, 0.0, 0.40, 2.00);
        StakingClvWatcher.ClvSnapshot snapshot = StakingClvWatcher.compute(java.util.List.of(row));

        assertEquals(0.25, snapshot.clv(), 1e-9);
        assertEquals(1, snapshot.sampleCount());
        assertEquals(1, snapshot.closingLineSamples());
        assertEquals(1.0, snapshot.coverageRatio(), 1e-9);
    }

    @Test
    void blendsTrueClvWithProxyWhenCoverageIsPartial() {
        // Row 1: closing-line present, +25% CLV, stake 100
        PaperTradeLearningSample beatClose = settledWithClose(100.0, 0.0, 0.40, 2.00);
        // Row 2: no closing snapshot, falls back to PnL/stake = +0.30 (stake 100, pnl +30)
        PaperTradeLearningSample noClose = settledWithClose(100.0, 30.0, 0.50, null);
        StakingClvWatcher.ClvSnapshot snapshot = StakingClvWatcher.compute(java.util.List.of(beatClose, noClose));

        // Stake-weighted blend: (100*0.25 + 100*0.30) / 200 = 0.275
        assertEquals(0.275, snapshot.clv(), 1e-9);
        assertEquals(2, snapshot.sampleCount());
        assertEquals(1, snapshot.closingLineSamples());
        assertEquals(0.5, snapshot.coverageRatio(), 1e-9);
    }

    @Test
    void coverageRatioPublishedAsGauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PaperTradeLearningSampleRepository repo = Mockito.mock(PaperTradeLearningSampleRepository.class);
        when(repo.findBySettledAtAfter(any(LocalDateTime.class))).thenReturn(java.util.List.of(
                settledWithClose(100.0, 0.0, 0.40, 2.00),
                settledWithClose(100.0, 30.0, 0.50, null)
        ));
        StakingClvWatcher watcher = new StakingClvWatcher(repo, registry, CLOCK);
        watcher.refresh();

        assertNotNull(registry.find(StakingClvWatcher.METRIC_COVERAGE).gauge());
        assertEquals(0.5, registry.find(StakingClvWatcher.METRIC_COVERAGE).gauge().value(), 1e-9);
    }

    private static PaperTradeLearningSample settled(double stake, double pnl) {
        PaperTradeLearningSample sample = new PaperTradeLearningSample();
        sample.setStake(stake);
        sample.setProfitLoss(pnl);
        sample.setStatus(pnl >= 0 ? "WON" : "LOST");
        sample.setSettledAt(LocalDateTime.of(2026, 5, 18, 12, 0));
        return sample;
    }

    private static PaperTradeLearningSample settledWithClose(double stake,
                                                              double pnl,
                                                              double impliedAtBet,
                                                              Double closingDecimalOdds) {
        PaperTradeLearningSample sample = settled(stake, pnl);
        sample.setImpliedProbability(impliedAtBet);
        if (closingDecimalOdds != null) {
            sample.setClosingDecimalOdds(closingDecimalOdds);
            sample.setClosingObservedAt(LocalDateTime.of(2026, 5, 18, 12, 30));
        }
        return sample;
    }
}
