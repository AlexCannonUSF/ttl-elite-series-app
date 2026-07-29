package com.ttl.tabletennis.service.papertrade;

import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * Shared pure-function utilities used across the papertrade package.
 *
 * <p>Per the §4 decomposition plan, helpers used by more than one extracted
 * service live here as package-private statics. PaperTradingService and
 * every new {@code papertrade.*} service should
 * {@code import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.*;}
 * so call sites stay terse.
 *
 * <p>Everything here is stateless and reproducible — no clock, no random,
 * no I/O. Adding anything that breaks those constraints means the helper
 * doesn't belong here; it belongs in the service that owns the state.
 */
public final class PaperTradingHelpers {

    /** Tolerance used by exposure / Kelly / bankroll arithmetic to treat
     *  near-zero values as exactly zero. Match the value previously in
     *  {@code PaperTradingService}. */
    public static final double EPS = 1e-9;

    private PaperTradingHelpers() {
        // utility class — not instantiable
    }

    public static int clamp(int value, int lo, int hi) {
        return Math.min(hi, Math.max(lo, value));
    }

    public static double clamp(double value, double lo, double hi) {
        return Math.min(hi, Math.max(lo, value));
    }

    public static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    public static double valueOrZero(Double value) {
        return value == null ? 0.0 : value;
    }

    /** Trim non-blank value, or return the fallback. Used to coerce nullable
     *  feed-derived strings into a stable, non-blank shape. */
    public static String safeText(String value, String fallback) {
        if (StringUtils.hasText(value)) {
            return value.trim();
        }
        return fallback;
    }

    public static String normalizeTrigger(String trigger) {
        if (!StringUtils.hasText(trigger)) {
            return "unknown trigger";
        }
        return trigger.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Match-phase classifier: true for phases that signal a finished or
     * about-to-be-settled match. Drives the settlement-gate logic in
     * placement, settlement, and the score-winner resolver.
     */
    public static boolean isFinishedPhase(String phaseRaw) {
        if (!StringUtils.hasText(phaseRaw)) {
            return false;
        }
        String phase = phaseRaw.trim().toUpperCase(Locale.ROOT);
        return phase.contains("FINISH")
                || phase.contains("FINAL")
                || phase.contains("ENDED")
                || phase.contains("CLOSED")
                || phase.contains("SETTLED")
                || phase.contains("RESULT")
                || phase.contains("COMPLETE");
    }

    /**
     * Match-phase classifier: true for late-mid or later phases (deciding
     * sets, finishing, settled). Strictly broader than {@link #isFinishedPhase(String)} —
     * any finished phase also matches here.
     */
    public static boolean isLateLikePhase(String phaseRaw) {
        if (!StringUtils.hasText(phaseRaw)) {
            return false;
        }
        String phase = phaseRaw.trim().toUpperCase(Locale.ROOT);
        return phase.contains("LIVE_LATE")
                || phase.contains("LIVE_MID")
                || phase.contains("FINISH")
                || phase.contains("FINAL")
                || phase.contains("SETTLED")
                || phase.contains("COMPLETE")
                || phase.contains("RESULT")
                || phase.contains("END");
    }

    private static final DateTimeFormatter MINUTE_BUCKET_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    /**
     * Best-effort ISO-8601 timestamp parser used by the placement / settlement
     * paths. Tries {@code OffsetDateTime}, {@code Instant}, {@code LocalDateTime}
     * with both {@code T} and space separators, and finally a date-only
     * fallback that buckets to end-of-day. Returns {@link Optional#empty()}
     * when nothing parses — callers treat that as "unknown start".
     */
    public static Optional<LocalDateTime> parseStartDateTime(String startTimeIso) {
        if (!StringUtils.hasText(startTimeIso)) {
            return Optional.empty();
        }
        String v = startTimeIso.trim();
        try {
            return Optional.of(OffsetDateTime.parse(v).toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
        } catch (Exception ignore) {
            // continue
        }
        try {
            return Optional.of(Instant.parse(v).atZone(ZoneId.systemDefault()).toLocalDateTime());
        } catch (Exception ignore) {
            // continue
        }
        String localLike = (v.contains(" ") && !v.contains("T"))
                ? v.replace(' ', 'T')
                : v;
        try {
            return Optional.of(LocalDateTime.parse(localLike));
        } catch (Exception ignore) {
            // continue
        }
        try {
            if (v.length() >= 10) {
                LocalDate d = LocalDate.parse(v.substring(0, 10));
                return Optional.of(d.plusDays(1).atStartOfDay().minusSeconds(1));
            }
        } catch (Exception ignore) {
            // continue
        }
        return Optional.empty();
    }

    /**
     * Bucket an ISO start time to its minute key {@code yyyy-MM-ddTHH:mm}.
     * Falls back to a truncated + normalised raw key when the time can't
     * parse — keeps the bucket stable across feeds with slightly different
     * precisions.
     */
    public static String startBucket(String startTimeIso) {
        Optional<LocalDateTime> parsed = parseStartDateTime(startTimeIso);
        if (parsed.isPresent()) {
            return parsed.get().withSecond(0).withNano(0).format(MINUTE_BUCKET_FORMATTER);
        }
        if (!StringUtils.hasText(startTimeIso)) {
            return "na";
        }
        String raw = startTimeIso.trim();
        if (raw.length() >= 16) {
            raw = raw.substring(0, 16);
        }
        return normalizeKey(raw);
    }

    /**
     * Lowercase + ASCII-fold + collapse to dash-separated tokens. Used as a
     * deterministic key for matchup / side / event identifiers across feeds.
     */
    public static String normalizeKey(String value) {
        if (!StringUtils.hasText(value)) {
            return "na";
        }
        return value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }
}
