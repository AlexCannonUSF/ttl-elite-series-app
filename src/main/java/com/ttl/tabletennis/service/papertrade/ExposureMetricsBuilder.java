package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.dto.PaperTradingSessionDto;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.EPS;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.clamp;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.normalizeTrigger;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.round2;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.round4;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.valueOrZero;

/**
 * Pure-function builder: turns the session + open bets + cap configuration
 * into the exposure-metrics block of the session-snapshot DTO.
 *
 * <p>Fourth §4 SessionService slice. Same static-utility shape as
 * {@link TriggerInsightsBuilder} and {@link EquityCurveBuilder}: no Spring
 * dependency, no repository, no shared state. The cap configuration is
 * passed in via {@link ExposureCaps} so {@code PaperTradingService} (which
 * keeps the {@code @Value}-injected raw values for use in the placement
 * loop) builds the record at the call site.
 *
 * <p>The clamps on each cap (e.g. {@code openExposurePct} forced into
 * {@code [0.10, 0.95]}) preserve the legacy guard rails. They are not
 * meant as a policy statement here — they belong to the staking policy
 * proper, but mirroring them keeps this extract behaviour-neutral.
 */
public final class ExposureMetricsBuilder {

    private static final double MIN_CAPITAL_BASE = 100.0;

    private ExposureMetricsBuilder() {
        // utility class — not instantiable
    }

    public static PaperTradingSessionDto.ExposureMetricsDto buildExposureMetrics(PaperTradeSession session,
                                                                                  List<PaperTradeBet> openRows,
                                                                                  ExposureCaps caps) {
        List<PaperTradeBet> open = openRows == null ? List.of() : openRows;
        ExposureProfile exposureProfile = ExposureProfile.fromOpenBets(open);
        double capitalBase = Math.max(
                valueOrZero(session == null ? null : session.getCurrentBankroll()),
                round2(valueOrZero(session == null ? null : session.getCurrentBankroll()) + exposureProfile.openStake())
        );
        capitalBase = Math.max(MIN_CAPITAL_BASE, capitalBase);

        double openExposureCap = round2(capitalBase * clamp(caps.maxOpenExposurePct(), 0.10, 0.95));
        double openExposure = round2(exposureProfile.openStake());
        double openExposureUsagePct = openExposureCap <= EPS ? 0.0 : clamp(openExposure / openExposureCap, 0.0, 2.0);
        double openExposureRemaining = round2(Math.max(0.0, openExposureCap - openExposure));
        int maxOpenBets = clamp(caps.maxConcurrentOpenBets(), 1, 60);
        double concurrentUsagePct = maxOpenBets <= 0 ? 0.0 : clamp(exposureProfile.openBets() / (double) maxOpenBets, 0.0, 2.0);

        double playerCap = round2(capitalBase * clamp(caps.maxExposurePerPlayerPct(), 0.03, 0.60));
        double triggerCap = round2(capitalBase * clamp(caps.maxExposurePerTriggerPct(), 0.05, 0.75));

        Map<Long, Double> playerStake = new HashMap<>();
        Map<Long, String> playerNames = new HashMap<>();
        Map<String, Double> triggerStake = new HashMap<>();

        for (PaperTradeBet bet : open) {
            if (bet == null || !PaperTradeBet.STATUS_OPEN.equalsIgnoreCase(bet.getStatus())) {
                continue;
            }
            double stake = Math.max(0.0, bet.getStake());
            if (bet.getSidePlayerId() != null) {
                playerStake.merge(bet.getSidePlayerId(), stake, Double::sum);
                if (StringUtils.hasText(bet.getSideName())) {
                    playerNames.putIfAbsent(bet.getSidePlayerId(), bet.getSideName().trim());
                }
            }
            String trigger = normalizeTrigger(bet.getTopTrigger());
            if (StringUtils.hasText(trigger)) {
                triggerStake.merge(trigger, stake, Double::sum);
            }
        }

        long mostExposedPlayerId = playerStake.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(-1L);
        double mostExposedPlayerStake = round2(mostExposedPlayerId < 0 ? 0.0 : playerStake.getOrDefault(mostExposedPlayerId, 0.0));
        String mostExposedPlayerName = mostExposedPlayerId < 0
                ? null
                : playerNames.getOrDefault(mostExposedPlayerId, "Player " + mostExposedPlayerId);
        double mostExposedPlayerUsagePct = playerCap <= EPS ? 0.0 : clamp(mostExposedPlayerStake / playerCap, 0.0, 2.0);
        int playerNearCapCount = (int) playerStake.values().stream()
                .mapToDouble(Double::doubleValue)
                .filter(stake -> playerCap > EPS && (stake / playerCap) >= 0.80)
                .count();

        String mostExposedTrigger = triggerStake.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
        double mostExposedTriggerStake = round2(StringUtils.hasText(mostExposedTrigger)
                ? triggerStake.getOrDefault(mostExposedTrigger, 0.0)
                : 0.0);
        double mostExposedTriggerUsagePct = triggerCap <= EPS ? 0.0 : clamp(mostExposedTriggerStake / triggerCap, 0.0, 2.0);
        int triggerNearCapCount = (int) triggerStake.values().stream()
                .mapToDouble(Double::doubleValue)
                .filter(stake -> triggerCap > EPS && (stake / triggerCap) >= 0.80)
                .count();

        return new PaperTradingSessionDto.ExposureMetricsDto(
                openExposure,
                openExposureCap,
                round4(openExposureUsagePct),
                openExposureRemaining,
                maxOpenBets,
                round4(concurrentUsagePct),
                mostExposedPlayerName,
                mostExposedPlayerStake,
                playerCap,
                round4(mostExposedPlayerUsagePct),
                playerNearCapCount,
                mostExposedTrigger,
                mostExposedTriggerStake,
                triggerCap,
                round4(mostExposedTriggerUsagePct),
                triggerNearCapCount
        );
    }

    /**
     * Cap-configuration record: the raw {@code @Value}-injected fields the
     * caller (today {@code PaperTradingService}) hands in at the delegate
     * call site. Kept as plain doubles + an int so callers don't need to
     * import any policy types.
     */
    public record ExposureCaps(int maxConcurrentOpenBets,
                               double maxOpenExposurePct,
                               double maxExposurePerPlayerPct,
                               double maxExposurePerTriggerPct) {
    }
}
