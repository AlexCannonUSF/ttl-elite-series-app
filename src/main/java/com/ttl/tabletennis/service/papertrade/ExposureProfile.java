package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeBet;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.normalizeTrigger;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.round2;

/**
 * Aggregate snapshot of an account's open exposure: how many bets are
 * outstanding, total stake, and per-player / per-trigger stake sums.
 *
 * <p>Originally a private nested record inside {@code PaperTradingService};
 * lifted here as part of the §4 decomposition because two callers now need it:
 * <ol>
 *   <li>the placement loop (when deciding whether a new bet would breach a cap), and</li>
 *   <li>{@link ExposureMetricsBuilder} (when rendering the session-snapshot DTO).</li>
 * </ol>
 *
 * <p>Behaviour is verbatim from the original record — same factory rules,
 * same {@code addPlacement} update semantics, same getter shapes. Anything
 * that wants to change exposure math should change it here, not at the
 * callers.
 */
public record ExposureProfile(int openBets,
                              double openStake,
                              Map<Long, Double> playerStake,
                              Map<String, Double> triggerStake) {

    public static ExposureProfile fromOpenBets(List<PaperTradeBet> bets) {
        if (bets == null || bets.isEmpty()) {
            return new ExposureProfile(0, 0.0, Map.of(), Map.of());
        }
        int openCount = 0;
        double openStake = 0.0;
        Map<Long, Double> byPlayer = new HashMap<>();
        Map<String, Double> byTrigger = new HashMap<>();
        for (PaperTradeBet bet : bets) {
            if (bet == null || !PaperTradeBet.STATUS_OPEN.equalsIgnoreCase(bet.getStatus())) {
                continue;
            }
            double stake = Math.max(0.0, bet.getStake());
            openCount++;
            openStake += stake;
            if (bet.getSidePlayerId() != null) {
                byPlayer.merge(bet.getSidePlayerId(), stake, Double::sum);
            }
            String trigger = normalizeTrigger(bet.getTopTrigger());
            if (StringUtils.hasText(trigger)) {
                byTrigger.merge(trigger, stake, Double::sum);
            }
        }
        return new ExposureProfile(openCount, round2(openStake), byPlayer, byTrigger);
    }

    public ExposureProfile addPlacement(Long sidePlayerId, String triggerKey, double stake) {
        double normalizedStake = Math.max(0.0, stake);
        Map<Long, Double> nextByPlayer = new HashMap<>(playerStake);
        Map<String, Double> nextByTrigger = new HashMap<>(triggerStake);
        if (sidePlayerId != null) {
            nextByPlayer.merge(sidePlayerId, normalizedStake, Double::sum);
        }
        String trigger = normalizeTrigger(triggerKey);
        if (StringUtils.hasText(trigger)) {
            nextByTrigger.merge(trigger, normalizedStake, Double::sum);
        }
        return new ExposureProfile(openBets + 1, round2(openStake + normalizedStake), nextByPlayer, nextByTrigger);
    }

    public double playerStake(Long sidePlayerId) {
        if (sidePlayerId == null || playerStake == null || playerStake.isEmpty()) {
            return 0.0;
        }
        return Math.max(0.0, playerStake.getOrDefault(sidePlayerId, 0.0));
    }

    public double triggerStake(String triggerKey) {
        String normalized = normalizeTrigger(triggerKey);
        if (!StringUtils.hasText(normalized) || triggerStake == null || triggerStake.isEmpty()) {
            return 0.0;
        }
        return Math.max(0.0, triggerStake.getOrDefault(normalized, 0.0));
    }
}
