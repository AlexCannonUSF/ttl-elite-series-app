package com.ttl.tabletennis.scrape;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ttl.tabletennis.domain.PaperTradeModelCall;
import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.dto.HardRockScoreStreamStatusDto;
import com.ttl.tabletennis.model.MatchOdds;
import com.ttl.tabletennis.repository.PaperTradeModelCallRepository;
import com.ttl.tabletennis.repository.PaperTradeSessionRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Maintains Hard Rock's event score subscription independently of its betting
 * market collection.
 *
 * <p>Hard Rock removes an event from {@code betSync.events} when betting
 * closes, often before the match has ended. Its own app keeps the locked event
 * subscribed on {@code /graphql-ws}; point, game, and terminal updates continue
 * there. This client mirrors that public app transport, persists the latest
 * state in memory across ordinary polling cycles, and publishes score events
 * without treating market disappearance as completion.
 */
@Component
public class HardRockScoreStreamClient {

    static final String SOURCE_TYPE = "HARD_ROCK_SCORE_STREAM";
    private static final String TOPIC = "score.observed";
    private static final double LIVE_CONFIDENCE = 0.98;
    private static final double TERMINAL_CONFIDENCE = 0.99;
    private static final Logger log = LoggerFactory.getLogger(HardRockScoreStreamClient.class);

    private final ObjectMapper objectMapper;
    private final IngestionBus ingestionBus;
    private final PaperTradeSessionRepository sessionRepository;
    private final PaperTradeModelCallRepository modelCallRepository;
    private final HttpClient httpClient;
    private final boolean enabled;
    private final URI socketUri;
    private final String channel;
    private final String locale;
    private final int maxTrackedEvents;
    private final Duration completedRetention;
    private final Duration staleRegistrationRetention;

    private final Map<String, TrackedSeed> tracked = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> eventState = new ConcurrentHashMap<>();
    private final Map<String, MatchOdds> latestRows = new ConcurrentHashMap<>();
    private final Map<String, String> lastPublishedFingerprint = new ConcurrentHashMap<>();
    private final Set<String> pendingSubscriptions = ConcurrentHashMap.newKeySet();
    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean loginAccepted = new AtomicBoolean(false);
    private final AtomicReference<Instant> connectedAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastMessageAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastScoreAt = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>("");
    private final AtomicLong reconnectCount = new AtomicLong();

    @Autowired
    public HardRockScoreStreamClient(
            ObjectMapper objectMapper,
            IngestionBus ingestionBus,
            PaperTradeSessionRepository sessionRepository,
            PaperTradeModelCallRepository modelCallRepository,
            @Value("${ttl.hardrock.scoreStream.enabled:true}") boolean enabled,
            @Value("${ttl.hardrock.scoreStream.url:wss://api.hardrocksportsbook.com/graphql-ws}") String socketUrl,
            @Value("${hr.channel:FLORIDA_ONLINE}") String channel,
            @Value("${hr.language:enus}") String language,
            @Value("${hr.region:us}") String region,
            @Value("${hr.segment:r.fl}") String segment,
            @Value("${ttl.hardrock.scoreStream.maxTrackedEvents:600}") int maxTrackedEvents,
            @Value("${ttl.hardrock.scoreStream.completedRetentionMinutes:720}") long completedRetentionMinutes,
            @Value("${ttl.hardrock.scoreStream.staleRegistrationHours:12}") long staleRegistrationHours
    ) {
        this(
                objectMapper,
                ingestionBus,
                sessionRepository,
                modelCallRepository,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                enabled,
                socketUrl,
                channel,
                language,
                region,
                segment,
                maxTrackedEvents,
                completedRetentionMinutes,
                staleRegistrationHours
        );
    }

