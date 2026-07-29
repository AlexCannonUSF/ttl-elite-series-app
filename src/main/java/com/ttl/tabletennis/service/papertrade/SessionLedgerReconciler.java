package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.repository.PaperTradeBetRepository;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.round2;

/**
 * Rebuilds mutable session totals from the immutable bet ledger.
 *
 * <p>Session counters are a cache, never the authority. Reconciliation at
 * both ends of a locked sync repairs historic stale-writer damage and makes
 * the bankroll/exposure figures independently auditable.
 */
@Service
public class SessionLedgerReconciler {

    private final PaperTradeBetRepository betRepository;

    public SessionLedgerReconciler(PaperTradeBetRepository betRepository) {
        this.betRepository = betRepository;
    }

    public LedgerSnapshot reconcile(PaperTradeSession session) {
        if (session == null || session.getId() == null) {
            return LedgerSnapshot.empty();
        }
        List<PaperTradeBet> bets = betRepository.findBySessionIdOrderByPlacedAtAsc(session.getId());
        double totalStaked = 0.0;
        double totalReturned = 0.0;
        double realizedPnl = 0.0;
        int wins = 0;
        int losses = 0;
        int pushes = 0;
        int open = 0;

        for (PaperTradeBet bet : bets) {
            if (bet == null) {
                continue;
            }
            double stake = Math.max(0.0, bet.getStake());
            totalStaked += stake;
            String status = bet.getStatus() == null ? "" : bet.getStatus().trim().toUpperCase();
            switch (status) {
                case PaperTradeBet.STATUS_WON -> {
                    wins++;
                    totalReturned += stake * Math.max(1.0, bet.getDecimalOdds());
                    realizedPnl += bet.getProfitLoss() == null
                            ? stake * (Math.max(1.0, bet.getDecimalOdds()) - 1.0)
                            : bet.getProfitLoss();
                }
                case PaperTradeBet.STATUS_LOST -> {
                    losses++;
                    realizedPnl += bet.getProfitLoss() == null ? -stake : bet.getProfitLoss();
                }
                case PaperTradeBet.STATUS_PUSHED -> {
                    pushes++;
                    totalReturned += stake;
                    realizedPnl += bet.getProfitLoss() == null ? 0.0 : bet.getProfitLoss();
                }
                case PaperTradeBet.STATUS_VOIDED -> {
                    totalReturned += stake;
                    realizedPnl += bet.getProfitLoss() == null ? 0.0 : bet.getProfitLoss();
                }
                case PaperTradeBet.STATUS_OPEN, PaperTradeBet.STATUS_PENDING_EVIDENCE -> open++;
                default -> {
                    // Unknown states remain funded as open exposure.
                    open++;
                }
            }
        }

        double currentBankroll = session.getStartingBankroll() - totalStaked + totalReturned;
        session.setTotalBets(bets.size());
        session.setTotalStaked(round2(totalStaked));
        session.setTotalReturned(round2(totalReturned));
        session.setRealizedPnl(round2(realizedPnl));
        session.setCurrentBankroll(round2(currentBankroll));
        session.setWins(wins);
        session.setLosses(losses);
        session.setPushes(pushes);
        session.setPeakBankroll(Math.max(session.getPeakBankroll(), session.getCurrentBankroll()));

        return new LedgerSnapshot(
                bets.size(),
                open,
                wins,
                losses,
                pushes,
                round2(totalStaked),
                round2(totalReturned),
                round2(realizedPnl),
                round2(currentBankroll)
        );
    }

    public record LedgerSnapshot(int totalBets,
                                 int openBets,
                                 int wins,
                                 int losses,
                                 int pushes,
                                 double totalStaked,
                                 double totalReturned,
                                 double realizedPnl,
                                 double currentBankroll) {
        static LedgerSnapshot empty() {
            return new LedgerSnapshot(0, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0);
        }
    }
}
