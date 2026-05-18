package com.ttl.tabletennis.scrape;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Component
public class SofaScoreFeedClient implements FeedClient<MirrorObservationPayload> {

    static final SourceId SOURCE = SourceId.SOFASCORE;
    private static final Logger log = LoggerFactory.getLogger(SofaScoreFeedClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOPIC = "score.observed";

    private final HttpClient httpClient;
    private final IngestionBus ingestionBus;
    private final OddsSnapshotFactory oddsSnapshotFactory;
    private final FeedHealthTracker healthTracker = new FeedHealthTracker();
    private final boolean enabled;
    private final String apiBaseUrl;
    private final String liveEventsPath;
    private final String eventPathTemplate;
    private final String incidentsPathTemplate;
    private final String refererUrl;
    private final String userAgent;
    private final Duration requestTimeout;
    private final double defaultConfidence;
    private final double completionConfidence;

    @Autowired
    public SofaScoreFeedClient(IngestionBus ingestionBus,
                               OddsSnapshotFactory oddsSnapshotFactory,
                               @Value("${ttl.sofascore.enabled:false}") boolean enabled,
                               @Value("${ttl.sofascore.apiBaseUrl:https://www.sofascore.com/api/v1}") String apiBaseUrl,
                               @Value("${ttl.sofascore.liveEventsPath:/sport/table-tennis/events/live}") String liveEventsPath,
                               @Value("${ttl.sofascore.eventPathTemplate:/event/%s}") String eventPathTemplate,
                               @Value("${ttl.sofascore.incidentsPathTemplate:/event/%s/incidents}") String incidentsPathTemplate,
                               @Value("${ttl.sofascore.refererUrl:https://www.sofascore.com/table-tennis}") String refererUrl,
                               @Value("${ttl.sofascore.userAgent:Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36}") String userAgent,
                               @Value("${ttl.sofascore.requestTimeoutMs:5000}") long requestTimeoutMs,
                               @Value("${ttl.sofascore.defaultConfidence:0.78}") double defaultConfidence,
                               @Value("${ttl.sofascore.completionConfidence:0.90}") double completionConfidence) {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                ingestionBus,
                oddsSnapshotFactory,
                enabled,
                apiBaseUrl,
                liveEventsPath,
                eventPathTemplate,
                incidentsPathTemplate,
                refererUrl,
                userAgent,
                requestTimeoutMs,
                defaultConfidence,
                completionConfidence
        );
    }

    SofaScoreFeedClient(HttpClient httpClient,
                        IngestionBus ingestionBus,
                        OddsSnapshotFactory oddsSnapshotFactory,
                        boolean enabled,
                        String apiBaseUrl,
                        String liveEventsPath,
                        String eventPathTemplate,
                        String incidentsPathTemplate,
                        String refererUrl,
                        String userAgent,
                        long requestTimeoutMs,
                        double defaultConfidence,
                        double completionConfidence) {
        this.httpClient = httpClient;
        this.ingestionBus = ingestionBus;
        this.oddsSnapshotFactory = oddsSnapshotFactory;
        this.enabled = enabled;
        this.apiBaseUrl = trimTrailingSlash(apiBaseUrl);
        this.liveEventsPath = ensureLeadingSlash(liveEventsPath);
        this.eventPathTemplate = ensureLeadingSlash(eventPathTemplate);
        this.incidentsPathTemplate = ensureLeadingSlash(incidentsPathTemplate);
        this.refererUrl = refererUrl == null ? "" : refererUrl.trim();
        this.userAgent = userAgent == null ? "" : userAgent.trim();
        this.requestTimeout = Duration.ofMillis(Math.max(1000L, requestTimeoutMs));
        this.defaultConfidence = clamp(defaultConfidence, 0.0, 1.0, 0.78);
        this.completionConfidence = clamp(completionConfidence, 0.0, 1.0, 0.90);
    }

    @Override
    public SourceId source() {
        return SOURCE;
    }

