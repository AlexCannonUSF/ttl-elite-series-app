package com.ttl.tabletennis.scrape;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

abstract class AbstractJsonMirrorFeedClient implements FeedClient<MirrorObservationPayload> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SCORE_PAIR = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*[-:/]\\s*(\\d{1,2})(?!\\d)");
    private static final String TOPIC = "score.observed";

    private final SourceId source;
    private final HttpClient httpClient;
    private final IngestionBus ingestionBus;
    private final OddsSnapshotFactory oddsSnapshotFactory;
    private final FeedHealthTracker healthTracker = new FeedHealthTracker();
    private final boolean enabled;
    private final String apiBaseUrl;
    private final String liveEventsPath;
    private final String eventPathTemplate;
    private final String tokenParameterName;
    private final String apiToken;
    private final String apiKeyHeaderName;
    private final String apiKeyHeaderValue;
    private final String refererUrl;
    private final String userAgent;
    private final Duration requestTimeout;
    private final double defaultConfidence;
    private final double completionConfidence;

    AbstractJsonMirrorFeedClient(SourceId source,
                                 HttpClient httpClient,
                                 IngestionBus ingestionBus,
                                 OddsSnapshotFactory oddsSnapshotFactory,
                                 boolean enabled,
                                 String apiBaseUrl,
                                 String liveEventsPath,
                                 String eventPathTemplate,
                                 String tokenParameterName,
                                 String apiToken,
                                 String apiKeyHeaderName,
                                 String apiKeyHeaderValue,
                                 String refererUrl,
                                 String userAgent,
                                 long requestTimeoutMs,
                                 double defaultConfidence,
                                 double completionConfidence) {
        this.source = source;
        this.httpClient = httpClient == null ? HttpClient.newHttpClient() : httpClient;
        this.ingestionBus = ingestionBus;
        this.oddsSnapshotFactory = oddsSnapshotFactory == null ? new OddsSnapshotFactory() : oddsSnapshotFactory;
        this.enabled = enabled;
        this.apiBaseUrl = trimTrailingSlash(apiBaseUrl);
        this.liveEventsPath = ensureLeadingSlash(liveEventsPath);
        this.eventPathTemplate = ensureLeadingSlash(eventPathTemplate);
        this.tokenParameterName = safeTrim(tokenParameterName);
        this.apiToken = safeTrim(apiToken);
        this.apiKeyHeaderName = safeTrim(apiKeyHeaderName);
        this.apiKeyHeaderValue = safeTrim(apiKeyHeaderValue);
        this.refererUrl = safeTrim(refererUrl);
        this.userAgent = StringUtils.hasText(userAgent) ? userAgent.trim() : "TTLEliteSeries/3.0";
        this.requestTimeout = Duration.ofMillis(Math.max(1000L, requestTimeoutMs));
        this.defaultConfidence = clamp(defaultConfidence, 0.0, 1.0, 0.75);
        this.completionConfidence = clamp(completionConfidence, 0.0, 1.0, 0.88);
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public SourceId source() {
        return source;
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
        return Set.of(Capability.SCORES, Capability.POINT_BY_POINT, Capability.RESULTS, Capability.COMPLETION_SIGNAL);
    }

    List<MirrorObservationPayload> fetchMirrorPayloads(PullContext ctx) {
        String fixtureId = firstAttribute(ctx, "fixtureId", "eventId", "matchId", "betsapiEventId", "aiscoreMatchId");
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
        for (JsonNode event : events) {
            buildPayload(event, null, ctx).ifPresent(payloads::add);
        }
        return payloads;
    }

    private List<MirrorObservationPayload> fetchTargetedPayloads(PullContext ctx, String fixtureId) {
        if (!StringUtils.hasText(eventPathTemplate)) {
            return List.of();
        }
        Optional<JsonNode> root = readJson(buildUrl(formatPath(eventPathTemplate, fixtureId)), true);
        if (root.isEmpty()) {
            return List.of();
        }
        JsonNode event = unwrapEvent(root.get());
        return buildPayload(event, root.get(), ctx)
                .map(List::of)
                .orElse(List.of());
    }

    private Optional<MirrorObservationPayload> buildPayload(JsonNode eventNode,
                                                            JsonNode detailNode,
                                                            PullContext ctx) {
        if (eventNode == null || eventNode.isMissingNode() || eventNode.isNull()) {
            return Optional.empty();
        }

        String mirrorEventId = firstText(
                eventNode,
                "/id",
                "/event_id",
                "/eventId",
                "/match_id",
                "/matchId",
                "/fixture_id",
                "/fixture/id"
        );
        String player1Name = firstText(
                eventNode,
                "/home/name",
                "/home/name_en",
                "/homeTeam/name",
                "/homeCompetitor/name",
                "/home_name",
                "/homeName",
                "/homeTeamName",
                "/team_A/name",
                "/teams/home/name",
                "/participants/0/name",
                "/competitors/0/name"
        );
        String player2Name = firstText(
                eventNode,
                "/away/name",
                "/away/name_en",
                "/awayTeam/name",
                "/awayCompetitor/name",
                "/away_name",
                "/awayName",
                "/awayTeamName",
                "/team_B/name",
                "/teams/away/name",
                "/participants/1/name",
                "/competitors/1/name"
        );
        String competitionName = firstText(
                eventNode,
                "/league/name",
                "/league/name_en",
                "/competition/name",
                "/competitionName",
                "/league_name",
                "/tournament/name",
                "/category/name",
                "/season/name"
        );

        if (!StringUtils.hasText(player1Name) || !StringUtils.hasText(player2Name)) {
            return Optional.empty();
        }

        ScorePair gamePair = firstScorePair(eventNode, true);
        ScorePair pointPair = firstScorePair(eventNode, false);
        boolean completionSignal = isFinished(eventNode);
        String phase = resolvePhase(eventNode, completionSignal, gamePair.left(), gamePair.right());
        String server = resolveServer(eventNode);
        String startTimeIso = startTimeIso(eventNode);
        String trackedEventId = resolveTrackedEventId(ctx, mirrorEventId, player1Name, player2Name, startTimeIso);
        String payloadJson = payloadJson(eventNode, detailNode);

        return Optional.of(new MirrorObservationPayload(
                trackedEventId,
                mirrorEventId,
                player1Name,
                player2Name,
                competitionName,
                phase,
                gamePair.left(),
                gamePair.right(),
                pointPair.left(),
                pointPair.right(),
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

    private ScorePair firstScorePair(JsonNode node, boolean games) {
        if (games) {
            ScorePair direct = new ScorePair(
                    firstInteger(node, "/homeScore/current", "/score/home", "/scores/home", "/home_score", "/homeScore"),
                    firstInteger(node, "/awayScore/current", "/score/away", "/scores/away", "/away_score", "/awayScore")
            );
            if (direct.complete()) {
                return direct;
            }
            return firstPairFromText(node, "/ss", "/set_score", "/setScore", "/score/sets", "/match_score", "/matchScore", "/score");
        }

        ScorePair direct = new ScorePair(
                firstInteger(node, "/homeScore/point", "/pointScore/home", "/points/home", "/currentGameScore/home", "/home_points", "/homePoint"),
                firstInteger(node, "/awayScore/point", "/pointScore/away", "/points/away", "/currentGameScore/away", "/away_points", "/awayPoint")
        );
        if (direct.complete()) {
            return direct;
        }
        return firstPairFromText(node, "/points", "/point_score", "/pointScore", "/current_game_score", "/currentGameScore");
    }

    private ScorePair firstPairFromText(JsonNode node, String... pointers) {
        String scoreText = firstText(node, pointers);
        if (!StringUtils.hasText(scoreText)) {
            return ScorePair.empty();
        }
        Matcher matcher = SCORE_PAIR.matcher(scoreText);
        if (!matcher.find()) {
            return ScorePair.empty();
        }
        return new ScorePair(parseInteger(matcher.group(1)), parseInteger(matcher.group(2)));
    }

    private String resolvePhase(JsonNode eventNode,
                                boolean completionSignal,
                                Integer gamesP1,
                                Integer gamesP2) {
        String statusText = firstText(
                eventNode,
                "/time_status",
                "/status_id",
                "/status/type",
                "/status/code",
                "/status/description",
                "/status/statusType",
                "/status",
                "/statusText",
                "/status_name",
                "/match_status"
        ).toLowerCase(Locale.ROOT);

        if (completionSignal) {
            return "FINISHED";
        }
        if (statusText.equals("0")
                || statusText.contains("notstarted")
                || statusText.contains("not started")
                || statusText.contains("scheduled")
                || statusText.contains("prematch")) {
            return "PREMATCH";
        }
        if (statusText.equals("1")
                || statusText.contains("live")
                || statusText.contains("progress")
                || firstBoolean(eventNode, "/isLive", "/live", "/hasLiveScore")) {
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
        String statusText = firstText(
                eventNode,
                "/time_status",
                "/status_id",
                "/status/type",
                "/status/code",
                "/status/description",
                "/status/statusType",
                "/status",
                "/statusText",
                "/status_name",
                "/match_status"
        ).toLowerCase(Locale.ROOT);
        return firstBoolean(eventNode, "/status/finished", "/finished", "/ended", "/resulted", "/matchCompleted")
                || statusText.equals("3")
                || statusText.equals("8")
                || statusText.contains("finished")
                || statusText.contains("ended")
                || statusText.contains("fulltime")
                || statusText.contains("full time")
                || statusText.contains("closed")
                || statusText.contains("complete");
    }

    private String resolveServer(JsonNode eventNode) {
        String explicit = firstText(
                eventNode,
                "/server",
                "/serving",
                "/servingPlayer",
                "/servingTeam/shortName",
                "/servingTeam/name",
                "/serve",
                "/service"
        );
        if (!StringUtils.hasText(explicit)) {
            explicit = firstText(eventNode, "/servingTeam/side", "/servingSide");
        }
        if (!StringUtils.hasText(explicit)) {
            return "";
        }

        String normalized = explicit.trim().toUpperCase(Locale.ROOT);
        if ("HOME".equals(normalized) || "P1".equals(normalized) || "1".equals(normalized)) {
            return "P1";
        }
        if ("AWAY".equals(normalized) || "P2".equals(normalized) || "2".equals(normalized)) {
            return "P2";
        }
        return normalized.length() <= 4 ? normalized : normalized.substring(0, 4);
    }

    private String payloadJson(JsonNode eventNode, JsonNode detailNode) {
        if (detailNode == null || detailNode.isMissingNode() || detailNode.isNull() || detailNode == eventNode) {
            return eventNode.toString();
        }
        ObjectNode merged = MAPPER.createObjectNode();
        merged.set("event", eventNode.deepCopy());
        merged.set("detail", detailNode.deepCopy());
        return merged.toString();
    }

    private String startTimeIso(JsonNode eventNode) {
        long timestampSeconds = firstLong(
                eventNode,
                "/time",
                "/startTimestamp",
                "/startTimeTimestamp",
                "/match_time",
                "/scheduled",
                "/start_time"
        );
        if (timestampSeconds > 0L) {
            return Instant.ofEpochSecond(timestampSeconds).toString();
        }
        return firstText(eventNode, "/startTime", "/startDate", "/startDateTime", "/start_time_iso", "/time");
    }

    private JsonNode unwrapEvent(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return MAPPER.createObjectNode();
        }
        for (String pointer : List.of("/event", "/result", "/data/event", "/data/match", "/match")) {
            JsonNode candidate = root.at(pointer);
            if (candidate != null && candidate.isObject()) {
                return candidate;
            }
        }
        for (String pointer : List.of("/results", "/data", "/data/results", "/events", "/matches")) {
            JsonNode candidate = root.at(pointer);
            if (candidate != null && candidate.isArray() && candidate.size() > 0 && candidate.get(0).isObject()) {
                return candidate.get(0);
            }
        }
        return root;
    }

    private List<JsonNode> extractEvents(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return List.of();
        }

        List<JsonNode> out = new ArrayList<>();
        for (String pointer : List.of(
                "/results",
                "/events",
                "/matches",
                "/response",
                "/data",
                "/data/results",
                "/data/events",
                "/data/matches"
        )) {
            JsonNode candidate = root.at(pointer);
            if (candidate != null && candidate.isArray()) {
                for (JsonNode event : candidate) {
                    if (event != null && event.isObject()) {
                        out.add(event);
                    }
                }
                if (!out.isEmpty()) {
                    return out;
                }
            }
        }

        JsonNode unwrapped = unwrapEvent(root);
        if (unwrapped.isObject() && unwrapped != root) {
            return List.of(unwrapped);
        }
        return List.of();
    }

    private Optional<JsonNode> readJson(String url, boolean allowNotFound) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("User-Agent", userAgent)
                .GET();
        if (StringUtils.hasText(refererUrl)) {
            builder.header("Referer", refererUrl);
        }
        if (StringUtils.hasText(apiKeyHeaderName) && StringUtils.hasText(apiKeyHeaderValue)) {
            builder.header(apiKeyHeaderName, apiKeyHeaderValue);
        }

        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int statusCode = response.statusCode();
            if (allowNotFound && statusCode == 404) {
                return Optional.empty();
            }
            if (statusCode < 200 || statusCode >= 300) {
                throw new IllegalStateException(source().id() + " request failed with HTTP " + statusCode + " for " + url);
            }
            String body = response.body();
            if (!StringUtils.hasText(body)) {
                return Optional.empty();
            }
            return Optional.of(MAPPER.readTree(body));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to parse " + source().id() + " response from " + url, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while requesting " + source().id() + " " + url, exception);
        }
    }

    private String buildUrl(String path) {
        String url = apiBaseUrl + ensureLeadingSlash(path);
        if (!StringUtils.hasText(tokenParameterName) || !StringUtils.hasText(apiToken) || url.contains(tokenParameterName + "=")) {
            return url;
        }
        String delimiter = url.contains("?") ? "&" : "?";
        return url + delimiter + tokenParameterName + "=" + URLEncoder.encode(apiToken, StandardCharsets.UTF_8);
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
            if (candidate.isArray() && candidate.size() > 0) {
                JsonNode first = candidate.get(0);
                if (first.isTextual() || first.isNumber() || first.isBoolean()) {
                    return first.asText().trim();
                }
            }
        }
        return "";
    }

    private Integer firstInteger(JsonNode node, String... pointers) {
        String text = firstText(node, pointers);
        return parseInteger(text);
    }

    private Integer parseInteger(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            String normalized = text.replaceAll("[^0-9-]", "");
            if (!StringUtils.hasText(normalized)) {
                return null;
            }
            return Integer.parseInt(normalized);
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
            String normalized = text.replaceAll("[^0-9-]", "");
            if (!StringUtils.hasText(normalized)) {
                return -1L;
            }
            long parsed = Long.parseLong(normalized);
            return parsed > 9_999_999_999L ? parsed / 1000L : parsed;
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
            return "";
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

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private double clamp(double value, double min, double max, double fallback) {
        if (Double.isNaN(value) || value < min || value > max) {
            return fallback;
        }
        return value;
    }

    private record ScorePair(Integer left, Integer right) {
        static ScorePair empty() {
            return new ScorePair(null, null);
        }

        boolean complete() {
            return left != null && right != null;
        }
    }
}
