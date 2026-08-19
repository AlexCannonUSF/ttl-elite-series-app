package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.TrackedMatchObservation;
import com.ttl.tabletennis.dto.LiveOddsRecommendationDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

import static com.ttl.tabletennis.service.papertrade.BetLockedIdentity.effectiveExternalEventId;
import static com.ttl.tabletennis.service.papertrade.BetLockedIdentity.effectiveLockedStartTimeIso;
import static com.ttl.tabletennis.service.papertrade.BetLockedIdentity.effectiveSourceFeedEventId;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.safeText;

/**
 * Bet identity lock + drift bookkeeping. Pins a bet's identity once a
 * strong external id is observed, then guards future row/observation
 * matches against drift (and records a drift attempt when the guards
 * reject a candidate).
 *
 * <p>Twenty-first §4 slice — the drift-tracking cluster. Six methods that
 * were called from 14+ unrelated places across observation handling,
 * score updates, and settlement application. Pulled together because
 * {@code rowMatchesLockedIdentity} / {@code observationMatchesLockedIdentity}
 * and the three {@code markIdentityDriftAttempt} overloads share the same
 * {@link BetLockedIdentity} accessor surface.
 *
 * <p>Behaviour verbatim from the original private helpers in
 * {@code PaperTradingService}. The mutators ({@link #lockBetIdentityIfEligible}
 * and {@link #markIdentityDriftAttempt}) write directly onto the bet entity;
 * the predicates are pure reads.
 */
public final class BetIdentityLockManager {

    private static final Logger log = LoggerFactory.getLogger(BetIdentityLockManager.class);

    /**
     * #116 — Observer hook so a Spring component
     * ({@link IdentityDriftMetrics}) can publish Micrometer counters
     * without coupling this utility class to the Spring container.
     * Defaults to a no-op so the manager works standalone in unit tests
     * that don't load a full ApplicationContext.
     */
    public interface Observer {
        default void onDriftAttempt(String reason) {}
        default void onFallbackRescued() {}
    }

    private static final Observer NO_OP_OBSERVER = new Observer() {};
    private static volatile Observer observer = NO_OP_OBSERVER;

    /**
     * Install an observer. Called once at boot from
     * {@link IdentityDriftMetrics#install()}. Pass {@code null} to reset
     * back to the no-op (used by tests that want to clear state between runs).
     */
    public static void setObserver(Observer next) {
        observer = next == null ? NO_OP_OBSERVER : next;
    }

    private BetIdentityLockManager() {
        // utility class — not instantiable
    }

    /**
     * Pin a bet's identity when a strong external id is now present.
     * Records the lock timestamp + the locked external/feed event ids +
     * the locked start time. Idempotent — re-locking sets nothing.
     *
     * @return {@code true} if any lock field changed (caller should persist).
     */
    public static boolean lockBetIdentityIfEligible(PaperTradeBet bet, LocalDateTime lockedAt) {
        if (bet == null) {
            return false;
        }
        boolean changed = false;
        String externalEventId = StringUtils.hasText(bet.getExternalEventId()) ? bet.getExternalEventId().trim() : null;
        String sourceFeedEventId = StringUtils.hasText(bet.getLastSourceFeedEventId()) ? bet.getLastSourceFeedEventId().trim() : null;
        String startTimeIso = StringUtils.hasText(bet.getStartTimeIso()) ? bet.getStartTimeIso().trim() : null;
        boolean hasStrongIdentity = StringUtils.hasText(externalEventId) || StringUtils.hasText(sourceFeedEventId);

        if (!hasStrongIdentity) {
            return false;
        }

        if (!bet.isIdentityLocked()) {
            bet.setIdentityLocked(true);
            bet.setIdentityLockedAt(lockedAt == null ? LocalDateTime.now() : lockedAt);
            changed = true;
        } else if (bet.getIdentityLockedAt() == null && lockedAt != null) {
            bet.setIdentityLockedAt(lockedAt);
            changed = true;
        }

        if (StringUtils.hasText(startTimeIso) && !StringUtils.hasText(bet.getLockedStartTimeIso())) {
            bet.setLockedStartTimeIso(startTimeIso);
            changed = true;
        }
        if (StringUtils.hasText(externalEventId) && !StringUtils.hasText(bet.getLockedExternalEventId())) {
            bet.setLockedExternalEventId(externalEventId);
            changed = true;
        }
        if (StringUtils.hasText(sourceFeedEventId) && !StringUtils.hasText(bet.getLockedSourceFeedEventId())) {
            bet.setLockedSourceFeedEventId(sourceFeedEventId);
            changed = true;
        }
        return changed;
    }

