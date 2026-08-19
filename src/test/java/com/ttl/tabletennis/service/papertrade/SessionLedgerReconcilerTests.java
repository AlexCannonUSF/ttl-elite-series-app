package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.repository.PaperTradeBetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionLedgerReconcilerTests {

    @Test
    void immutableBetLedgerRepairsAllSessionCountersAndBankroll() {
        PaperTradeBetRepository repository = mock(PaperTradeBetRepository.class);
        SessionLedgerReconciler reconciler = new SessionLedgerReconciler(repository);
        PaperTradeSession session = new PaperTradeSession();
        ReflectionTestUtils.setField(session, "id", 12L);
        session.setStartingBankroll(100.0);
        session.setCurrentBankroll(1.0);
        session.setPeakBankroll(100.0);
        when(repository.findBySessionIdOrderByPlacedAtAsc(12L)).thenReturn(List.of(
                bet(PaperTradeBet.STATUS_WON, 10.0, 2.0, null),
                bet(PaperTradeBet.STATUS_LOST, 5.0, 1.8, null),
                bet(PaperTradeBet.STATUS_PUSHED, 4.0, 2.1, null),
                bet(PaperTradeBet.STATUS_VOIDED, 3.0, 2.2, null),
                bet(PaperTradeBet.STATUS_OPEN, 8.0, 2.0, null)
        ));

        SessionLedgerReconciler.LedgerSnapshot result = reconciler.reconcile(session);

        assertEquals(5, result.totalBets());
        assertEquals(1, result.openBets());
        assertEquals(1, result.wins());
        assertEquals(1, result.losses());
        assertEquals(1, result.pushes());
        assertEquals(30.0, result.totalStaked());
        assertEquals(27.0, result.totalReturned());
        assertEquals(5.0, result.realizedPnl());
        assertEquals(97.0, result.currentBankroll());
        assertEquals(97.0, session.getCurrentBankroll());
        assertEquals(5, session.getTotalBets());
        assertEquals(100.0, session.getPeakBankroll());
    }

    @Test
    void persistedProfitLossOverridesDerivedPayoutMath() {
        PaperTradeBetRepository repository = mock(PaperTradeBetRepository.class);
        SessionLedgerReconciler reconciler = new SessionLedgerReconciler(repository);
        PaperTradeSession session = new PaperTradeSession();
        ReflectionTestUtils.setField(session, "id", 13L);
        session.setStartingBankroll(100.0);
        when(repository.findBySessionIdOrderByPlacedAtAsc(13L)).thenReturn(List.of(
                bet(PaperTradeBet.STATUS_WON, 10.0, 2.0, 7.5),
                bet(PaperTradeBet.STATUS_LOST, 10.0, 2.0, -8.0)
        ));

        SessionLedgerReconciler.LedgerSnapshot result = reconciler.reconcile(session);

        assertEquals(-0.5, result.realizedPnl());
        assertEquals(100.0, result.currentBankroll(),
                "cash bankroll follows actual stake/return ledger, independent of reported P&L");
    }

    private PaperTradeBet bet(String status, double stake, double odds, Double pnl) {
        PaperTradeBet bet = new PaperTradeBet();
        bet.setStatus(status);
        bet.setStake(stake);
        bet.setDecimalOdds(odds);
        bet.setProfitLoss(pnl);
        return bet;
    }
}
