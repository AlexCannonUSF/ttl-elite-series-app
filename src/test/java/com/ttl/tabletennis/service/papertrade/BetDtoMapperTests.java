package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.dto.PaperTradeBetDto;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BetDtoMapperTests {

    @Test
    void copiesAllPrimaryFields() {
        PaperTradeBet bet = new PaperTradeBet();
        // id is JPA-managed (no setter); the mapper just reads it via getId().
        bet.setStatus(PaperTradeBet.STATUS_WON);
        bet.setSource("ttseries");
        bet.setStrategy("kelly-v3");
        bet.setModelVersion("variant-a-3.0.0");
        bet.setEventName("Smith vs. Jones");
        bet.setCompetitionName("Champions League");
        bet.setLiveAtPlacement(true);
        bet.setStake(20.0);
        bet.setDecimalOdds(2.5);
        bet.setPotentialPayout(50.0);
        bet.setProfitLoss(30.0);
        bet.setModelProbability(0.55);
        bet.setImpliedProbability(0.40);
        bet.setEdge(0.15);
        bet.setSidePlayerId(7L);
        bet.setSideName("Smith");
        bet.setTopTrigger("Smash");
        bet.setSettlementConfidence(0.94);
        bet.setSettlementEvidenceId(501L);
        bet.setSettlementEvidenceFingerprint("evidence-fingerprint");
        bet.setSettlementEvidenceSourceCount(3);
        bet.setSettlementCoverageState("FULL");
        bet.setSettlementAmbiguityScore(0.08);
        bet.setSettlementObservedAt(LocalDateTime.parse("2026-05-10T19:29:55"));
        bet.setClosingDecimalOdds(2.32);
        bet.setClosingObservedAt(LocalDateTime.parse("2026-05-10T19:29:50"));
        bet.setClosingSource("HR_MKT");
        bet.setClosingMarketState("CLOSED");
        bet.setPlacedAt(LocalDateTime.parse("2026-05-10T18:00:00"));
        bet.setSettledAt(LocalDateTime.parse("2026-05-10T19:30:00"));

        PaperTradeBetDto dto = BetDtoMapper.toDto(bet, "SETTLED");

        assertEquals(PaperTradeBet.STATUS_WON, dto.status());
        assertEquals("ttseries", dto.source());
        assertEquals("kelly-v3", dto.strategy());
        assertEquals("variant-a-3.0.0", dto.modelVersion());
        assertEquals("Smith vs. Jones", dto.eventName());
        assertEquals("Champions League", dto.competitionName());
        assertTrue(dto.liveAtPlacement());
        assertEquals(20.0, dto.stake(), 1e-9);
        assertEquals(2.5, dto.decimalOdds(), 1e-9);
        assertEquals(50.0, dto.potentialPayout(), 1e-9);
        assertEquals(30.0, dto.profitLoss(), 1e-9);
        assertEquals(0.55, dto.modelProbability(), 1e-9);
        assertEquals(0.40, dto.impliedProbability(), 1e-9);
        assertEquals(0.15, dto.edge(), 1e-9);
        assertEquals("Smith", dto.sideName());
        assertEquals("Smash", dto.topTrigger());
        assertEquals(0.94, dto.settlementConfidence(), 1e-9);
        assertEquals(501L, dto.settlementEvidenceId());
        assertEquals("evidence-fingerprint", dto.settlementEvidenceFingerprint());
        assertEquals(3, dto.settlementEvidenceSourceCount());
        assertEquals("FULL", dto.settlementCoverageState());
        assertEquals(0.08, dto.settlementAmbiguityScore(), 1e-9);
        assertEquals(LocalDateTime.parse("2026-05-10T19:29:55"), dto.settlementObservedAt());
        assertEquals(2.32, dto.closingDecimalOdds(), 1e-9);
        assertEquals(LocalDateTime.parse("2026-05-10T19:29:50"), dto.closingObservedAt());
        assertEquals("HR_MKT", dto.closingSource());
        assertEquals("CLOSED", dto.closingMarketState());
        assertEquals(LocalDateTime.parse("2026-05-10T18:00:00"), dto.placedAt());
        assertEquals(LocalDateTime.parse("2026-05-10T19:30:00"), dto.settledAt());
    }

    @Test
    void passesThroughTrackingState() {
        PaperTradeBet bet = new PaperTradeBet();
        bet.setStatus(PaperTradeBet.STATUS_OPEN);

        // Mapper is pure pass-through; whatever the caller computed is the contract
        assertEquals("OPEN_PENDING_SCORE", BetDtoMapper.toDto(bet, "OPEN_PENDING_SCORE").trackingState());
        assertEquals("MARKET_CLOSED_SCORE_TRACKED",
                BetDtoMapper.toDto(bet, "MARKET_CLOSED_SCORE_TRACKED").trackingState());
        // Even nonsense pass-through must round-trip — the mapper is dumb.
        assertEquals("EXOTIC_STATE", BetDtoMapper.toDto(bet, "EXOTIC_STATE").trackingState());
    }

    @Test
    void copiesAllNullableFieldsCorrectly() {
        PaperTradeBet bet = new PaperTradeBet();
        // Most fields untouched → primitives default to 0/false, references to null.
        PaperTradeBetDto dto = BetDtoMapper.toDto(bet, "UNKNOWN");

        assertEquals(0.0, dto.stake(), 1e-9);
        assertFalse(dto.liveAtPlacement());
        assertEquals("UNKNOWN", dto.trackingState());
    }
}