    /**
     * Does {@code row} match the bet's locked identity? Returns true when
     * the bet isn't locked (no constraint) or when external/feed event ids
     * align (or are blank on one side) and start times are compatible.
     *
     * <p>#114 (was bug #113): we no longer reject outright when both sides
     * publish IDs that disagree — the same physical match can carry distinct
     * IDs across feed families (e.g. Hard Rock's 19-digit market id vs the
     * BETRADAR_UF {@code sr:match:NNNN} id that Hard Rock's inner
     * {@code matchState} block relays from its upstream score provider).
     * When IDs disagree, we fall through to a player-pair + start-time
     * compatibility check — strict enough to reject genuinely-different
     * matches, loose enough to keep settlement alive across cross-feed
     * identity drift.
     */
    public static boolean rowMatchesLockedIdentity(PaperTradeBet bet, LiveOddsRecommendationDto row) {
        if (bet == null || row == null || !bet.isIdentityLocked()) {
            return true;
        }
        String candidateExternalEventId = StringUtils.hasText(row.externalEventId())
                ? row.externalEventId().trim()
                : MatchKeyBuilder.extractExternalEventId(row.source());
        String candidateSourceFeedEventId = StringUtils.hasText(row.sourceFeedEventId())
                ? row.sourceFeedEventId().trim()
                : "";
        String candidateStartTimeIso = StringUtils.hasText(row.startTimeIso())
                ? row.startTimeIso().trim()
                : "";
        return identityCompatibleWithLock(
                bet,
                candidateExternalEventId,
                candidateSourceFeedEventId,
                candidateStartTimeIso,
                row.player1Name(),
                row.player2Name());
    }

    /** Same shape as {@link #rowMatchesLockedIdentity}, but reads the candidate
     *  fields from a {@link TrackedMatchObservation} instead of a row DTO. */
    public static boolean observationMatchesLockedIdentity(PaperTradeBet bet, TrackedMatchObservation observation) {
        if (bet == null || observation == null || !bet.isIdentityLocked()) {
            return true;
        }
        String candidateExternalEventId = StringUtils.hasText(observation.getExternalEventId())
                ? observation.getExternalEventId().trim()
                : "";
        String candidateSourceFeedEventId = StringUtils.hasText(observation.getSourceFeedEventId())
                ? observation.getSourceFeedEventId().trim()
                : "";
        String candidateStartTimeIso = StringUtils.hasText(observation.getStartTimeIso())
                ? observation.getStartTimeIso().trim()
                : "";
        return identityCompatibleWithLock(
                bet,
                candidateExternalEventId,
                candidateSourceFeedEventId,
                candidateStartTimeIso,
                observation.getPlayer1Name(),
                observation.getPlayer2Name());
    }