    @Override
    public List<IngestEvent<MirrorObservationPayload>> pullOnce(PullContext ctx) {
        if (!enabled) {
            return List.of();
        }

        Instant startedAt = healthTracker.onPullStart();
        try {
            List<MirrorObservationPayload> payloads = fetchMirrorPayloads(ctx);
            Instant observedAt = Instant.now();
            String correlationId = ctx == null ? "" : ctx.correlationId();
            List<IngestEvent<MirrorObservationPayload>> events = payloads.stream()
                    .map(payload -> new IngestEvent<>(
                            source(),
                            TOPIC,
                            observedAt,
                            payload.completionSignal() ? completionConfidence : defaultConfidence,
                            correlationId,
                            "",
                            payload
                    ))
                    .toList();
            ingestionBus.publishAll(events);
            healthTracker.onPullSuccess(startedAt);
            return events;
        } catch (RuntimeException exception) {
            healthTracker.onPullFailure(startedAt, exception);
            throw exception;
        }
    }

    @Override
    public FeedHealth currentHealth() {
        return healthTracker.snapshot(source());
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.SCORES, Capability.POINT_BY_POINT, Capability.RESULTS);
    }

    public List<MirrorObservationPayload> fetchMirrorPayloads(PullContext ctx) {
        String fixtureId = firstAttribute(ctx, "fixtureId", "eventId", "sofascoreEventId");
        if (StringUtils.hasText(fixtureId)) {
            return fetchTargetedPayloads(ctx, fixtureId.trim());
        }
        return fetchLivePayloads(ctx);
    }

    private List<MirrorObservationPayload> fetchLivePayloads(PullContext ctx) {
        JsonNode root = readJson(buildUrl(liveEventsPath), false).orElse(MAPPER.createObjectNode());
        List<JsonNode> events = extractEvents(root);
        if (events.isEmpty()) {
            return List.of();
        }

        List<MirrorObservationPayload> payloads = new ArrayList<>();
        for (JsonNode eventNode : events) {
            buildPayload(eventNode, null, ctx).ifPresent(payloads::add);
        }
        return payloads;
    }

    private List<MirrorObservationPayload> fetchTargetedPayloads(PullContext ctx, String fixtureId) {
        String eventPath = formatPath(eventPathTemplate, fixtureId);
        Optional<JsonNode> eventRoot = readJson(buildUrl(eventPath), true);
        if (eventRoot.isEmpty()) {
            return List.of();
        }

        JsonNode eventNode = unwrapEvent(eventRoot.get());
        JsonNode incidentsNode = readJson(buildUrl(formatPath(incidentsPathTemplate, fixtureId)), true)
                .map(this::extractIncidents)
                .orElse(null);
        return buildPayload(eventNode, incidentsNode, ctx)
                .map(List::of)
                .orElse(List.of());
    }

    private Optional<MirrorObservationPayload> buildPayload(JsonNode eventNode,
                                                            JsonNode incidentsNode,
                                                            PullContext ctx) {
        if (eventNode == null || eventNode.isMissingNode() || eventNode.isNull()) {
            return Optional.empty();
        }

        String mirrorEventId = firstText(eventNode, "/id", "/eventId");
        String player1Name = firstText(
                eventNode,
                "/homeTeam/name",
                "/homeCompetitor/name",
                "/player1/name",
                "/participants/0/name"
        );
        String player2Name = firstText(
                eventNode,
                "/awayTeam/name",
                "/awayCompetitor/name",
                "/player2/name",
                "/participants/1/name"
        );
        String competitionName = firstText(
                eventNode,
                "/tournament/name",
                "/competition/name",
                "/season/name",
                "/sportEvent/tournament/name"
        );

        if (!StringUtils.hasText(player1Name) || !StringUtils.hasText(player2Name)) {
            return Optional.empty();
        }

        String startTimeIso = startTimeIso(eventNode);
        String trackedEventId = resolveTrackedEventId(ctx, mirrorEventId, player1Name, player2Name, startTimeIso);
        boolean completionSignal = isFinished(eventNode);
        Integer gamesP1 = firstInteger(eventNode, "/homeScore/current", "/homeScore/display", "/homeScore/period1");
        Integer gamesP2 = firstInteger(eventNode, "/awayScore/current", "/awayScore/display", "/awayScore/period1");
        Integer pointsP1 = firstInteger(eventNode, "/homeScore/point", "/pointScore/home", "/pointScore/player1", "/currentGameScore/home");
        Integer pointsP2 = firstInteger(eventNode, "/awayScore/point", "/pointScore/away", "/pointScore/player2", "/currentGameScore/away");
        String phase = resolvePhase(eventNode, completionSignal, gamesP1, gamesP2);
        String server = resolveServer(eventNode);
        String payloadJson = payloadJson(eventNode, incidentsNode);

        return Optional.of(new MirrorObservationPayload(
                trackedEventId,
                mirrorEventId,
                player1Name,
                player2Name,
                competitionName,
                phase,
                gamesP1,
                gamesP2,
                pointsP1,
                pointsP2,
                server,
                completionSignal,
                payloadJson
        ));
    }

    private String resolveTrackedEventId(PullContext ctx,
                                         String mirrorEventId,
                                         String player1Name,
                                         String player2Name,
                                         String startTimeIso) {
        String fromContext = firstAttribute(ctx, "trackedEventId");
        if (StringUtils.hasText(fromContext)) {
            return fromContext.trim();
        }

        String bookerEventId = firstAttribute(ctx, "bookerEventId", "externalEventId", "hrEventId");
        if (StringUtils.hasText(bookerEventId)) {
            return oddsSnapshotFactory.trackedEventId(SourceId.HR_MKT.id(), bookerEventId.trim());
        }

        if (StringUtils.hasText(mirrorEventId)) {
            return oddsSnapshotFactory.trackedEventId(source().id(), mirrorEventId.trim());
        }

        String matchKey = oddsSnapshotFactory.matchKey(player1Name, player2Name, startTimeIso, null, null);
        return oddsSnapshotFactory.trackedEventId(source().id(), matchKey);
    }

    private String resolvePhase(JsonNode eventNode,
                                boolean completionSignal,
                                Integer gamesP1,
                                Integer gamesP2) {
        String statusType = firstText(eventNode, "/status/type", "/status/code", "/status/description", "/status/statusType")
                .toLowerCase(Locale.ROOT);
        if (completionSignal) {
            return "FINISHED";
        }
        if (statusType.contains("notstarted") || statusType.contains("scheduled")) {
            return "PREMATCH";
        }
        if (statusType.contains("inprogress") || firstBoolean(eventNode, "/isLive", "/live")) {
            int totalGames = safeInt(gamesP1) + safeInt(gamesP2);
            if (totalGames <= 1) {
                return "LIVE_EARLY";
            }
            if (totalGames <= 3) {
                return "LIVE_MID";
            }
            return "LIVE_LATE";
        }
        return "UNKNOWN";
    }

    private boolean isFinished(JsonNode eventNode) {
        String statusType = firstText(eventNode, "/status/type", "/status/code", "/status/description", "/status/statusType")
                .toLowerCase(Locale.ROOT);
        return firstBoolean(eventNode, "/status/finished", "/finished")
                || statusType.contains("finished")
                || statusType.contains("ended")
                || statusType.contains("closed");
    }

    private String resolveServer(JsonNode eventNode) {
        String explicit = firstText(eventNode, "/server", "/serving", "/servingPlayer", "/servingTeam/shortName", "/servingTeam/name");
        if (!StringUtils.hasText(explicit)) {
            explicit = firstText(eventNode, "/servingTeam/side", "/servingSide");
        }
        if (!StringUtils.hasText(explicit)) {
            return "";
        }

        String normalized = explicit.trim().toUpperCase(Locale.ROOT);
        if ("HOME".equals(normalized) || "P1".equals(normalized)) {
            return "P1";
        }
        if ("AWAY".equals(normalized) || "P2".equals(normalized)) {
            return "P2";
        }
        return normalized.length() <= 4 ? normalized : normalized.substring(0, 4);
    }

    private String payloadJson(JsonNode eventNode, JsonNode incidentsNode) {
        if (incidentsNode == null || incidentsNode.isMissingNode() || incidentsNode.isNull()) {
            return eventNode.toString();
        }
        ObjectNode merged = MAPPER.createObjectNode();
        merged.set("event", eventNode.deepCopy());
        merged.set("incidents", incidentsNode.deepCopy());
        return merged.toString();
    }

    private String startTimeIso(JsonNode eventNode) {
        long timestampSeconds = firstLong(eventNode, "/startTimestamp", "/startTimeTimestamp", "/time/currentPeriodStartTimestamp");
        if (timestampSeconds > 0L) {
            return Instant.ofEpochSecond(timestampSeconds).toString();
        }
        return firstText(eventNode, "/startTime", "/startDate", "/startDateTime");
    }

    private JsonNode unwrapEvent(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return MAPPER.createObjectNode();
        }
        JsonNode event = root.path("event");
        if (event.isObject()) {
            return event;
        }
        return root;
    }

    private JsonNode extractIncidents(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return MAPPER.createArrayNode();
        }
        JsonNode incidents = root.path("incidents");
        return incidents.isArray() ? incidents : MAPPER.createArrayNode();
    }

    private List<JsonNode> extractEvents(JsonNode root) {
        JsonNode events = root == null ? null : root.path("events");
        if (events == null || !events.isArray()) {
            JsonNode nestedEvents = root == null ? null : root.path("data").path("events");
            events = nestedEvents != null && nestedEvents.isArray() ? nestedEvents : events;
        }
        if (events == null || !events.isArray()) {
            return List.of();
        }

        List<JsonNode> result = new ArrayList<>();
        for (JsonNode event : events) {
            if (event != null && event.isObject()) {
                result.add(event);
            }
        }
        return result;
    }

    private Optional<JsonNode> readJson(String url, boolean allowNotFound) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", refererUrl)
                .header("User-Agent", userAgent)
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int statusCode = response.statusCode();
            if (allowNotFound && statusCode == 404) {
                return Optional.empty();
            }
            if (statusCode < 200 || statusCode >= 300) {
                throw new IllegalStateException("SofaScore request failed with HTTP " + statusCode + " for " + url);
            }
            String body = response.body();
            if (!StringUtils.hasText(body)) {
                return Optional.empty();
            }
            return Optional.of(MAPPER.readTree(body));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to parse SofaScore response from " + url, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while requesting SofaScore " + url, exception);
        }
    }

    private String buildUrl(String path) {
        return apiBaseUrl + ensureLeadingSlash(path);
    }

    private String formatPath(String template, String fixtureId) {
        String encodedFixtureId = URLEncoder.encode(fixtureId == null ? "" : fixtureId.trim(), StandardCharsets.UTF_8);
        return template.formatted(encodedFixtureId);
    }

    private String firstAttribute(PullContext ctx, String... keys) {
        if (ctx == null || ctx.attributes() == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            if (!StringUtils.hasText(key)) {
                continue;
            }
            String value = ctx.attributes().get(key);
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String firstText(JsonNode node, String... pointers) {
        if (node == null || pointers == null) {
            return "";
        }
        for (String pointer : pointers) {
            JsonNode candidate = node.at(pointer);
            if (candidate == null || candidate.isMissingNode() || candidate.isNull()) {
                continue;
            }
            if (candidate.isTextual() && StringUtils.hasText(candidate.textValue())) {
                return candidate.textValue().trim();
            }
            if (candidate.isNumber() || candidate.isBoolean()) {
                return candidate.asText().trim();
            }
        }
        return "";
    }

    private Integer firstInteger(JsonNode node, String... pointers) {
        String text = firstText(node, pointers);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return Integer.parseInt(text.replaceAll("[^0-9-]", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private long firstLong(JsonNode node, String... pointers) {
        String text = firstText(node, pointers);
        if (!StringUtils.hasText(text)) {
            return -1L;
        }
        try {
            return Long.parseLong(text.replaceAll("[^0-9-]", ""));
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private boolean firstBoolean(JsonNode node, String... pointers) {
        String text = firstText(node, pointers);
        if (!StringUtils.hasText(text)) {
            return false;
        }
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text);
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private String trimTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            return "https://www.sofascore.com/api/v1";
        }
        String trimmed = value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private String ensureLeadingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private double clamp(double value, double min, double max, double fallback) {
        if (Double.isNaN(value) || value < min || value > max) {
            return fallback;
        }
        return value;
    }
}
