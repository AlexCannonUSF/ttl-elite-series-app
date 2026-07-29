package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeBet;
import org.springframework.util.StringUtils;

/**
 * Resolve the "locked or current" identifier set for a bet. Each accessor
 * returns the locked-at-placement value when present, otherwise the most
 * recent observed/feed value, otherwise {@code ""}.
 *
 * <p>Nineteenth §4 slice — pure-function bet identifier resolvers. Used
 * 14+ times across the placement loop, observation tracking, and score
 * normalization paths. No state, no Spring; just trims and falls-back.
 *
 * <p>The "lock" semantic: once a bet's identity is pinned (via
 * {@code identityLocked = true}), the locked fields take precedence even
 * if newer rows arrive with different ids — that's how drift tracking
 * keeps a bet pointed at the match it was actually placed on.
 */
public final class BetLockedIdentity {

    private BetLockedIdentity() {
        // utility class — not instantiable
    }

    /** Locked external event id when present, else current external event id, else {@code ""}. */
    public static String effectiveExternalEventId(PaperTradeBet bet) {
        if (bet == null) {
            return "";
        }
        if (StringUtils.hasText(bet.getLockedExternalEventId())) {
            return bet.getLockedExternalEventId().trim();
        }
        if (StringUtils.hasText(bet.getExternalEventId())) {
            return bet.getExternalEventId().trim();
        }
        return "";
    }

    /** Locked source-feed event id when present, else last observed source-feed event id, else {@code ""}. */
    public static String effectiveSourceFeedEventId(PaperTradeBet bet) {
        if (bet == null) {
            return "";
        }
        if (StringUtils.hasText(bet.getLockedSourceFeedEventId())) {
            return bet.getLockedSourceFeedEventId().trim();
        }
        if (StringUtils.hasText(bet.getLastSourceFeedEventId())) {
            return bet.getLastSourceFeedEventId().trim();
        }
        return "";
    }

    /** Locked start-time ISO when present, else current start-time ISO, else {@code ""}. */
    public static String effectiveLockedStartTimeIso(PaperTradeBet bet) {
        if (bet == null) {
            return "";
        }
        if (StringUtils.hasText(bet.getLockedStartTimeIso())) {
            return bet.getLockedStartTimeIso().trim();
        }
        if (StringUtils.hasText(bet.getStartTimeIso())) {
            return bet.getStartTimeIso().trim();
        }
        return "";
    }
}
