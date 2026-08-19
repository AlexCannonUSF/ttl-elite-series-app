package com.ttl.tabletennis.scrape;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class ItftWttFeedClient implements FeedClient<ItftWttHistoricalPayload> {

    static final SourceId SOURCE = SourceId.ITTF_WTT;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOPIC = "ranking.updated";

    private final HttpClient httpClient;
    private final IngestionBus ingestionBus;
    private final FeedHealthTracker healthTracker = new FeedHealthTracker();
    private final boolean enabled;
    private final String rankingUrl;
    private final String apiKeyHeaderName;
    private final String apiKeyHeaderValue;
    private final String userAgent;
    private final Duration requestTimeout;
    private final double defaultConfidence;

    @Autowired
    public ItftWttFeedClient(IngestionBus ingestionBus,
                             @Value("${ttl.itftwtt.enabled:false}") boolean enabled,
                             @Value("${ttl.itftwtt.rankingUrl:https://worldtabletennis.com/rankings}") String rankingUrl,
                             @Value("${ttl.itftwtt.apiKeyHeaderName:}") String apiKeyHeaderName,
                             @Value("${ttl.itftwtt.apiKeyHeaderValue:}") String apiKeyHeaderValue,
                             @Value("${ttl.itftwtt.userAgent:TTLEliteSeries/3.0}") String userAgent,
                             @Value("${ttl.itftwtt.requestTimeoutMs:8000}") long requestTimeoutMs,
                             @Value("${ttl.itftwtt.defaultConfidence:0.82}") double defaultConfidence) {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                ingestionBus,
                enabled,
                rankingUrl,
                apiKeyHeaderName,
                apiKeyHeaderValue,
                userAgent,
                requestTimeoutMs,
                defaultConfidence
        );
    }

    ItftWttFeedClient(HttpClient httpClient,
                      IngestionBus ingestionBus,
                      boolean enabled,
                      String rankingUrl,
                      String apiKeyHeaderName,
                      String apiKeyHeaderValue,
                      String userAgent,
                      long requestTimeoutMs,
                      double defaultConfidence) {
        this.httpClient = httpClient == null ? HttpClient.newHttpClient() : httpClient;
        this.ingestionBus = ingestionBus;
        this.enabled = enabled;
        this.rankingUrl = StringUtils.hasText(rankingUrl) ? rankingUrl.trim() : "https://worldtabletennis.com/rankings";
        this.apiKeyHeaderName = safeTrim(apiKeyHeaderName);
        this.apiKeyHeaderValue = safeTrim(apiKeyHeaderValue);
        this.userAgent = StringUtils.hasText(userAgent) ? userAgent.trim() : "TTLEliteSeries/3.0";
        this.requestTimeout = Duration.ofMillis(Math.max(1000L, requestTimeoutMs));
        this.defaultConfidence = clamp(defaultConfidence, 0.0, 1.0, 0.82);
    }

    @Override
    public SourceId source() {
        return SOURCE;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public List<IngestEvent<ItftWttHistoricalPayload>> pullOnce(PullContext ctx) {
        if (!enabled) {
            return List.of();
        }

        Instant startedAt = healthTracker.onPullStart();
        try {
            List<ItftWttHistoricalPayload> payloads = fetchHistoricalPayloads(ctx);
            Instant observedAt = Instant.now();
            String correlationId = ctx == null ? "" : ctx.correlationId();
            List<IngestEvent<ItftWttHistoricalPayload>> events = payloads.stream()
                    .map(payload -> new IngestEvent<>(
                            source(),
                            TOPIC,
                            observedAt,
                            defaultConfidence,
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
        return Set.of(Capability.RANKINGS, Capability.RESULTS);
    }

    List<ItftWttHistoricalPayload> fetchHistoricalPayloads(PullContext ctx) {
        String url = firstAttribute(ctx, "url", "rankingUrl", "sourceUrl");
        if (!StringUtils.hasText(url)) {
            url = rankingUrl;
        }
        LocalDate asOfDate = parseDate(firstAttribute(ctx, "asOfDate")).orElse(LocalDate.now());
        int limit = parseLimit(firstAttribute(ctx, "limit"), 100);
        String body = readBody(url);
        return parsePayloads(body, url, asOfDate, limit);
    }

    private List<ItftWttHistoricalPayload> parsePayloads(String body,
                                                         String sourceUrl,
                                                         LocalDate asOfDate,
                                                         int limit) {
        if (!StringUtils.hasText(body)) {
            return List.of();
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return parseJsonPayloads(trimmed, sourceUrl, asOfDate, limit);
        }
        return parseHtmlPayloads(trimmed, sourceUrl, asOfDate, limit);
    }

    private List<ItftWttHistoricalPayload> parseJsonPayloads(String body,
                                                             String sourceUrl,
                                                             LocalDate asOfDate,
                                                             int limit) {
        try {
            JsonNode root = MAPPER.readTree(body);
            List<JsonNode> rows = extractRows(root);
            if (rows.isEmpty()) {
                return List.of();
            }
            List<ItftWttHistoricalPayload> payloads = new ArrayList<>();
            for (JsonNode row : rows) {
                toPayload(row, sourceUrl, asOfDate).ifPresent(payloads::add);
                if (payloads.size() >= limit) {
                    break;
                }
            }
            return payloads;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to parse ITTF/WTT JSON payload from " + sourceUrl, exception);
        }
    }

    private List<JsonNode> extractRows(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return List.of();
        }
        if (root.isArray()) {
            return arrayRows(root);
        }
        for (String pointer : List.of("/results", "/rankings", "/data", "/data/results", "/data/rankings", "/items")) {
            JsonNode candidate = root.at(pointer);
            if (candidate != null && candidate.isArray()) {
                return arrayRows(candidate);
            }
        }
        return List.of();
    }

    private List<JsonNode> arrayRows(JsonNode array) {
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode item : array) {
            if (item != null && item.isObject()) {
                rows.add(item);
            }
        }
        return rows;
    }

    private java.util.Optional<ItftWttHistoricalPayload> toPayload(JsonNode row,
                                                                   String sourceUrl,
                                                                   LocalDate asOfDate) {
        String playerName = firstText(
                row,
                "/player/name",
                "/playerName",
                "/name",
                "/athlete/name",
                "/competitor/name",
                "/participant/name"
        );
        if (!StringUtils.hasText(playerName)) {
            return java.util.Optional.empty();
        }
        Integer rank = firstInteger(row, "/rank", "/ranking", "/position", "/rankOrder");
        Double points = firstDouble(row, "/points", "/rating", "/score", "/rankPoints");
        String country = firstText(row, "/country", "/country/name", "/association", "/nationality", "/countryCode");
        String sourceKey = firstText(row, "/id", "/player/id", "/profileId", "/slug");
        return java.util.Optional.of(new ItftWttHistoricalPayload(
                sourceKey,
                playerName,
                country,
                rank,
                points,
                asOfDate,
                sourceUrl,
                row.toString()
        ));
    }

    private List<ItftWttHistoricalPayload> parseHtmlPayloads(String body,
                                                             String sourceUrl,
                                                             LocalDate asOfDate,
                                                             int limit) {
        Document document = Jsoup.parse(body, sourceUrl);
        List<ItftWttHistoricalPayload> payloads = new ArrayList<>();
        for (Element table : document.select("table")) {
            java.util.Optional<RankingColumns> columns = resolveColumns(table);
            if (columns.isEmpty()) {
                continue;
            }
            Elements rows = table.select("tr");
            RankingColumns resolved = columns.get();
            for (int rowIndex = resolved.firstDataRowIndex(); rowIndex < rows.size(); rowIndex++) {
                Elements cells = rows.get(rowIndex).select("td, th");
                if (cells.size() <= resolved.maxIndex()) {
                    continue;
                }
                String playerName = text(cells, resolved.playerCol());
                if (!StringUtils.hasText(playerName)) {
                    continue;
                }
                Integer rank = resolved.rankCol() >= 0 ? parseInteger(text(cells, resolved.rankCol())) : null;
                Double points = resolved.pointsCol() >= 0 ? parseDouble(text(cells, resolved.pointsCol())) : null;
                String country = resolved.countryCol() >= 0 ? text(cells, resolved.countryCol()) : "";
                payloads.add(new ItftWttHistoricalPayload(
                        rank == null ? "" : String.valueOf(rank),
                        playerName,
                        country,
                        rank,
                        points,
                        asOfDate,
                        sourceUrl,
                        htmlPayloadJson(playerName, country, rank, points, rowIndex)
                ));
                if (payloads.size() >= limit) {
                    return payloads;
                }
            }
        }
        return payloads;
    }

    private java.util.Optional<RankingColumns> resolveColumns(Element table) {
        if (table == null) {
            return java.util.Optional.empty();
        }
        Elements rows = table.select("tr");
        for (int rowIndex = 0; rowIndex < Math.min(rows.size(), 6); rowIndex++) {
            Elements cells = rows.get(rowIndex).select("th, td");
            int rankCol = -1;
            int playerCol = -1;
            int countryCol = -1;
            int pointsCol = -1;
            for (int col = 0; col < cells.size(); col++) {
                String header = normalizeHeader(cells.get(col).text());
                if (header.equals("rank") || header.equals("ranking") || header.equals("position") || header.equals("pos")) {
                    rankCol = col;
                } else if (header.contains("player") || header.equals("name") || header.contains("athlete")) {
                    playerCol = col;
                } else if (header.contains("country") || header.contains("nation") || header.contains("assoc")) {
                    countryCol = col;
                } else if (header.contains("point") || header.contains("rating")) {
                    pointsCol = col;
                }
            }
            if (playerCol >= 0) {
                return java.util.Optional.of(new RankingColumns(rankCol, playerCol, countryCol, pointsCol, rowIndex + 1));
            }
        }
        return java.util.Optional.empty();
    }

    private String readBody(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("Accept", "application/json, text/html;q=0.9, */*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("User-Agent", userAgent)
                .GET();
        if (StringUtils.hasText(apiKeyHeaderName) && StringUtils.hasText(apiKeyHeaderValue)) {
            builder.header(apiKeyHeaderName, apiKeyHeaderValue);
        }
        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int statusCode = response.statusCode();
            if (statusCode < 200 || statusCode >= 300) {
                throw new IllegalStateException("ITTF/WTT request failed with HTTP " + statusCode + " for " + url);
            }
            return response.body();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read ITTF/WTT payload from " + url, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while requesting ITTF/WTT " + url, exception);
        }
    }

    private String htmlPayloadJson(String playerName,
                                   String country,
                                   Integer rank,
                                   Double points,
                                   int rowIndex) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("playerName", playerName);
        payload.put("country", country == null ? "" : country);
        if (rank != null) {
            payload.put("rank", rank);
        }
        if (points != null) {
            payload.put("points", points);
        }
        payload.put("rowIndex", rowIndex);
        return payload.toString();
    }

    private String text(Elements cells, int index) {
        if (cells == null || index < 0 || index >= cells.size()) {
            return "";
        }
        return cells.get(index).text().replace('\u00a0', ' ').trim();
    }

    private String normalizeHeader(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        return raw.trim()
                .toLowerCase(Locale.ROOT)
                .replace('\u00a0', ' ')
                .replaceAll("[^a-z0-9]+", "");
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
        return parseInteger(firstText(node, pointers));
    }

    private Double firstDouble(JsonNode node, String... pointers) {
        return parseDouble(firstText(node, pointers));
    }

    private Integer parseInteger(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            String normalized = raw.replaceAll("[^0-9-]", "");
            if (!StringUtils.hasText(normalized)) {
                return null;
            }
            return Integer.parseInt(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Double parseDouble(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            String normalized = raw.trim().replace(',', '.').replaceAll("[^0-9.\\-]", "");
            if (!StringUtils.hasText(normalized)) {
                return null;
            }
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private java.util.Optional<LocalDate> parseDate(String raw) {
        if (!StringUtils.hasText(raw)) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(LocalDate.parse(raw.trim()));
        } catch (RuntimeException ignored) {
            return java.util.Optional.empty();
        }
    }

    private int parseLimit(String rawLimit, int fallback) {
        if (!StringUtils.hasText(rawLimit)) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(rawLimit.trim()));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
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

    private record RankingColumns(int rankCol,
                                  int playerCol,
                                  int countryCol,
                                  int pointsCol,
                                  int firstDataRowIndex) {
        int maxIndex() {
            return Math.max(Math.max(rankCol, playerCol), Math.max(countryCol, pointsCol));
        }
    }
}
