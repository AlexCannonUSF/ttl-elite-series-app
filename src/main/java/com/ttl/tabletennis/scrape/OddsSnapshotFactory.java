package com.ttl.tabletennis.scrape;

import com.ttl.tabletennis.domain.OddsQuote;
import com.ttl.tabletennis.domain.OddsSnapshot;
import com.ttl.tabletennis.model.MatchOdds;
import com.ttl.tabletennis.util.NameUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Component
public class OddsSnapshotFactory {

    static final String SIDE_P1 = "P1";
    static final String SIDE_P2 = "P2";
    static final String MARKET_OPEN = "OPEN";
    static final String MARKET_SUSPENDED = "SUSPENDED";
    static final String MARKET_CLOSED = "CLOSED";
    static final String HARD_ROCK_SOURCE_ID = HardRockFeedClient.SOURCE.id();

    public boolean supportsOddsQuoteBackfill(OddsQuote oddsQuote) {
        return oddsQuote != null
                && isHardRockSource(oddsQuote.getSource())
                && StringUtils.hasText(firstNonBlank(oddsQuote.getPlayer1Normalized(), oddsQuote.getPlayer1Display()))
                && StringUtils.hasText(firstNonBlank(oddsQuote.getPlayer2Normalized(), oddsQuote.getPlayer2Display()));
    }

    public List<OddsSnapshot> fromMatchOddsEvent(IngestEvent<?> event, MatchOdds odds) {
        if (odds == null) {
            return List.of();
        }

        String sourceId = normalizeSourceId(event == null || event.source() == null ? null : event.source().id());
        // #125 — Prefer externalEventId (stable across prematch/live) over
        // sourceFeedEventId. For HardRock specifically, externalEventId is
        // the outer market id (numeric, present from prematch onward) and
        // sourceFeedEventId is the inner matchState's sr:match: id (only
        // appears once the score feed kicks in). Without this swap, the
        // same physical match produced two different trackedEventId SHA-256s
        // across the prematch→live transition — fragmenting every downstream
        // identity-keyed lookup (OddsSnapshotRepository dedupe,
        // SettlementEvidenceRecordRepository, SettlementAuditRecordRepository,
        // SettlementShadowAuditService, mirror clients keying via
        // OddsSnapshotFactory.trackedEventId).
        //
        // For other feeds (SofaScore, AIscore, BetsAPI, TtSeries) only
        // externalEventId is populated, so the change is a no-op there.
        String trackedEventId = trackedEventId(
                sourceId,
                firstNonBlank(safeTrim(odds.getExternalEventId()), safeTrim(odds.getSourceFeedEventId()), matchKey(
                        odds.getPlayerA(),
                        odds.getPlayerB(),
                        odds.getStartTimeIso(),
                        null,
                        null
                ))
        );
        String bookerEventId = firstNonBlank(safeTrim(odds.getExternalEventId()), safeTrim(odds.getSourceFeedEventId()));
        String matchKey = matchKey(odds.getPlayerA(), odds.getPlayerB(), odds.getStartTimeIso(), null, null);
        String marketState = marketState(odds);
        LocalDateTime observedAt = observedAt(event == null ? null : event.observedAt(), odds == null ? 0L : odds.getTimestamp(), null);
        String correlationId = event == null ? "" : event.correlationId();
        String rawPayloadRef = event == null ? "" : event.rawPayloadRef();

        List<OddsSnapshot> snapshots = new ArrayList<>(2);
        maybeAddSnapshot(snapshots, trackedEventId, bookerEventId, matchKey, sourceId, marketState,
                observedAt, correlationId, rawPayloadRef, SIDE_P1, odds.getOddsA());
        maybeAddSnapshot(snapshots, trackedEventId, bookerEventId, matchKey, sourceId, marketState,
                observedAt, correlationId, rawPayloadRef, SIDE_P2, odds.getOddsB());
        return snapshots;
    }