    HardRockScoreStreamClient(ObjectMapper objectMapper,
                              IngestionBus ingestionBus,
                              PaperTradeSessionRepository sessionRepository,
                              PaperTradeModelCallRepository modelCallRepository,
                              HttpClient httpClient,
                              boolean enabled,
                              String socketUrl,
                              String channel,
                              String language,
                              String region,
                              String segment,
                              int maxTrackedEvents,
                              long completedRetentionMinutes,
                              long staleRegistrationHours) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.ingestionBus = ingestionBus;
        this.sessionRepository = sessionRepository;
        this.modelCallRepository = modelCallRepository;
        this.httpClient = httpClient == null ? HttpClient.newHttpClient() : httpClient;
        this.enabled = enabled;
        this.socketUri = URI.create(StringUtils.hasText(socketUrl)
                ? socketUrl.trim()
                : "wss://api.hardrocksportsbook.com/graphql-ws");
        this.channel = StringUtils.hasText(channel) ? channel.trim() : "FLORIDA_ONLINE";
        String normalizedLanguage = StringUtils.hasText(language) ? language.trim() : "enus";
        String normalizedRegion = StringUtils.hasText(region) ? region.trim() : "us";
        String normalizedSegment = StringUtils.hasText(segment) ? segment.trim().toLowerCase(Locale.ROOT) : "fl";
        if (normalizedSegment.startsWith("r.")) {
            normalizedSegment = normalizedSegment.substring(2);
        }
        this.locale = (normalizedLanguage.replace("-", "") + "-" + normalizedRegion + "-x-" + normalizedSegment)
                .toLowerCase(Locale.ROOT);
        this.maxTrackedEvents = Math.max(50, maxTrackedEvents);
        this.completedRetention = Duration.ofMinutes(Math.max(30L, completedRetentionMinutes));
        this.staleRegistrationRetention = Duration.ofHours(Math.max(2L, staleRegistrationHours));
    }

    @PostConstruct
    void start() {
        connectIfNeeded();
    }

    @PreDestroy
    void stop() {
        WebSocket active = socket.getAndSet(null);
        connected.set(false);
        loginAccepted.set(false);
        if (active != null) {
            try {
                active.sendClose(WebSocket.NORMAL_CLOSURE, "application shutdown");
            } catch (RuntimeException ignored) {
                // JVM shutdown is best-effort.
            }
        }
    }

    /** Register an event while its market row is still discoverable. */
    public void track(MatchOdds row) {
        if (!enabled || row == null || !StringUtils.hasText(row.getExternalEventId())) {
            return;
        }
        String eventId = sanitizeEventId(row.getExternalEventId());
        if (!StringUtils.hasText(eventId)) {
            return;
        }
        Instant now = Instant.now();
        tracked.compute(eventId, (ignored, existing) -> TrackedSeed.merge(existing, row, now));
        pendingSubscriptions.add(eventId);
        enforceTrackingLimit();
        flushSubscriptions();
    }

    public void trackEventIds(Collection<String> eventIds) {
        if (!enabled || eventIds == null) {
            return;
        }
        Instant now = Instant.now();
        for (String raw : eventIds) {
            String eventId = sanitizeEventId(raw);
            if (!StringUtils.hasText(eventId)) continue;
            tracked.putIfAbsent(eventId, TrackedSeed.placeholder(eventId, now));
            pendingSubscriptions.add(eventId);
        }
        enforceTrackingLimit();
        flushSubscriptions();
    }

    /** Latest durable score states, including terminal rows after markets close. */
    public List<MatchOdds> snapshots() {
        pruneExpired();
        return latestRows.values().stream()
                .map(HardRockScoreStreamClient::copy)
                .sorted(Comparator.comparingLong(MatchOdds::getTimestamp).reversed())
                .toList();
    }

    public List<MatchOdds> snapshotsForEventIds(Collection<String> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) return List.of();
        trackEventIds(eventIds);
        List<MatchOdds> rows = new ArrayList<>();
        for (String raw : eventIds) {
            MatchOdds row = latestRows.get(sanitizeEventId(raw));
            if (row != null) rows.add(copy(row));
        }
        rows.sort(Comparator.comparingLong(MatchOdds::getTimestamp).reversed());
        return List.copyOf(rows);
    }

    public HardRockScoreStreamStatusDto status() {
        int live = 0;
        int completed = 0;
        for (MatchOdds row : latestRows.values()) {
            if (row.isMatchCompleted() || row.isResulted()) completed++;
            else if (row.isLive()) live++;
        }
        return new HardRockScoreStreamStatusDto(
                enabled,
                connected.get() && loginAccepted.get(),
                tracked.size(),
                live,
                completed,
                connectedAt.get(),
                lastMessageAt.get(),
                lastScoreAt.get(),
                reconnectCount.get(),
                blankToNull(lastError.get())
        );
    }

    @Scheduled(initialDelayString = "${ttl.hardrock.scoreStream.initialDelayMs:750}",
            fixedDelayString = "${ttl.hardrock.scoreStream.reconnectFixedDelayMs:3000}")
    void maintainConnection() {
        if (!enabled) return;
        pruneExpired();
        connectIfNeeded();
        flushSubscriptions();
    }

    @Scheduled(initialDelayString = "${ttl.hardrock.scoreStream.keepAliveMs:15000}",
            fixedDelayString = "${ttl.hardrock.scoreStream.keepAliveMs:15000}")
    void keepAlive() {
        WebSocket active = socket.get();
        if (!enabled || active == null || !connected.get()) return;
        send(active, Map.of("KeepAlive", Map.of("reqId", 1)));
    }

    /**
     * Rehydrate subscriptions after an IntelliJ rerun. Model calls are the
     * durable list of matches the user was watching, including skipped picks.
     */
    @Scheduled(initialDelayString = "${ttl.hardrock.scoreStream.recoveryInitialDelayMs:1500}",
            fixedDelayString = "${ttl.hardrock.scoreStream.recoveryFixedDelayMs:60000}")
    void recoverActiveSessionEvents() {
        if (!enabled || sessionRepository == null || modelCallRepository == null) return;
        try {
            Optional<PaperTradeSession> active = sessionRepository.findFirstByStatusOrderByIdDesc(PaperTradeSession.STATUS_ACTIVE);
            if (active.isEmpty()) return;
            Instant now = Instant.now();
            for (PaperTradeModelCall call : modelCallRepository.findBySessionIdOrderByCapturedAtDesc(active.get().getId())) {
                if (!StringUtils.hasText(call.getExternalEventId()) || !insideRecoveryWindow(call, now)) continue;
                MatchOdds seed = new MatchOdds(
                        call.getPlayer1Name(),
                        call.getPlayer2Name(),
                        2.0,
                        2.0,
                        call.getEventName(),
                        call.getCompetitionName(),
                        false,
                        call.getStartTimeIso(),
                        SOURCE_TYPE + ":RECOVERY|event=" + call.getExternalEventId()
                );
                seed.setExternalEventId(call.getExternalEventId());
                track(seed);
            }
        } catch (RuntimeException ex) {
            log.debug("[hardrock-score-stream] active-session recovery deferred: {}", ex.toString());
        }
    }

    private boolean insideRecoveryWindow(PaperTradeModelCall call, Instant now) {
        Optional<Instant> start = parseInstant(call.getStartTimeIso());
        if (start.isEmpty()) return true;
        return !start.get().isBefore(now.minus(staleRegistrationRetention))
                && !start.get().isAfter(now.plus(Duration.ofHours(18)));
    }

    private void connectIfNeeded() {
        if (!enabled || connected.get() || !connecting.compareAndSet(false, true)) return;
        if (connectedAt.get() != null) reconnectCount.incrementAndGet();
        httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(12))
                .buildAsync(socketUri, new ScoreSocketListener())
                .whenComplete((opened, error) -> {
                    connecting.set(false);
                    if (error != null) {
                        markDisconnected(error);
                        return;
                    }
                    socket.set(opened);
                });
    }

    private void flushSubscriptions() {
        WebSocket active = socket.get();
        if (!enabled || active == null || !connected.get() || !loginAccepted.get() || pendingSubscriptions.isEmpty()) {
            return;
        }
        List<String> ids = pendingSubscriptions.stream().sorted().limit(100).toList();
        if (ids.isEmpty()) return;
        Map<String, Object> subscribe = new LinkedHashMap<>();
        subscribe.put("ids", ids);
        subscribe.put("descendants", true);
        subscribe.put("marketTypes", List.of("EXCLUDE_MARKETS_DUMMY_TYPE"));
        if (send(active, Map.of("SubscriptionRequest", Map.of("subscribe", subscribe)))) {
            pendingSubscriptions.removeAll(ids);
        }
    }

    private boolean send(WebSocket active, Object payload) {
        try {
            active.sendText(objectMapper.writeValueAsString(payload), true);
            return true;
        } catch (Exception ex) {
            markDisconnected(ex);
            return false;
        }
    }

    void acceptMessage(String message) {
        if (!StringUtils.hasText(message)) return;
        try {
            JsonNode root = objectMapper.readTree(message);
            lastMessageAt.set(Instant.now());
            JsonNode response = root.path("Response");
            if (response.isObject()) {
                if ("ok".equalsIgnoreCase(response.path("status").asText())
                        && "0".equals(response.path("reqId").asText())) {
                    loginAccepted.set(true);
                    lastError.set("");
                    pendingSubscriptions.addAll(tracked.keySet());
                    flushSubscriptions();
                }
                return;
            }

            JsonNode patch = root.path("SubscriptionResponse").path("data");
            if (!patch.isObject()) patch = root.path("Event");
            if (!patch.isObject()) return;
            String eventId = sanitizeEventId(patch.path("id").asText());
            if (!StringUtils.hasText(eventId)) return;
            JsonNode eventPatch = patch;

            ObjectNode merged = eventState.compute(eventId, (ignored, existing) -> {
                ObjectNode target = existing == null ? objectMapper.createObjectNode() : existing.deepCopy();
                deepMerge(target, eventPatch);
                return target;
            });
            MatchOdds row = toMatchOdds(eventId, merged);
            if (row == null) return;
            latestRows.put(eventId, row);
            lastScoreAt.set(Instant.now());
            String fingerprint = fingerprint(row);
            if (!fingerprint.equals(lastPublishedFingerprint.put(eventId, fingerprint)) && ingestionBus != null) {
                ingestionBus.publish(new IngestEvent<>(
                        SourceId.HR_TGT,
                        TOPIC,
                        Instant.ofEpochMilli(row.getTimestamp()),
                        row.getSourceConfidence(),
                        "hr-score-stream-" + eventId,
                        "",
                        copy(row)
                ));
            }
        } catch (Exception ex) {
            lastError.set("message parse: " + safeMessage(ex));
            log.debug("[hardrock-score-stream] ignored malformed message: {}", ex.toString());
        }
    }

    private MatchOdds toMatchOdds(String eventId, ObjectNode state) {
        TrackedSeed seed = tracked.get(eventId);
        String[] participants = participants(state);
        String playerA = firstText(seed == null ? null : seed.playerA(), participants[0]);
        String playerB = firstText(seed == null ? null : seed.playerB(), participants[1]);
        if (!StringUtils.hasText(playerA) || !StringUtils.hasText(playerB)) return null;

        JsonNode matchState = state.path("simpleMatchState");
        if (!matchState.isObject()) matchState = state.path("matchState");
        int gamesA = readInt(matchState, "gamesA", "setsA");
        int gamesB = readInt(matchState, "gamesB", "setsB");
        int pointsA = readInt(matchState, "pointsInCurrentGameA", "pointsA");
        int pointsB = readInt(matchState, "pointsInCurrentGameB", "pointsB");
        boolean preMatch = matchState.path("preMatch").asBoolean(state.path("preMatch").asBoolean(false));
        boolean completed = matchState.path("matchCompleted").asBoolean(false)
                || state.path("matchCompleted").asBoolean(false)
                || state.path("resulted").asBoolean(false)
                || isTerminalState(state.path("state").asText());
        boolean inplay = !completed && (state.path("inplay").asBoolean(false) || !preMatch);
        String score = score(gamesA, gamesB, pointsA, pointsB, preMatch, completed);
        String phase = phase(gamesA, gamesB, score, preMatch, completed, inplay);
        String scoreDetail = scoreDetail(matchState.path("gameScoreInGameN"));

        String eventName = firstText(seed == null ? null : seed.eventName(), state.path("name").asText(), playerA + " vs. " + playerB);
        String competitionName = firstText(seed == null ? null : seed.competitionName(), state.path("compName").asText(), "Table Tennis");
        String startTime = firstText(seed == null ? null : seed.startTimeIso(), eventTimeIso(state.path("eventTime")));
        double oddsA = seed == null ? 2.0 : seed.oddsA();
        double oddsB = seed == null ? 2.0 : seed.oddsB();

        MatchOdds row = new MatchOdds(
                playerA,
                playerB,
                oddsA > 1.0 ? oddsA : 2.0,
                oddsB > 1.0 ? oddsB : 2.0,
                eventName,
                competitionName,
                inplay,
                startTime,
                SOURCE_TYPE + ":" + channel + "|event=" + eventId,
                score,
                phase
        );
        row.setExternalEventId(eventId);
        row.setSourceType(SOURCE_TYPE);
        row.setSourceConfidence(completed ? TERMINAL_CONFIDENCE : LIVE_CONFIDENCE);
        row.setDisplayed(state.path("displayed").asBoolean(true));
        row.setResulted(completed);
        row.setMatchCompleted(completed);
        row.setSourceFeedCode(matchState.path("sourceFeedCode").asText(null));
        row.setSourceFeedEventId(matchState.path("sourceFeedEventId").asText(null));
        row.setScoreDetail(scoreDetail);
        return row;
    }

    private static String[] participants(JsonNode state) {
        JsonNode participants = state.path("participants");
        if (!participants.isArray() || participants.size() < 2) return new String[]{"", ""};
        return new String[]{participants.get(0).path("name").asText(""), participants.get(1).path("name").asText("")};
    }

    private static String score(int gamesA,
                                int gamesB,
                                int pointsA,
                                int pointsB,
                                boolean preMatch,
                                boolean completed) {
        if (preMatch && gamesA <= 0 && gamesB <= 0 && pointsA <= 0 && pointsB <= 0) return "";
        if (gamesA < 0 || gamesB < 0) return "";
        if (!completed && pointsA >= 0 && pointsB >= 0) {
            return gamesA + "-" + gamesB + " (" + pointsA + "-" + pointsB + ")";
        }
        return gamesA + "-" + gamesB;
    }

    private static String phase(int gamesA,
                                int gamesB,
                                String score,
                                boolean preMatch,
                                boolean completed,
                                boolean inplay) {
        if (completed) return "FINISHED";
        if (preMatch && !StringUtils.hasText(score)) return "UPCOMING";
        if (!inplay && !StringUtils.hasText(score)) return "UPCOMING";
        int totalGames = Math.max(0, gamesA) + Math.max(0, gamesB);
        if (totalGames >= 4) return "LIVE_LATE";
        if (totalGames >= 2) return "LIVE_MID";
        return "LIVE_EARLY";
    }

    private static String scoreDetail(JsonNode gameScores) {
        if (!gameScores.isObject()) return "";
        Map<Integer, String> ordered = new TreeMap<>();
        gameScores.fields().forEachRemaining(entry -> {
            try {
                int game = Integer.parseInt(entry.getKey());
                JsonNode value = entry.getValue();
                int a = readInt(value, "A", "a");
                int b = readInt(value, "B", "b");
                if (a >= 0 && b >= 0) ordered.put(game, a + "-" + b);
            } catch (NumberFormatException ignored) {
                // Only numbered table-tennis games belong in score detail.
            }
        });
        return String.join(", ", ordered.values());
    }

    private static int readInt(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) return -1;
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isInt() || value.isLong()) return value.asInt();
            if (value.isTextual()) {
                try {
                    return Integer.parseInt(value.asText().trim());
                } catch (NumberFormatException ignored) {
                    // try the next alias
                }
            }
        }
        return -1;
    }

    private static void deepMerge(ObjectNode target, JsonNode patch) {
        patch.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            JsonNode current = target.get(key);
            if (value.isObject() && current != null && current.isObject()) {
                deepMerge((ObjectNode) current, value);
            } else {
                target.set(key, value.deepCopy());
            }
        });
    }

    private void pruneExpired() {
        Instant now = Instant.now();
        for (Map.Entry<String, TrackedSeed> entry : tracked.entrySet()) {
            String id = entry.getKey();
            MatchOdds latest = latestRows.get(id);
            Instant latestAt = latest == null ? entry.getValue().registeredAt() : Instant.ofEpochMilli(latest.getTimestamp());
            Duration retention = latest != null && (latest.isMatchCompleted() || latest.isResulted())
                    ? completedRetention
                    : staleRegistrationRetention;
            if (latestAt.plus(retention).isBefore(now)) {
                tracked.remove(id);
                latestRows.remove(id);
                eventState.remove(id);
                lastPublishedFingerprint.remove(id);
                pendingSubscriptions.remove(id);
            }
        }
    }

    private void enforceTrackingLimit() {
        int overflow = tracked.size() - maxTrackedEvents;
        if (overflow <= 0) return;
        tracked.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.comparing(TrackedSeed::registeredAt)))
                .limit(overflow)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(id -> {
                    tracked.remove(id);
                    latestRows.remove(id);
                    eventState.remove(id);
                    lastPublishedFingerprint.remove(id);
                    pendingSubscriptions.remove(id);
                });
    }

    private void markDisconnected(Throwable error) {
        connected.set(false);
        loginAccepted.set(false);
        socket.set(null);
        if (error != null) lastError.set(safeMessage(error));
    }

    private static boolean isTerminalState(String state) {
        if (!StringUtils.hasText(state)) return false;
        String normalized = state.trim().toUpperCase(Locale.ROOT);
        return normalized.contains("COMPLETE") || normalized.contains("FINISH")
                || normalized.contains("RESULT") || normalized.contains("SETTLED")
                || normalized.equals("CLOSED");
    }

    private static String fingerprint(MatchOdds row) {
        return String.join("|",
                safe(row.getLiveScore()),
                safe(row.getMatchPhase()),
                safe(row.getScoreDetail()),
                Boolean.toString(row.isLive()),
                Boolean.toString(row.isResulted()),
                Boolean.toString(row.isMatchCompleted()));
    }

    private static MatchOdds copy(MatchOdds source) {
        MatchOdds copy = new MatchOdds(
                source.getPlayerA(), source.getPlayerB(), source.getOddsA(), source.getOddsB(),
                source.getEventName(), source.getCompetitionName(), source.isLive(), source.getStartTimeIso(),
                source.getSource(), source.getLiveScore(), source.getMatchPhase());
        copy.setExternalEventId(source.getExternalEventId());
        copy.setTimestamp(source.getTimestamp());
        copy.setSourceType(source.getSourceType());
        copy.setSourceConfidence(source.getSourceConfidence());
        copy.setDisplayed(source.isDisplayed());
        copy.setResulted(source.isResulted());
        copy.setMatchCompleted(source.isMatchCompleted());
        copy.setSourceFeedCode(source.getSourceFeedCode());
        copy.setSourceFeedEventId(source.getSourceFeedEventId());
        copy.setScoreDetail(source.getScoreDetail());
        return copy;
    }

    private static String eventTimeIso(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return "";
        try {
            long epochMs = value.isNumber() ? value.asLong() : Long.parseLong(value.asText().trim());
            return epochMs > 0L ? Instant.ofEpochMilli(epochMs).toString() : "";
        } catch (RuntimeException ignored) {
            return value.asText("");
        }
    }

    private static Optional<Instant> parseInstant(String value) {
        if (!StringUtils.hasText(value)) return Optional.empty();
        String raw = value.trim();
        try {
            return Optional.of(Instant.parse(raw));
        } catch (DateTimeParseException ignored) {
            // try offset/local timestamps used by legacy rows
        }
        try {
            return Optional.of(OffsetDateTime.parse(raw).toInstant());
        } catch (DateTimeParseException ignored) {
            // try local application time
        }
        try {
            return Optional.of(LocalDateTime.parse(raw).atZone(ZoneId.systemDefault()).toInstant());
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    private static String sanitizeEventId(String value) {
        return StringUtils.hasText(value) ? value.trim().replaceAll("[^A-Za-z0-9:_-]", "") : "";
    }

    private static String firstText(String... values) {
        if (values == null) return "";
        for (String value : values) if (StringUtils.hasText(value)) return value.trim();
        return "";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown error";
        String message = error.getMessage();
        return StringUtils.hasText(message) ? message.trim() : error.getClass().getSimpleName();
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private final class ScoreSocketListener implements WebSocket.Listener {
        private final StringBuilder fragments = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            socket.set(webSocket);
            connected.set(true);
            loginAccepted.set(false);
            connecting.set(false);
            connectedAt.set(Instant.now());
            lastError.set("");
            webSocket.request(1);
            send(webSocket, Map.of("SportsbookLoginRequest", Map.of(
                    "application", "sportsbook",
                    "channel", channel,
                    "locale", locale
            )));
            log.info("[hardrock-score-stream] connected; channel={} tracked={}", channel, tracked.size());
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            synchronized (fragments) {
                fragments.append(data);
                if (last) {
                    String complete = fragments.toString();
                    fragments.setLength(0);
                    acceptMessage(complete);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            markDisconnected(new IllegalStateException("closed " + statusCode + (StringUtils.hasText(reason) ? ": " + reason : "")));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            markDisconnected(error);
        }
    }

    private record TrackedSeed(String eventId,
                               String playerA,
                               String playerB,
                               double oddsA,
                               double oddsB,
                               String eventName,
                               String competitionName,
                               String startTimeIso,
                               Instant registeredAt) {

        private static TrackedSeed placeholder(String eventId, Instant now) {
            return new TrackedSeed(eventId, "", "", 2.0, 2.0, "", "", "", now);
        }

        private static TrackedSeed merge(TrackedSeed existing, MatchOdds row, Instant now) {
            if (existing == null) {
                return new TrackedSeed(
                        row.getExternalEventId(), safe(row.getPlayerA()), safe(row.getPlayerB()),
                        row.getOddsA(), row.getOddsB(), safe(row.getEventName()), safe(row.getCompetitionName()),
                        safe(row.getStartTimeIso()), now);
            }
            return new TrackedSeed(
                    existing.eventId,
                    firstText(row.getPlayerA(), existing.playerA),
                    firstText(row.getPlayerB(), existing.playerB),
                    row.getOddsA() > 1.0 ? row.getOddsA() : existing.oddsA,
                    row.getOddsB() > 1.0 ? row.getOddsB() : existing.oddsB,
                    firstText(row.getEventName(), existing.eventName),
                    firstText(row.getCompetitionName(), existing.competitionName),
                    firstText(row.getStartTimeIso(), existing.startTimeIso),
                    existing.registeredAt
            );
        }
    }
}