    /**
     * Shared identity-compatibility predicate. Encapsulates the three-stage
     * lock check used by both {@link #rowMatchesLockedIdentity} and
     * {@link #observationMatchesLockedIdentity}:
     *
     * <ol>
     *   <li>Fast path: any of the locked IDs matches the candidate's
     *       corresponding ID (case-insensitive) → accept.</li>
     *   <li>ID-mismatch fallback (#114): if both sides publish IDs and they
     *       disagree, accept only when the player pair matches loosely
     *       <em>and</em> the start times are compatible (within the
     *       {@link BetIdentityMatcher#isCompatibleStartTime} drift window).
     *       Without this fallback, cross-feed identity drift starves
     *       settlement — every observation gets rejected and bets sit OPEN
     *       until the void timeout fires.</li>
     *   <li>Soft path: if no ID disagreement is observable (blank on one
     *       side), require start-time compatibility only. Preserves
     *       backward-compat behaviour for cases where the candidate hasn't
     *       acquired an ID yet.</li>
     * </ol>
     */
    private static boolean identityCompatibleWithLock(PaperTradeBet bet,
                                                       String candidateExternalEventId,
                                                       String candidateSourceFeedEventId,
                                                       String candidateStartTimeIso,
                                                       String candidatePlayer1,
                                                       String candidatePlayer2) {
        String lockedExternalEventId = effectiveExternalEventId(bet);
        String lockedSourceFeedEventId = effectiveSourceFeedEventId(bet);
        String lockedStartTimeIso = effectiveLockedStartTimeIso(bet);

        boolean externalIdMatches = StringUtils.hasText(lockedExternalEventId)
                && StringUtils.hasText(candidateExternalEventId)
                && lockedExternalEventId.equalsIgnoreCase(candidateExternalEventId);
        boolean sourceFeedIdMatches = StringUtils.hasText(lockedSourceFeedEventId)
                && StringUtils.hasText(candidateSourceFeedEventId)
                && lockedSourceFeedEventId.equalsIgnoreCase(candidateSourceFeedEventId);

        // Stage 1 — at least one ID matches outright. Fast accept.
        if (externalIdMatches || sourceFeedIdMatches) {
            return true;
        }

        boolean sourceFeedIdDisagrees = StringUtils.hasText(lockedSourceFeedEventId)
                && StringUtils.hasText(candidateSourceFeedEventId)
                && !lockedSourceFeedEventId.equalsIgnoreCase(candidateSourceFeedEventId);
        boolean externalIdDisagrees = StringUtils.hasText(lockedExternalEventId)
                && StringUtils.hasText(candidateExternalEventId)
                && !lockedExternalEventId.equalsIgnoreCase(candidateExternalEventId);

        // Stage 2 — IDs published on both sides disagree. Only accept if the
        // player pair + start time line up (cross-feed identity drift case).
        if (sourceFeedIdDisagrees || externalIdDisagrees) {
            boolean playersMatch = BetIdentityMatcher.isLoosePairNameMatch(bet, candidatePlayer1, candidatePlayer2);
            if (!playersMatch) {
                return false;
            }
            // Stricter time gate for the fallback: cross-feed observations for
            // the SAME match should arrive within minutes of each other, not
            // hours. The legacy {@link BetIdentityMatcher#isCompatibleStartTime}
            // uses a 720-min window that's appropriate for placement-time
            // drift but far too lenient for "is this the same match
            // identified under a different feed ID" — two real matches
            // between the same Polish TT pair can happen 4-6 hours apart on
            // the same day's schedule. Require within 60 minutes here.
            if (!isCrossFeedStartTimeClose(lockedStartTimeIso, candidateStartTimeIso, 60L)) {
                return false;
            }
            log.info("[paper] identity-lock fallback rescued cross-feed observation: betId={} "
                            + "lockedSourceFeedEventId={} candidateSourceFeedEventId={} "
                            + "lockedExternalEventId={} candidateExternalEventId={} "
                            + "players={} vs {}",
                    bet.getId(),
                    safeText(lockedSourceFeedEventId, ""),
                    safeText(candidateSourceFeedEventId, ""),
                    safeText(lockedExternalEventId, ""),
                    safeText(candidateExternalEventId, ""),
                    safeText(candidatePlayer1, ""),
                    safeText(candidatePlayer2, ""));
            observer.onFallbackRescued();
            return true;
        }

        // Stage 3 — no ID disagreement (blank on one side). Defer to start-time
        // compatibility when available; otherwise accept (preserves original
        // soft-path behaviour).
        if (StringUtils.hasText(lockedStartTimeIso) && StringUtils.hasText(candidateStartTimeIso)) {
            return BetIdentityMatcher.isCompatibleStartTime(lockedStartTimeIso, candidateStartTimeIso);
        }
        return true;
    }