    public List<OddsSnapshot> fromOddsQuote(OddsQuote oddsQuote) {
        if (!supportsOddsQuoteBackfill(oddsQuote)) {
            return List.of();
        }

        String sourceId = normalizeSourceId(oddsQuote.getSource());
        String matchKey = matchKey(
                oddsQuote.getPlayer1Display(),
                oddsQuote.getPlayer2Display(),
                oddsQuote.getStartTimeIso(),
                oddsQuote.getPlayer1Normalized(),
                oddsQuote.getPlayer2Normalized()
        );
        String trackedEventId = trackedEventId(sourceId, matchKey);
        LocalDateTime observedAt = observedAt(null, oddsQuote.getQuoteTimestampMs(), oddsQuote.getScrapedAt());

        List<OddsSnapshot> snapshots = new ArrayList<>(2);
        maybeAddSnapshot(snapshots, trackedEventId, null, matchKey, sourceId, MARKET_OPEN,
                observedAt, safeTrim(oddsQuote.getCorrelationId()), "",
                SIDE_P1, oddsQuote.getDecimalOddsPlayer1());
        maybeAddSnapshot(snapshots, trackedEventId, null, matchKey, sourceId, MARKET_OPEN,
                observedAt, safeTrim(oddsQuote.getCorrelationId()), "",
                SIDE_P2, oddsQuote.getDecimalOddsPlayer2());
        return snapshots;
    }

    private void maybeAddSnapshot(List<OddsSnapshot> snapshots,
                                  String trackedEventId,
                                  String bookerEventId,
                                  String matchKey,
                                  String sourceId,
                                  String marketState,
                                  LocalDateTime observedAt,
                                  String correlationId,
                                  String rawPayloadRef,
                                  String side,
                                  double decimalOdds) {
        if (decimalOdds <= 1.0) {
            return;
        }

        OddsSnapshot snapshot = new OddsSnapshot();
        snapshot.setTrackedEventId(trackedEventId);
        snapshot.setBookerEventId(bookerEventId);
        snapshot.setMatchKey(matchKey);
        snapshot.setSide(side);
        snapshot.setPriceDecimal(decimalOdds);
        snapshot.setImpliedProb(1.0 / decimalOdds);
        snapshot.setMarketState(marketState);
        snapshot.setSourceId(sourceId);
        snapshot.setObservedAt(observedAt);
        snapshot.setCorrelationId(correlationId);
        snapshot.setRawPayloadRef(rawPayloadRef);
        snapshots.add(snapshot);
    }

    String normalizeSourceId(String source) {
        SourceId knownSource = SourceId.fromValue(source).orElse(null);
        if (knownSource != null) {
            return knownSource.id();
        }
        if (isHardRockSource(source)) {
            return HARD_ROCK_SOURCE_ID;
        }
        String normalized = safeTrim(source).toUpperCase(Locale.ROOT);
        if (normalized.length() <= 16) {
            return normalized;
        }
        return normalized.substring(0, 16);
    }

    boolean isHardRockSource(String source) {
        String normalized = safeTrim(source).toUpperCase(Locale.ROOT);
        return normalized.contains("HARD_ROCK") || HARD_ROCK_SOURCE_ID.equals(normalized);
    }

    String marketState(MatchOdds odds) {
        if (odds.isResulted() || odds.isMatchCompleted()) {
            return MARKET_CLOSED;
        }
        if (!odds.isDisplayed()) {
            return MARKET_SUSPENDED;
        }
        return MARKET_OPEN;
    }

    String trackedEventId(String sourceId, String identitySource) {
        return sha256Hex(firstNonBlank(sourceId, HARD_ROCK_SOURCE_ID) + "|" + firstNonBlank(identitySource, ""));
    }

    String matchKey(String player1Raw,
                    String player2Raw,
                    String startTimeIso,
                    String player1Normalized,
                    String player2Normalized) {
        String left = firstNonBlank(player1Normalized, NameUtils.normalizeForLookup(player1Raw));
        String right = firstNonBlank(player2Normalized, NameUtils.normalizeForLookup(player2Raw));
        String key = String.join("|", safeTrim(left), safeTrim(right), safeTrim(startTimeIso));
        if (key.length() <= 128) {
            return key;
        }
        return "mk:" + sha256Hex(key);
    }

    LocalDateTime observedAt(Instant observedAtInstant, long quoteTimestampMs, LocalDateTime fallback) {
        if (observedAtInstant != null) {
            return LocalDateTime.ofInstant(observedAtInstant, ZoneOffset.UTC);
        }
        if (quoteTimestampMs > 0L) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(quoteTimestampMs), ZoneOffset.UTC);
        }
        if (fallback != null) {
            return fallback;
        }
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for tracked event ids", e);
        }
    }
}
