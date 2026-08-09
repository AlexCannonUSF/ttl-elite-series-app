package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.PaperTradeModelCall;
import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.dto.ModelRunHistoryDto;
import com.ttl.tabletennis.repository.PaperTradeBetRepository;
import com.ttl.tabletennis.repository.PaperTradeModelCallRepository;
import com.ttl.tabletennis.repository.PaperTradeSessionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class ModelRunHistoryService {

    private final PaperTradeSessionRepository sessionRepository;
    private final PaperTradeBetRepository betRepository;
    private final PaperTradeModelCallRepository callRepository;

    public ModelRunHistoryService(PaperTradeSessionRepository sessionRepository,
                                  PaperTradeBetRepository betRepository,
                                  PaperTradeModelCallRepository callRepository) {
        this.sessionRepository = sessionRepository;
        this.betRepository = betRepository;
        this.callRepository = callRepository;
    }

    @Transactional(readOnly = true)
    public ModelRunHistoryDto history(int limit) {
        int take = Math.max(1, Math.min(limit, 100));
        List<ModelRunHistoryDto.Run> runs = sessionRepository.findAllByOrderByIdDesc(PageRequest.of(0, take))
                .stream()
                .map(this::summarize)
                .toList();
        return new ModelRunHistoryDto(LocalDateTime.now(), runs);
    }

    private ModelRunHistoryDto.Run summarize(PaperTradeSession session) {
        List<PaperTradeBet> bets = betRepository.findBySessionIdOrderByPlacedAtAsc(session.getId());
        long calls = callRepository.countBySessionId(session.getId());
        int wins = count(bets, PaperTradeBet.STATUS_WON);
        int losses = count(bets, PaperTradeBet.STATUS_LOST);
        int pushes = count(bets, PaperTradeBet.STATUS_PUSHED);
        int voids = count(bets, PaperTradeBet.STATUS_VOIDED);
        int open = count(bets, PaperTradeBet.STATUS_OPEN);
        int settled = wins + losses;
        double staked = bets.stream().mapToDouble(PaperTradeBet::getStake).sum();
        double settledStake = bets.stream()
                .filter(bet -> PaperTradeBet.STATUS_WON.equalsIgnoreCase(bet.getStatus())
                        || PaperTradeBet.STATUS_LOST.equalsIgnoreCase(bet.getStatus()))
                .mapToDouble(PaperTradeBet::getStake)
                .sum();
        double pnl = bets.stream()
                .filter(bet -> bet.getProfitLoss() != null)
                .mapToDouble(PaperTradeBet::getProfitLoss)
                .sum();
        // Open stakes have not produced a return yet. Including them in the
        // denominator understated completed-run ROI and made historical cards
        // disagree with the settlement ledger.
        double roi = settledStake <= 0.0 ? 0.0 : (pnl / settledStake) * 100.0;

        String effective = session.getEffectiveModelVersion();
        if (!StringUtils.hasText(effective)) {
            // Legacy sessions pre-date explicit run identity. A placed bet is
            // the strongest record of the model that actually drove risk; old
            // call rows may only contain the requested selector (ENSEMBLE).
            effective = bets.stream()
                    .map(PaperTradeBet::getModelVersion)
                    .filter(StringUtils::hasText)
                    .findFirst()
                    .orElse(null);
        }
        if (!StringUtils.hasText(effective)) {
            effective = callRepository.findBySessionIdOrderByCapturedAtDesc(session.getId()).stream()
                    .map(PaperTradeModelCall::getModelVersion)
                    .filter(StringUtils::hasText)
                    .findFirst()
                    .orElse(null);
        }
        String family = StringUtils.hasText(session.getEffectiveModelFamily())
                ? session.getEffectiveModelFamily()
                : inferFamily(effective);
        return new ModelRunHistoryDto.Run(
                session.getId(),
                session.getLabel(),
                session.getStatus(),
                session.getRequestedModelVersion(),
                effective,
                family,
                session.getPolicyVersion(),
                session.getCodeRevision(),
                session.getCreatedAt(),
                session.getClosedAt(),
                session.getLastSyncAt(),
                calls,
                bets.size(),
                open,
                settled,
                wins,
                losses,
                pushes,
                voids,
                round2(staked),
                round2(pnl),
                round2(roi),
                Math.min(100.0, round2((settled / 100.0) * 100.0))
        );
    }

    private static int count(List<PaperTradeBet> bets, String status) {
        return (int) bets.stream().filter(bet -> status.equalsIgnoreCase(bet.getStatus())).count();
    }

    private static String inferFamily(String version) {
        String value = version == null ? "" : version.toUpperCase(Locale.ROOT);
        if (value.contains("ENSEMBLE")) return "ENSEMBLE";
        if (value.contains("LOGISTIC")) return "LOGISTIC";
        if (value.contains("GBT")) return "GBT_LIKE";
        if (value.contains("RF")) return "RF_LIKE";
        if (value.contains("BASELINE")) return "BASELINE";
        return "UNKNOWN";
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
