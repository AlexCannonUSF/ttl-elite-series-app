package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.scrape.SourceId;
import com.ttl.tabletennis.settlement.CoverageState;
import com.ttl.tabletennis.settlement.HoldOpen;
import com.ttl.tabletennis.settlement.IdentityLock;
import com.ttl.tabletennis.settlement.Settle;
import com.ttl.tabletennis.settlement.SettlementEvidence;
import com.ttl.tabletennis.settlement.SettlementReason;
import com.ttl.tabletennis.settlement.TrackedEventId;
import com.ttl.tabletennis.settlement.observation.LiveObservation;
import com.ttl.tabletennis.settlement.observation.MatchPhase;
import com.ttl.tabletennis.settlement.observation.ScoreState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SettlementFingerprintTests {

    @Test
    void evidenceFingerprintIgnoresSchedulerBundleTimestamp() {
        Instant observedAt = Instant.parse("2026-07-29T20:00:00Z");
        SettlementEvidence first = evidence(91L, observedAt, observedAt.plusSeconds(1), 3, 1);
        SettlementEvidence second = evidence(91L, observedAt, observedAt.plusSeconds(31), 3, 1);

        assertEquals(SettlementFingerprint.evidence(first), SettlementFingerprint.evidence(second));
    }

    @Test
    void materialScoreAndDecisionChangesProduceNewFingerprints() {
        Instant observedAt = Instant.parse("2026-07-29T20:00:00Z");
        SettlementEvidence first = evidence(92L, observedAt, observedAt.plusSeconds(1), 3, 1);
        SettlementEvidence changedScore = evidence(92L, observedAt, observedAt.plusSeconds(1), 3, 2);
        PaperTradeBet bet = bet(92L);
        String evidenceFingerprint = SettlementFingerprint.evidence(first);

        assertNotEquals(evidenceFingerprint, SettlementFingerprint.evidence(changedScore));
        assertNotEquals(
                SettlementFingerprint.decision(
                        bet,
                        evidenceFingerprint,
                        new Settle(first, 10L, SettlementReason.SCORE_BACKED_FINISHED, 0.95)
                ),
                SettlementFingerprint.decision(
                        bet,
                        evidenceFingerprint,
                        new HoldOpen(first, SettlementReason.MANUAL_REVIEW_AWAITING, "awaiting confirmation")
                )
        );
    }

    @Test
    void diffFingerprintIsStableAcrossRepeatedPolls() {
        String first = SettlementFingerprint.diff(
                93L,
                "HOLD_OPEN",
                "OFFICIAL_RESULT_CONFIRMED",
                null,
                10L,
                "OUTCOME_DIFF"
        );
        String repeated = SettlementFingerprint.diff(
                93L,
                "HOLD_OPEN",
                "OFFICIAL_RESULT_CONFIRMED",
                null,
                10L,
                "OUTCOME_DIFF"
        );

        assertEquals(first, repeated);
    }

    private SettlementEvidence evidence(long betId,
                                        Instant observedAt,
                                        Instant bundleAsOf,
                                        int sets1,
                                        int sets2) {
        LiveObservation live = new LiveObservation(
                SourceId.HR_TGT,
                observedAt,
                0.95,
                MatchPhase.FINISHED,
                new ScoreState(sets1, sets2, 11, 7, ""),
                "raw-score",
                true,
                "booker-" + betId,
                "market-" + betId,
                false,
                true
        );
        return new SettlementEvidence(
                betId,
                new TrackedEventId("event-" + betId),
                new IdentityLock(
                        10L,
                        20L,
                        observedAt.minus(Duration.ofMinutes(30)),
                        Duration.ofMinutes(90),
                        "booker-" + betId,
                        "market-" + betId
                ),
                List.of(live),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                CoverageState.FULL,
                List.of(),
                0.0,
                0.95,
                bundleAsOf
        );
    }

    private PaperTradeBet bet(long id) {
        PaperTradeBet bet = new PaperTradeBet();
        try {
            Field field = PaperTradeBet.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(bet, id);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
        return bet;
    }
}