    /**
     * Stricter start-time compatibility for the cross-feed-identity-drift
     * fallback. The legacy {@link BetIdentityMatcher#isCompatibleStartTime}
     * has a 720-min window which is too loose to distinguish "same physical
     * match identified under two feed IDs" (minutes apart) from "two real
     * matches between the same pair scheduled hours apart" (a normal TT
     * Elite Series scenario where Lukaszewski plays Smith at 16:30 and
     * Lukaszewski plays Kowalski at 21:00 — same first player, different
     * matches). Treats blank-on-either-side as compatible (we have nothing
     * to compare).
     */
    private static boolean isCrossFeedStartTimeClose(String lockedIso,
                                                       String candidateIso,
                                                       long maxDriftMinutes) {
        if (!StringUtils.hasText(lockedIso) || !StringUtils.hasText(candidateIso)) {
            return true;
        }
        java.util.Optional<LocalDateTime> a = com.ttl.tabletennis.service.papertrade.PaperTradingHelpers
                .parseStartDateTime(lockedIso);
        java.util.Optional<LocalDateTime> b = com.ttl.tabletennis.service.papertrade.PaperTradingHelpers
                .parseStartDateTime(candidateIso);
        if (a.isEmpty() || b.isEmpty()) {
            // Fall back to minute-bucket equality when one side is unparseable.
            return com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.startBucket(lockedIso)
                    .equals(com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.startBucket(candidateIso));
        }
        long diff = Math.abs(java.time.temporal.ChronoUnit.MINUTES.between(a.get(), b.get()));
        return diff <= maxDriftMinutes;
    }

    /** Row-shaped drift-attempt record. */
    public static void markIdentityDriftAttempt(PaperTradeBet bet,
                                                 LiveOddsRecommendationDto row,
                                                 LocalDateTime observedAt,
                                                 String reason) {
        if (bet == null || row == null || !bet.isIdentityLocked()) {
            return;
        }
        String candidateExternalEventId = StringUtils.hasText(row.externalEventId())
                ? row.externalEventId().trim()
                : MatchKeyBuilder.extractExternalEventId(row.source());
        String candidateSourceFeedEventId = StringUtils.hasText(row.sourceFeedEventId()) ? row.sourceFeedEventId().trim() : null;
        String candidateStartTimeIso = StringUtils.hasText(row.startTimeIso()) ? row.startTimeIso().trim() : null;
        markIdentityDriftAttempt(
                bet,
                candidateExternalEventId,
                candidateSourceFeedEventId,
                candidateStartTimeIso,
                observedAt,
                reason
        );
    }

    /** Observation-shaped drift-attempt record. */
    public static void markIdentityDriftAttempt(PaperTradeBet bet,
                                                 TrackedMatchObservation observation,
                                                 String reason) {
        if (bet == null || observation == null || !bet.isIdentityLocked()) {
            return;
        }
        markIdentityDriftAttempt(
                bet,
                observation.getExternalEventId(),
                observation.getSourceFeedEventId(),
                observation.getStartTimeIso(),
                observation.getObservedAt(),
                reason
        );
    }

    /** Canonical drift-attempt record: bumps the bet's drift counter +
     *  timestamp + warn-logs the conflicting identifiers. */
    public static void markIdentityDriftAttempt(PaperTradeBet bet,
                                                 String candidateExternalEventId,
                                                 String candidateSourceFeedEventId,
                                                 String candidateStartTimeIso,
                                                 LocalDateTime observedAt,
                                                 String reason) {
        if (bet == null || !bet.isIdentityLocked()) {
            return;
        }
        bet.setIdentityDriftCount(Math.max(0, bet.getIdentityDriftCount()) + 1);
        bet.setLastIdentityDriftAt(observedAt == null ? LocalDateTime.now() : observedAt);
        observer.onDriftAttempt(reason);
        log.warn(
                "[paper] identity drift blocked: betId={} reason={} lockedExternalEventId={} candidateExternalEventId={} lockedSourceFeedEventId={} candidateSourceFeedEventId={} lockedStartTimeIso={} candidateStartTimeIso={}",
                bet.getId(),
                reason,
                effectiveExternalEventId(bet),
                safeText(candidateExternalEventId, ""),
                effectiveSourceFeedEventId(bet),
                safeText(candidateSourceFeedEventId, ""),
                effectiveLockedStartTimeIso(bet),
                safeText(candidateStartTimeIso, "")
        );
    }
}
