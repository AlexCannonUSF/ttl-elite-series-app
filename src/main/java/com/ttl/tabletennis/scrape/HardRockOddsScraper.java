package com.ttl.tabletennis.scrape;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ttl.tabletennis.model.MatchOdds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls public odds from Hard Rock Bet.
 *
 * Strategy:
 * 1) GraphQL events feed (includes market selections + odds).
 * 2) Official public tree endpoint (`sportsbook/api/public/events/tree`) with segment/channel.
 * 3) Optional custom JSON endpoint (via -Dhr.json=...) for quick overrides.
 * 4) HTML fallback parse from home page.
 */
@Component
public class HardRockOddsScraper {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(HardRockOddsScraper.class);
    private static final Pattern VS_SPLIT = Pattern.compile("\\s+(?:vs\\.?|v\\.?|-)\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCORE_PATTERN = Pattern.compile("\\b(\\d{1,2})\\s*[-:]\\s*(\\d{1,2})\\b");
    private static final Pattern SOURCE_EVENT_ID_PATTERN =
            Pattern.compile("\\|event=([A-Za-z0-9:_-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MATCH_STATE_GAME_SCORE_PATTERN =
            Pattern.compile("(?:gameScore(?:In)?Game|scoreInGame)(\\d+)([AB])", Pattern.CASE_INSENSITIVE);

    private static final String SOURCE_TYPE_GQL_MARKET = "GQL_MARKET";
    private static final String SOURCE_TYPE_GQL_SCOREBOARD = "GQL_SCOREBOARD";
    private static final String SOURCE_TYPE_GQL_TRACKED_EVENT = "GQL_TRACKED_EVENT";
    private static final String SOURCE_TYPE_PUBLIC_MARKET = "PUBLIC_TREE_MARKET";
    private static final String SOURCE_TYPE_PUBLIC_SCOREBOARD = "PUBLIC_TREE_SCOREBOARD";
    private static final String SOURCE_TYPE_CUSTOM_JSON = "CUSTOM_JSON";
    private static final String SOURCE_TYPE_HTML_FALLBACK = "HTML_FALLBACK";

    private static final double SOURCE_CONFIDENCE_GQL_MARKET = 0.92;
    private static final double SOURCE_CONFIDENCE_GQL_SCOREBOARD = 0.90;
    private static final double SOURCE_CONFIDENCE_GQL_TRACKED_EVENT = 0.97;
    private static final double SOURCE_CONFIDENCE_PUBLIC_MARKET = 0.56;
    private static final double SOURCE_CONFIDENCE_PUBLIC_SCOREBOARD = 0.66;
    private static final double SOURCE_CONFIDENCE_CUSTOM_JSON = 0.35;
    private static final double SOURCE_CONFIDENCE_HTML = 0.25;

    private static final String DEFAULT_HOME = "https://app.hardrock.bet/home";
    private static final String DEFAULT_PUBLIC_TREE = "https://api.hardrocksportsbook.com/sportsbook/api/public/events/tree";
    private static final String DEFAULT_GRAPHQL_EVENTS = "https://app.hardrock.bet/java-graphql/graphql?type=events";
    private static final String DEFAULT_WHATS_MY_SEGMENT = "https://api.hardrock.bet/sportsbook/v1/api/whatsMySegment";
    private static final String DEFAULT_REGION = "us";
    private static final String DEFAULT_LANGUAGE = "enus";

    private final HttpClient httpClient;
    private final String homeUrl;
    private final String publicTreeUrl;
    private final String graphQlUrl;
    private final String whatsMySegmentUrl;
    private final String region;
    private final String language;
    private final int maxEvents;
    private final String customJsonUrl;
    private final List<String> segmentCandidates;
    private final List<String> channelCandidates;
    private final boolean tableTennisOnly;
    private final boolean ttlCompetitionOnly;

    @Autowired
    public HardRockOddsScraper(
            @Value("${hr.home:https://app.hardrock.bet/home}") String homeUrl,
            @Value("${hr.publicTree:https://api.hardrocksportsbook.com/sportsbook/api/public/events/tree}") String publicTreeUrl,
            @Value("${hr.graphqlUrl:https://app.hardrock.bet/java-graphql/graphql?type=events}") String graphQlUrl,
            @Value("${hr.whatsMySegmentUrl:https://api.hardrock.bet/sportsbook/v1/api/whatsMySegment}") String whatsMySegmentUrl,
            @Value("${hr.region:us}") String region,
            @Value("${hr.language:enus}") String language,
            @Value("${hr.maxEvents:220}") int maxEvents,
            @Value("${hr.json:}") String customJsonUrl,
            @Value("${hr.segment:r.fl}") String segment,
            @Value("${hr.channel:web}") String channel,
            @Value("${hr.fallbackSegments:r.fl,r.az,r.co,r.il,r.in,r.ia,r.nj,r.od,r.pa,r.tn,r.va}") String fallbackSegments,
            @Value("${hr.fallbackChannels:FLORIDA_ONLINE,NEW_JERSEY_ONLINE,web,mobile}") String fallbackChannels,
            @Value("${hr.tableTennisOnly:true}") boolean tableTennisOnly,
            @Value("${hr.ttlCompetitionOnly:true}") boolean ttlCompetitionOnly
    ) {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build(),
                readSystemOverride("hr.home", homeUrl, DEFAULT_HOME),
                readSystemOverride("hr.publicTree", publicTreeUrl, DEFAULT_PUBLIC_TREE),
                readSystemOverride("hr.graphqlUrl", graphQlUrl, DEFAULT_GRAPHQL_EVENTS),
                readSystemOverride("hr.whatsMySegmentUrl", whatsMySegmentUrl, DEFAULT_WHATS_MY_SEGMENT),
                readSystemOverride("hr.region", region, DEFAULT_REGION),
                readSystemOverride("hr.language", language, DEFAULT_LANGUAGE),
                maxEvents,
                readSystemOverride("hr.json", customJsonUrl, ""),
                readSystemOverride("hr.segment", segment, "r.fl"),
                readSystemOverride("hr.channel", channel, "web"),
                readSystemOverride("hr.fallbackSegments", fallbackSegments, ""),
                readSystemOverride("hr.fallbackChannels", fallbackChannels, ""),
                tableTennisOnly,
                ttlCompetitionOnly
        );
    }

    HardRockOddsScraper(HttpClient httpClient,
                        String homeUrl,
                        String publicTreeUrl,
                        String graphQlUrl,
                        String whatsMySegmentUrl,
                        String region,
                        String language,
                        int maxEvents,
                        String customJsonUrl,
                        String segment,
                        String channel,
                        String fallbackSegments,
                        String fallbackChannels,
                        boolean tableTennisOnly,
                        boolean ttlCompetitionOnly) {
        this.httpClient = httpClient;
        this.homeUrl = StringUtils.hasText(homeUrl) ? homeUrl.trim() : DEFAULT_HOME;
        this.publicTreeUrl = StringUtils.hasText(publicTreeUrl) ? publicTreeUrl.trim() : DEFAULT_PUBLIC_TREE;
        this.graphQlUrl = graphQlUrl == null ? "" : graphQlUrl.trim();
        this.whatsMySegmentUrl = whatsMySegmentUrl == null ? "" : whatsMySegmentUrl.trim();
        this.region = StringUtils.hasText(region) ? region.trim().toLowerCase(Locale.ROOT) : DEFAULT_REGION;
        this.language = StringUtils.hasText(language) ? language.trim().toLowerCase(Locale.ROOT) : DEFAULT_LANGUAGE;
        this.maxEvents = Math.max(40, maxEvents);
        this.customJsonUrl = customJsonUrl == null ? "" : customJsonUrl.trim();
        this.segmentCandidates = mergeCandidates(segment, fallbackSegments, "r.fl");
        this.channelCandidates = mergeCandidates(channel, fallbackChannels, "web");
        this.tableTennisOnly = tableTennisOnly;
        this.ttlCompetitionOnly = ttlCompetitionOnly;
    }

    HardRockOddsScraper(HttpClient httpClient,
                        String homeUrl,
                        String publicTreeUrl,
                        String customJsonUrl,
                        String segment,
                        String channel,
                        String fallbackSegments,
                        String fallbackChannels,
                        boolean tableTennisOnly) {
        this.httpClient = httpClient;
        this.homeUrl = StringUtils.hasText(homeUrl) ? homeUrl.trim() : DEFAULT_HOME;
        this.publicTreeUrl = StringUtils.hasText(publicTreeUrl) ? publicTreeUrl.trim() : DEFAULT_PUBLIC_TREE;
        this.graphQlUrl = "";
        this.whatsMySegmentUrl = "";
        this.region = DEFAULT_REGION;
        this.language = DEFAULT_LANGUAGE;
        this.maxEvents = 220;
        this.customJsonUrl = customJsonUrl == null ? "" : customJsonUrl.trim();
        this.segmentCandidates = mergeCandidates(segment, fallbackSegments, "r.fl");
        this.channelCandidates = mergeCandidates(channel, fallbackChannels, "web");
        this.tableTennisOnly = tableTennisOnly;
        this.ttlCompetitionOnly = true;
    }

    public List<MatchOdds> fetch() {
        List<MatchOdds> fromGraphQl = fetchGraphQlEvents();
        if (!fromGraphQl.isEmpty()) {
            return dedupe(fromGraphQl);
        }

        List<MatchOdds> official = fetchOfficialPublicTree();
        if (!official.isEmpty()) {
            return dedupe(official);
        }

        if (!customJsonUrl.isBlank()) {
            List<MatchOdds> custom = fetchFromCustomJson(customJsonUrl);
            if (!custom.isEmpty()) {
                return dedupe(custom);
            }
        }

        return dedupe(scrapeHtmlFallback());
    }

    public List<MatchOdds> fetchScoreboard() {
        List<MatchOdds> merged = new ArrayList<>();
        List<MatchOdds> fromGraphQl = fetchGraphQlScoreboard();
        if (!fromGraphQl.isEmpty()) {
            merged.addAll(fromGraphQl);
        }

        List<MatchOdds> official = fetchOfficialPublicTreeScoreboard();
        if (!official.isEmpty()) {
            merged.addAll(official);
        }

        if (merged.isEmpty()) {
            return List.of();
        }
        return dedupeScoreboard(merged);
    }

    public List<MatchOdds> discoverPublicTreeEvents() {
        List<MatchOdds> scoreboardRows = fetchOfficialPublicTreeScoreboard();
        if (!scoreboardRows.isEmpty()) {
            return dedupeScoreboard(scoreboardRows);
        }
        List<MatchOdds> marketRows = fetchOfficialPublicTree();
        if (!marketRows.isEmpty()) {
            return dedupe(marketRows);
        }
        return List.of();
    }

    public List<MatchOdds> fetchScoreboardByEventIds(Collection<String> externalEventIds) {
        List<String> requestedEventIds = normalizeExternalEventIds(externalEventIds);
        if (requestedEventIds.isEmpty() || !StringUtils.hasText(graphQlUrl)) {
            return List.of();
        }

        Optional<RegionHint> regionHint = fetchRegionHint();

        LinkedHashSet<String> segments = new LinkedHashSet<>();
        regionHint.map(RegionHint::segment).map(this::normalizeSegmentForQuery).filter(StringUtils::hasText).ifPresent(segments::add);
        if (!segmentCandidates.isEmpty()) {
            String normalized = normalizeSegmentForQuery(segmentCandidates.get(0));
            if (StringUtils.hasText(normalized)) {
                segments.add(normalized);
            }
        }
        if (segments.isEmpty()) {
            segments.add("fl");
        }

        LinkedHashSet<String> channels = new LinkedHashSet<>();
        regionHint.map(RegionHint::channel).map(this::normalizeChannel).filter(StringUtils::hasText).ifPresent(channels::add);
        for (String channel : channelCandidates) {
            String normalized = normalizeChannel(channel);
            if (StringUtils.hasText(normalized)) {
                channels.add(normalized);
            }
        }
        for (String segment : segments) {
            String inferred = inferOnlineChannel(segment);
            if (StringUtils.hasText(inferred)) {
                channels.add(inferred);
            }
        }
        channels.add("FLORIDA_ONLINE");
        channels.add("NEW_JERSEY_ONLINE");
        channels.add("VIRGINIA_ONLINE");
        channels.add("web");
        channels.add("mobile");

        List<MatchOdds> merged = new ArrayList<>();
        for (String segment : segments) {
            for (String channel : channels) {
                List<MatchOdds> rows = executeGraphQlScoreboardQuery(
                        segment,
                        channel,
                        List.of(),
                        false,
                        false,
                        requestedEventIds
                );
                if (!rows.isEmpty()) {
                    merged.addAll(rows);
                }
            }
        }

        if (merged.isEmpty()) {
            return List.of();
        }
        return dedupeScoreboard(merged);
    }

    private List<MatchOdds> fetchGraphQlScoreboard() {
        if (!StringUtils.hasText(graphQlUrl)) {
            return List.of();
        }

        Optional<RegionHint> regionHint = fetchRegionHint();

        LinkedHashSet<String> segments = new LinkedHashSet<>();
        regionHint.map(RegionHint::segment).map(this::normalizeSegmentForQuery).filter(StringUtils::hasText).ifPresent(segments::add);
        if (!segmentCandidates.isEmpty()) {
            String normalized = normalizeSegmentForQuery(segmentCandidates.get(0));
            if (StringUtils.hasText(normalized)) {
                segments.add(normalized);
            }
        }
        if (segments.isEmpty()) {
            segments.add("fl");
        }

        LinkedHashSet<String> channels = new LinkedHashSet<>();
        regionHint.map(RegionHint::channel).map(this::normalizeChannel).filter(StringUtils::hasText).ifPresent(channels::add);
        for (String channel : channelCandidates) {
            String normalized = normalizeChannel(channel);
            if (StringUtils.hasText(normalized)) {
                channels.add(normalized);
            }
        }
        for (String segment : segments) {
            String inferred = inferOnlineChannel(segment);
            if (StringUtils.hasText(inferred)) {
                channels.add(inferred);
            }
        }
        channels.add("FLORIDA_ONLINE");
        channels.add("NEW_JERSEY_ONLINE");
        channels.add("VIRGINIA_ONLINE");
        channels.add("web");
        channels.add("mobile");

        List<MatchOdds> merged = new ArrayList<>();
        for (String segment : segments) {
            for (String channel : channels) {
                List<String> ttlCompetitionIds = extractTtlCompetitionIdsFromTree(segment, channel);
                List<MatchOdds> ttlRows = queryGraphQlScoreboardEvents(segment, channel, ttlCompetitionIds, ttlCompetitionOnly);
                if (!ttlRows.isEmpty()) {
                    merged.addAll(ttlRows);
                }
                List<MatchOdds> allRows = queryGraphQlScoreboardEvents(segment, channel, List.of(), false);
                if (!allRows.isEmpty()) {
                    merged.addAll(allRows);
                }
            }
        }
        if (merged.isEmpty()) {
            return List.of();
        }
        return dedupeScoreboard(merged);
    }

    private List<MatchOdds> queryGraphQlScoreboardEvents(String segment,
                                                         String channel,
                                                         List<String> ttlCompetitionIds,
                                                         boolean ttlOnly) {
        List<MatchOdds> filtered = executeGraphQlScoreboardQuery(
                segment,
                channel,
                ttlCompetitionIds,
                ttlOnly,
                true,
                List.of()
        );
        List<MatchOdds> broad = executeGraphQlScoreboardQuery(
                segment,
                channel,
                ttlCompetitionIds,
                ttlOnly,
                false,
                List.of()
        );
        if (filtered.isEmpty() && broad.isEmpty()) {
            return List.of();
        }
        List<MatchOdds> merged = new ArrayList<>(filtered.size() + broad.size());
        merged.addAll(filtered);
        merged.addAll(broad);
        return dedupeScoreboard(merged);
    }

    private List<MatchOdds> executeGraphQlScoreboardQuery(String segment,
                                                          String channel,
                                                          List<String> ttlCompetitionIds,
                                                          boolean ttlOnly,
                                                          boolean includeMarketTypeFilter,
                                                          List<String> externalEventIds) {
        List<MatchOdds> merged = new ArrayList<>();
        for (boolean descending : List.of(false, true)) {
            List<MatchOdds> page = executeSingleGraphQlScoreboardQuery(
                    segment,
                    channel,
                    ttlCompetitionIds,
                    ttlOnly,
                    includeMarketTypeFilter,
                    externalEventIds,
                    descending
            );
            if (!page.isEmpty()) {
                merged.addAll(page);
            }
        }
        if (merged.isEmpty()) {
            return List.of();
        }
        return dedupeScoreboard(merged);
    }

    private List<MatchOdds> executeSingleGraphQlScoreboardQuery(String segment,
                                                                String channel,
                                                                List<String> ttlCompetitionIds,
                                                                boolean ttlOnly,
                                                                boolean includeMarketTypeFilter,
                                                                List<String> externalEventIds,
                                                                boolean descending) {
        ObjectNode payload = buildGraphQlPayload(
                segment,
                channel,
                ttlCompetitionIds,
                true,
                includeMarketTypeFilter,
                descending,
                externalEventIds
        );
        String body;
        try {
            body = MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            return List.of();
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(graphQlUrl))
                    .header("accept", "application/json")
                    .header("content-type", "application/json")
                    .header("user-agent", "Mozilla/5.0 (compatible; TTLEliteSeries/2.0)")
                    .timeout(Duration.ofSeconds(18))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || !StringUtils.hasText(response.body())) {
                return List.of();
            }

            JsonNode root = MAPPER.readTree(response.body());
            JsonNode events = root.path("data").path("betSync").path("events").path("data");
            if (!events.isArray() || events.isEmpty()) {
                return List.of();
            }

            List<MatchOdds> out = new ArrayList<>();
            String sourceTag = "HARD_ROCK_GQL_SCORE";
            boolean trackedEventQuery = externalEventIds != null && !externalEventIds.isEmpty();
            for (JsonNode event : events) {
                Optional<MatchOdds> parsed = parseGraphQlScoreboardEvent(event, sourceTag, channel, ttlOnly, trackedEventQuery);
                parsed.ifPresent(out::add);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private Optional<MatchOdds> parseGraphQlScoreboardEvent(JsonNode event,
                                                            String sourceTag,
                                                            String channel,
                                                            boolean ttlOnly,
                                                            boolean trackedEventQuery) {
        String competitionName = firstText(event, "compName", "competitionName", "leagueName");
        String eventName = firstText(event, "name", "displayName", "title");
        String sport = firstText(event, "sport", "sportCode", "sportName");
        if (tableTennisOnly && !(containsTableTennisToken(sport) || containsTableTennisToken(competitionName) || containsTableTennisToken(eventName))) {
            return Optional.empty();
        }
        if (ttlOnly && !containsTtlCompetitionToken(competitionName)) {
            return Optional.empty();
        }

        String[] players = extractPlayers(event, eventName);
        if (players[0].isBlank() || players[1].isBlank()) {
            players = extractPlayersFromMarkets(event);
        }
        if (players[0].isBlank() || players[1].isBlank()) {
            return Optional.empty();
        }

        boolean live = firstBoolean(event, "inplay", "isInplay");
        String startIso = "";
        JsonNode eventTimeNode = event.path("eventTime");
        if (eventTimeNode.isNumber()) {
            double epochMs = eventTimeNode.asDouble(0.0);
            if (epochMs > 0.0) {
                startIso = Instant.ofEpochMilli((long) epochMs).toString();
            }
        }
        if (!StringUtils.hasText(startIso)) {
            String startTimeRaw = firstText(event, "startTime", "eventStart", "startDateTime", "startDate");
            if (StringUtils.hasText(startTimeRaw)) {
                startIso = startTimeRaw.trim();
            }
        }
        MatchStateDetails matchState = extractMatchStateDetails(event.path("matchState"));
        String liveScore = StringUtils.hasText(matchState.liveScore())
                ? matchState.liveScore()
                : extractLiveScore(event, eventName);
        String matchPhase = isEventResulted(event)
                ? "FINISHED"
                : inferMatchPhase(live, startIso, liveScore);
        String externalEventId = firstText(event, "id", "eventId", "eventID");
        String source = withExternalEventId(sourceTag + ":" + channel, externalEventId);

        MatchOdds odds = new MatchOdds(
                players[0],
                players[1],
                2.0,
                2.0,
                StringUtils.hasText(eventName) ? eventName : players[0] + " vs " + players[1],
                StringUtils.hasText(competitionName) ? competitionName : "Table Tennis",
                live,
                startIso,
                source,
                liveScore,
                matchPhase
        );
        applyObservationMetadata(
                odds,
                event,
                matchState,
                trackedEventQuery ? SOURCE_TYPE_GQL_TRACKED_EVENT : SOURCE_TYPE_GQL_SCOREBOARD,
                trackedEventQuery ? SOURCE_CONFIDENCE_GQL_TRACKED_EVENT : SOURCE_CONFIDENCE_GQL_SCOREBOARD,
                externalEventId
        );
        return Optional.of(odds);
    }

    private List<MatchOdds> fetchGraphQlEvents() {
        if (!StringUtils.hasText(graphQlUrl)) {
            return List.of();
        }

        Optional<RegionHint> regionHint = fetchRegionHint();

        LinkedHashSet<String> segments = new LinkedHashSet<>();
        regionHint.map(RegionHint::segment).map(this::normalizeSegmentForQuery).filter(StringUtils::hasText).ifPresent(segments::add);
        if (!segmentCandidates.isEmpty()) {
            String normalized = normalizeSegmentForQuery(segmentCandidates.get(0));
            if (StringUtils.hasText(normalized)) {
                segments.add(normalized);
            }
        }
        if (segments.isEmpty()) {
            segments.add("fl");
        }

        LinkedHashSet<String> channels = new LinkedHashSet<>();
        regionHint.map(RegionHint::channel).map(this::normalizeChannel).filter(StringUtils::hasText).ifPresent(channels::add);
        for (String channel : channelCandidates) {
            String normalized = normalizeChannel(channel);
            if (StringUtils.hasText(normalized)) {
                channels.add(normalized);
            }
        }
        for (String segment : segments) {
            String inferred = inferOnlineChannel(segment);
            if (StringUtils.hasText(inferred)) {
                channels.add(inferred);
            }
        }
        channels.add("FLORIDA_ONLINE");
        channels.add("NEW_JERSEY_ONLINE");
        channels.add("VIRGINIA_ONLINE");
        channels.add("web");
        channels.add("mobile");

        for (String segment : segments) {
            for (String channel : channels) {
                List<String> ttlCompetitionIds = extractTtlCompetitionIdsFromTree(segment, channel);
                List<MatchOdds> ttlRows = queryGraphQlEvents(segment, channel, ttlCompetitionIds, ttlCompetitionOnly);
                if (!ttlRows.isEmpty()) {
                    return ttlRows;
                }
                List<MatchOdds> allRows = queryGraphQlEvents(segment, channel, List.of(), false);
                if (!allRows.isEmpty()) {
                    return allRows;
                }
            }
        }
        return List.of();
    }

    private List<MatchOdds> queryGraphQlEvents(String segment,
                                               String channel,
                                               List<String> ttlCompetitionIds,
                                               boolean ttlOnly) {
        ObjectNode payload = buildGraphQlPayload(
                segment,
                channel,
                ttlCompetitionIds,
                false,
                true,
                false,
                List.of()
        );
        String body;
        try {
            body = MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            return List.of();
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(graphQlUrl))
                    .header("accept", "application/json")
                    .header("content-type", "application/json")
                    .header("user-agent", "Mozilla/5.0 (compatible; TTLEliteSeries/2.0)")
                    .timeout(Duration.ofSeconds(18))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || !StringUtils.hasText(response.body())) {
                return List.of();
            }

            JsonNode root = MAPPER.readTree(response.body());
            JsonNode events = root.path("data").path("betSync").path("events").path("data");
            if (!events.isArray() || events.isEmpty()) {
                return List.of();
            }

            List<MatchOdds> out = new ArrayList<>();
            String sourceTag = "HARD_ROCK_GQL";
            for (JsonNode event : events) {
                Optional<MatchOdds> parsed = parseGraphQlEvent(event, sourceTag, channel, ttlOnly);
                parsed.ifPresent(out::add);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private ObjectNode buildGraphQlPayload(String segment,
                                           String channel,
                                           List<String> ttlCompetitionIds,
                                           boolean scoreboardMode,
                                           boolean includeMarketTypeFilter,
                                           boolean sortDescending,
                                           List<String> externalEventIds) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("operationName", "betSync");
        payload.put("query",
                "query betSync($filters:[Filter],$segment:String,$region:String,$language:String,$channel:String,$slice:Interval,$sports:[String],$sort:Sort,$marketTypes:[String]) {" +
                        " betSync(cmsSegment:$segment,region:$region,language:$language,channel:$channel,eventParams:{sportList:$sports,marketTypes:$marketTypes}) {" +
                        "  events(filters:$filters,slice:$slice,sports:$sports,sort:$sort) {" +
                        "   data { id compId compName name eventTime startTime endTime sport inplay isInplay displayed state resulted matchState markets(keyMarkets:false,marketTypes:$marketTypes) { id name type displayed state period resulted selection { id name type displayed suspended rootIdx odds resulted result } } }" +
                        "   count" +
                        "  }" +
                        " }" +
                        "}");

        ObjectNode variables = payload.putObject("variables");
        variables.put("channel", channel);
        variables.put("segment", normalizeSegmentForQuery(segment));
        variables.put("region", region);
        variables.put("language", language);

        ArrayNode sports = variables.putArray("sports");
        sports.add("TABLE_TENNIS");

        ArrayNode marketTypes = variables.putArray("marketTypes");
        if (includeMarketTypeFilter) {
            marketTypes.add("TABLE_TENNIS:FT:ML");
        }

        ArrayNode filters = variables.putArray("filters");
        if (!scoreboardMode) {
            filters.addObject().put("field", "displayed").put("value", "true");
        }
        filters.addObject().put("field", "outright").put("value", "false");
        if (ttlCompetitionIds != null && !ttlCompetitionIds.isEmpty()) {
            ObjectNode compFilter = filters.addObject();
            compFilter.put("field", "compId");
            ArrayNode values = compFilter.putArray("values");
            for (String id : ttlCompetitionIds) {
                if (StringUtils.hasText(id)) {
                    values.add(id.trim());
                }
            }
        }
        if (externalEventIds != null && !externalEventIds.isEmpty()) {
            ObjectNode idFilter = filters.addObject();
            idFilter.put("field", "id");
            ArrayNode values = idFilter.putArray("values");
            for (String id : externalEventIds) {
                if (StringUtils.hasText(id)) {
                    values.add(id.trim());
                }
            }
        }

        variables.putObject("slice")
                .put("from", 0)
                .put("to", maxEvents);

        variables.putObject("sort")
                .put("field", "eventTime")
                .put("descending", sortDescending);
        return payload;
    }

    private Optional<MatchOdds> parseGraphQlEvent(JsonNode event, String sourceTag, String channel, boolean ttlOnly) {
        String competitionName = firstText(event, "compName", "competitionName", "leagueName");
        String eventName = firstText(event, "name", "displayName", "title");
        String sport = firstText(event, "sport", "sportCode", "sportName");
        if (tableTennisOnly && !(containsTableTennisToken(sport) || containsTableTennisToken(competitionName) || containsTableTennisToken(eventName))) {
            return Optional.empty();
        }
        if (ttlOnly && !containsTtlCompetitionToken(competitionName)) {
            return Optional.empty();
        }

        JsonNode markets = firstArray(event, "markets", "betOffers", "marketGroups");
        if (markets == null || markets.isEmpty()) {
            return Optional.empty();
        }

        for (JsonNode market : markets) {
            JsonNode selections = firstArray(market, "selection", "selections", "outcomes", "runners");
            if (selections == null || selections.size() < 2) {
                continue;
            }

            ParsedSelection sideA = null;
            ParsedSelection sideB = null;
            List<ParsedSelection> extras = new ArrayList<>();
            for (JsonNode selection : selections) {
                Optional<ParsedSelection> parsed = extractGraphQlSelection(selection);
                if (parsed.isEmpty()) {
                    continue;
                }
                ParsedSelection s = parsed.get();
                if ("A".equalsIgnoreCase(s.sideType)) {
                    sideA = s;
                } else if ("B".equalsIgnoreCase(s.sideType)) {
                    sideB = s;
                } else {
                    extras.add(s);
                }
            }

            if (sideA == null && !extras.isEmpty()) {
                sideA = extras.remove(0);
            }
            if (sideB == null && !extras.isEmpty()) {
                sideB = extras.remove(0);
            }
            if (sideA == null || sideB == null) {
                continue;
            }
            if (sideA.decimalOdds <= 1.0 || sideB.decimalOdds <= 1.0) {
                continue;
            }

            boolean live = firstBoolean(event, "inplay", "isInplay");
            String startIso = "";
            JsonNode eventTimeNode = event.path("eventTime");
            if (eventTimeNode.isNumber()) {
                double epochMs = eventTimeNode.asDouble(0.0);
                if (epochMs > 0.0) {
                    startIso = Instant.ofEpochMilli((long) epochMs).toString();
                }
            }
            if (!StringUtils.hasText(startIso)) {
                String startTimeRaw = firstText(event, "startTime", "eventStart", "startDateTime", "startDate");
                if (StringUtils.hasText(startTimeRaw)) {
                    startIso = startTimeRaw.trim();
                }
            }
        MatchStateDetails matchState = extractMatchStateDetails(event.path("matchState"));
        String liveScore = StringUtils.hasText(matchState.liveScore())
                ? matchState.liveScore()
                : extractLiveScore(event, eventName);
        String matchPhase = isEventResulted(event)
                ? "FINISHED"
                : inferMatchPhase(live, startIso, liveScore);
        String externalEventId = firstText(event, "id", "eventId", "eventID");
        String source = withExternalEventId(sourceTag + ":" + channel, externalEventId);

        MatchOdds odds = new MatchOdds(
                sideA.name,
                sideB.name,
                sideA.decimalOdds,
                    sideB.decimalOdds,
                    StringUtils.hasText(eventName) ? eventName : sideA.name + " vs " + sideB.name,
                    StringUtils.hasText(competitionName) ? competitionName : "Table Tennis",
                live,
                startIso,
                source,
                liveScore,
                matchPhase
        );
        applyObservationMetadata(
                odds,
                event,
                matchState,
                SOURCE_TYPE_GQL_MARKET,
                SOURCE_CONFIDENCE_GQL_MARKET,
                externalEventId
        );
        return Optional.of(odds);
        }

        return Optional.empty();
    }

    private Optional<ParsedSelection> extractGraphQlSelection(JsonNode selection) {
        String name = firstText(selection, "name", "displayName", "participantName");
        if (!StringUtils.hasText(name)) {
            return Optional.empty();
        }
        if (!selection.path("displayed").asBoolean(true) || selection.path("suspended").asBoolean(false)) {
            return Optional.empty();
        }

        String oddsRaw = firstText(selection, "odds");
        double decimal = parseOddsToDecimal(oddsRaw);
        if (decimal <= 1.0) {
            return Optional.empty();
        }
        String sideType = firstText(selection, "type");
        return Optional.of(new ParsedSelection(name.trim(), decimal, sideType));
    }

    private void applyObservationMetadata(MatchOdds odds,
                                          JsonNode event,
                                          MatchStateDetails matchState,
                                          String sourceType,
                                          double sourceConfidence,
                                          String externalEventId) {
        if (odds == null) {
            return;
        }
        odds.setExternalEventId(sanitizeExternalEventId(externalEventId));
        odds.setSourceType(sourceType);
        odds.setSourceConfidence(sourceConfidence);
        odds.setDisplayed(firstBoolean(event, "displayed", "isDisplayed") || !event.has("displayed"));
        odds.setResulted(isEventResulted(event));
        odds.setMatchCompleted(matchState.matchCompleted());
        odds.setSourceFeedCode(matchState.sourceFeedCode());
        odds.setSourceFeedEventId(matchState.sourceFeedEventId());
        odds.setScoreDetail(matchState.scoreDetail());
    }

    private MatchStateDetails extractMatchStateDetails(JsonNode matchStateNode) {
        JsonNode candidate = unwrapMatchStateNode(matchStateNode);
        if (candidate == null || candidate.isMissingNode() || candidate.isNull()) {
            return MatchStateDetails.empty();
        }

        int gamesA = readInt(candidate, "gamesA", "setsA", "homeSets", "player1Sets", "aSets");
        int gamesB = readInt(candidate, "gamesB", "setsB", "awaySets", "player2Sets", "bSets");
        int pointsA = readInt(candidate, "pointsInCurrentGameA", "pointsA", "currentPointsA", "homePoints", "player1Points", "aPoints");
        int pointsB = readInt(candidate, "pointsInCurrentGameB", "pointsB", "currentPointsB", "awayPoints", "player2Points", "bPoints");
        boolean preMatch = firstBoolean(candidate, "preMatch", "isPreMatch", "prematch");
        boolean matchCompleted = firstBoolean(candidate, "matchCompleted", "resulted", "completed", "isResulted", "isCompleted");

        String liveScore = "";
        if (!(preMatch && gamesA <= 0 && gamesB <= 0 && pointsA <= 0 && pointsB <= 0) && gamesA >= 0 && gamesB >= 0) {
            liveScore = pointsA >= 0 && pointsB >= 0
                    ? gamesA + "-" + gamesB + " (" + pointsA + "-" + pointsB + ")"
                    : gamesA + "-" + gamesB;
        }
        if (!StringUtils.hasText(liveScore)) {
            String flatScore = firstText(candidate, "score", "setScore", "currentScore", "displayScore", "scoreDisplay");
            if (looksLikeScore(flatScore)) {
                liveScore = normalizeScore(flatScore);
            }
        }

        String scoreDetail = extractScoreDetailFromMatchStateObject(candidate);
        String sourceFeedCode = firstText(candidate, "sourceFeedCode", "feedCode", "sourceCode");
        String sourceFeedEventId = firstText(candidate, "sourceFeedEventId", "feedEventId", "sourceEventId");

        return new MatchStateDetails(
                StringUtils.hasText(liveScore) ? normalizeScore(liveScore) : "",
                StringUtils.hasText(scoreDetail) ? scoreDetail.trim() : "",
                matchCompleted,
                safeTrim(sourceFeedCode),
                safeTrim(sourceFeedEventId)
        );
    }

    private JsonNode unwrapMatchStateNode(JsonNode matchStateNode) {
        if (matchStateNode == null || matchStateNode.isMissingNode() || matchStateNode.isNull()) {
            return null;
        }
        JsonNode candidate = matchStateNode;
        if (candidate.isTextual()) {
            String raw = candidate.asText("");
            if (!StringUtils.hasText(raw)) {
                return null;
            }
            if (!(raw.trim().startsWith("{") || raw.trim().startsWith("["))) {
                return null;
            }
            try {
                candidate = MAPPER.readTree(raw);
            } catch (Exception ignore) {
                return null;
            }
        }
        if (candidate.isObject() && candidate.size() == 1) {
            JsonNode nested = candidate.elements().next();
            if (nested != null && nested.isObject()) {
                candidate = nested;
            }
        }
        return candidate;
    }

    private String extractScoreDetailFromMatchStateObject(JsonNode candidate) {
        if (candidate == null || !candidate.isObject()) {
            return "";
        }
        Map<Integer, int[]> byGame = new TreeMap<>();
        candidate.fields().forEachRemaining(entry -> {
            Matcher matcher = MATCH_STATE_GAME_SCORE_PATTERN.matcher(entry.getKey());
            if (!matcher.matches()) {
                return;
            }
            int gameNumber;
            try {
                gameNumber = Integer.parseInt(matcher.group(1));
            } catch (Exception ignore) {
                return;
            }
            int score = parseNullableInt(entry.getValue());
            if (score < 0) {
                return;
            }
            int[] pair = byGame.computeIfAbsent(gameNumber, unused -> new int[]{-1, -1});
            if ("A".equalsIgnoreCase(matcher.group(2))) {
                pair[0] = score;
            } else {
                pair[1] = score;
            }
        });

        if (byGame.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Integer, int[]> entry : byGame.entrySet()) {
            int[] pair = entry.getValue();
            if (pair[0] >= 0 && pair[1] >= 0) {
                parts.add(pair[0] + "-" + pair[1]);
            }
        }
        if (parts.isEmpty()) {
            return "";
        }
        return String.join(", ", parts);
    }

    private int parseNullableInt(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return -1;
        }
        if (value.isInt() || value.isLong()) {
            return value.asInt();
        }
        if (value.isTextual()) {
            try {
                return Integer.parseInt(value.asText("").trim());
            } catch (Exception ignore) {
                return -1;
            }
        }
        return -1;
    }

    private String sanitizeExternalEventId(String externalEventId) {
        if (!StringUtils.hasText(externalEventId)) {
            return "";
        }
        return externalEventId.trim().replaceAll("[^A-Za-z0-9:_-]", "");
    }

    private String safeTrim(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private List<String> extractTtlCompetitionIdsFromTree(String segment, String channel) {
        String body = fetchPublicTreeBody(segment, channel);
        if (!StringUtils.hasText(body)) {
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode sports = root.path("data").path("betSync").path("sports");
            if (!sports.isArray()) {
                return List.of();
            }
            LinkedHashSet<String> out = new LinkedHashSet<>();
            for (JsonNode sport : sports) {
                if (!isTableTennisSport(sport)) {
                    continue;
                }
                JsonNode categories = firstArray(sport, "categories", "children");
                if (categories == null) {
                    continue;
                }
                for (JsonNode category : categories) {
                    JsonNode competitions = firstArray(category, "competitions", "leagues", "children");
                    if (competitions == null) {
                        continue;
                    }
                    for (JsonNode competition : competitions) {
                        String competitionName = firstText(competition, "name", "displayName", "title");
                        if (!containsTtlCompetitionToken(competitionName)) {
                            continue;
                        }
                        String id = firstText(competition, "id", "compId");
                        if (StringUtils.hasText(id)) {
                            out.add(id);
                        }
                    }
                }
            }
            return new ArrayList<>(out);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String fetchPublicTreeBody(String segment, String channel) {
        String url = publicTreeUrl;
        String separator = url.contains("?") ? "&" : "?";
        String withParams = url
                + separator
                + "segment="
                + encodeQueryValue(segment)
                + "&channel="
                + encodeQueryValue(channel);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(withParams))
                    .header("accept", "application/json")
                    .header("user-agent", "Mozilla/5.0 (compatible; TTLEliteSeries/2.0)")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || !StringUtils.hasText(response.body())) {
                return "";
            }
            return response.body();
        } catch (Exception e) {
            return "";
        }
    }

    private Optional<RegionHint> fetchRegionHint() {
        if (!StringUtils.hasText(whatsMySegmentUrl)) {
            return Optional.empty();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(whatsMySegmentUrl))
                    .header("accept", "application/json")
                    .header("user-agent", "Mozilla/5.0 (compatible; TTLEliteSeries/2.0)")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || !StringUtils.hasText(response.body())) {
                return Optional.empty();
            }
            JsonNode root = MAPPER.readTree(response.body());
            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                data = root;
            }
            String segmentCode = firstText(data, "segmentCode", "segment", "stateCode");
            String channelCode = firstText(data, "channelCode", "channel");
            if (!StringUtils.hasText(segmentCode) && !StringUtils.hasText(channelCode)) {
                return Optional.empty();
            }
            return Optional.of(new RegionHint(segmentCode, channelCode));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String normalizeChannel(String channel) {
        if (!StringUtils.hasText(channel)) {
            return "";
        }
        String normalized = channel.trim();
        if ("web".equalsIgnoreCase(normalized) || "mobile".equalsIgnoreCase(normalized)) {
            return normalized.toLowerCase(Locale.ROOT);
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private String normalizeSegmentForQuery(String segment) {
        if (!StringUtils.hasText(segment)) {
            return "";
        }
        String normalized = segment.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("r.")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private String inferOnlineChannel(String normalizedSegment) {
        return switch (normalizeSegmentForQuery(normalizedSegment)) {
            case "fl" -> "FLORIDA_ONLINE";
            case "nj" -> "NEW_JERSEY_ONLINE";
            case "va" -> "VIRGINIA_ONLINE";
            case "az" -> "ARIZONA_ONLINE";
            case "co" -> "COLORADO_ONLINE";
            case "il" -> "ILLINOIS_ONLINE";
            case "in" -> "INDIANA_ONLINE";
            case "ia" -> "IOWA_ONLINE";
            case "pa" -> "PENNSYLVANIA_ONLINE";
            case "mi" -> "MICHIGAN_ONLINE";
            case "tn" -> "TENNESSEE_ONLINE";
            case "oh", "od" -> "OHIO_ONLINE";
            default -> "";
        };
    }

    private List<MatchOdds> fetchOfficialPublicTree() {
        for (String segment : segmentCandidates) {
            for (String channel : channelCandidates) {
                List<MatchOdds> rows = fetchOfficialPublicTree(segment, channel);
                if (!rows.isEmpty()) {
                    return rows;
                }
            }
        }
        return List.of();
    }

    private List<MatchOdds> fetchOfficialPublicTreeScoreboard() {
        List<MatchOdds> merged = new ArrayList<>();
        for (String segment : segmentCandidates) {
            for (String channel : channelCandidates) {
                List<MatchOdds> rows = fetchOfficialPublicTreeScoreboard(segment, channel);
                if (!rows.isEmpty()) {
                    merged.addAll(rows);
                }
            }
        }
        if (merged.isEmpty()) {
            return List.of();
        }
        return dedupeScoreboard(merged);
    }

    private List<MatchOdds> fetchOfficialPublicTreeScoreboard(String segment, String channel) {
        String body = fetchPublicTreeBody(segment, channel);
        if (!StringUtils.hasText(body)) {
            return List.of();
        }
        try {
            return parseOfficialTreeScoreboardJson(body, "HARD_ROCK_PUBLIC_SCORE");
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<MatchOdds> fetchOfficialPublicTree(String segment, String channel) {
        String body = fetchPublicTreeBody(segment, channel);
        if (!StringUtils.hasText(body)) {
            return List.of();
        }
        try {
            return parseOfficialTreeJson(body, "HARD_ROCK_PUBLIC");
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<MatchOdds> parseOfficialTreeJson(String body, String sourceTag) throws IOException {
        JsonNode root = MAPPER.readTree(body);
        JsonNode sports = root.path("data").path("betSync").path("sports");
        if (!sports.isArray() || sports.isEmpty()) {
            return List.of();
        }

        List<MatchOdds> out = new ArrayList<>();
        for (JsonNode sport : sports) {
            String sportName = textOf(sport, "name");
            if (tableTennisOnly && !isTableTennisSport(sport)) {
                continue;
            }
            parseSportNode(sport, sportName, sourceTag, out);
        }
        return out;
    }

    private List<MatchOdds> parseOfficialTreeScoreboardJson(String body, String sourceTag) throws IOException {
        JsonNode root = MAPPER.readTree(body);
        JsonNode sports = root.path("data").path("betSync").path("sports");
        if (!sports.isArray() || sports.isEmpty()) {
            return List.of();
        }

        List<MatchOdds> out = new ArrayList<>();
        for (JsonNode sport : sports) {
            String sportName = textOf(sport, "name");
            if (tableTennisOnly && !isTableTennisSport(sport)) {
                continue;
            }
            parseSportNodeScoreboard(sport, sportName, sourceTag, out);
        }
        return out;
    }

    private void parseSportNodeScoreboard(JsonNode sportNode, String sportName, String sourceTag, List<MatchOdds> out) {
        JsonNode competitions = firstArray(sportNode, "competitions", "leagues", "children");
        if (competitions != null) {
            parseCompetitionNodeArrayScoreboard(competitions, sportName, sourceTag, out);
            return;
        }

        JsonNode categories = firstArray(sportNode, "categories", "children");
        if (categories == null) {
            return;
        }
        for (JsonNode category : categories) {
            JsonNode nestedCompetitions = firstArray(category, "competitions", "leagues", "children");
            if (nestedCompetitions == null) {
                continue;
            }
            parseCompetitionNodeArrayScoreboard(nestedCompetitions, sportName, sourceTag, out);
        }
    }

    private void parseCompetitionNodeArrayScoreboard(JsonNode competitions, String sportName, String sourceTag, List<MatchOdds> out) {
        for (JsonNode competition : competitions) {
            String competitionName = firstText(competition, "name", "displayName", "title");
            JsonNode events = firstArray(competition, "events", "children", "matches");
            if (events == null) {
                logCountOnlyEventsNode("scoreboard", competition);
                continue;
            }

            for (JsonNode event : events) {
                Optional<MatchOdds> odds = parseEventNodeScoreboard(event, sportName, competitionName, sourceTag);
                odds.ifPresent(out::add);
            }
        }
    }

    private Optional<MatchOdds> parseEventNodeScoreboard(JsonNode event, String sportName, String competitionName, String sourceTag) {
        String eventName = firstText(event, "name", "displayName", "title");
        if (tableTennisOnly && !(containsTableTennisToken(sportName) || containsTableTennisToken(competitionName) || containsTableTennisToken(eventName))) {
            return Optional.empty();
        }

        String[] players = extractPlayers(event, eventName);
        if (players[0].isBlank() || players[1].isBlank()) {
            players = extractPlayersFromMarkets(event);
        }
        if (players[0].isBlank() || players[1].isBlank()) {
            return Optional.empty();
        }

        boolean live = firstBoolean(event,
                "live",
                "isLive",
                "inPlay",
                "isInPlay",
                "isStarted"
        ) || containsLiveToken(firstText(event, "status", "state", "phase"));

        String start = firstText(event,
                "eventStart",
                "startTime",
                "startDate",
                "startDateTime",
                "scheduledStartTime"
        );
        String liveScore = extractLiveScore(event, eventName);
        String matchPhase = isEventResulted(event)
                ? "FINISHED"
                : inferMatchPhase(live, start, liveScore);
        String externalEventId = firstText(event, "id", "eventId", "eventID");
        MatchOdds odds = new MatchOdds(
                players[0],
                players[1],
                2.0,
                2.0,
                StringUtils.hasText(eventName) ? eventName : players[0] + " vs " + players[1],
                StringUtils.hasText(competitionName) ? competitionName : sportName,
                live,
                start,
                withExternalEventId(sourceTag, externalEventId),
                liveScore,
                matchPhase
        );
        odds.setExternalEventId(sanitizeExternalEventId(externalEventId));
        odds.setSourceType(SOURCE_TYPE_PUBLIC_SCOREBOARD);
        odds.setSourceConfidence(SOURCE_CONFIDENCE_PUBLIC_SCOREBOARD);
        odds.setDisplayed(firstBoolean(event, "displayed", "isDisplayed") || !event.has("displayed"));
        odds.setResulted(isEventResulted(event));
        return Optional.of(odds);
    }

    private void parseSportNode(JsonNode sportNode, String sportName, String sourceTag, List<MatchOdds> out) {
        JsonNode competitions = firstArray(sportNode, "competitions", "leagues", "children");
        if (competitions != null) {
            parseCompetitionNodeArray(competitions, sportName, sourceTag, out);
            return;
        }

        JsonNode categories = firstArray(sportNode, "categories", "children");
        if (categories == null) {
            return;
        }
        for (JsonNode category : categories) {
            JsonNode nestedCompetitions = firstArray(category, "competitions", "leagues", "children");
            if (nestedCompetitions == null) {
                continue;
            }
            parseCompetitionNodeArray(nestedCompetitions, sportName, sourceTag, out);
        }
    }

    private void parseCompetitionNodeArray(JsonNode competitions, String sportName, String sourceTag, List<MatchOdds> out) {
        for (JsonNode competition : competitions) {
            String competitionName = firstText(competition, "name", "displayName", "title");
            JsonNode events = firstArray(competition, "events", "children", "matches");
            if (events == null) {
                logCountOnlyEventsNode("market", competition);
                continue;
            }

            for (JsonNode event : events) {
                Optional<MatchOdds> odds = parseEventNode(event, sportName, competitionName, sourceTag);
                odds.ifPresent(out::add);
            }
        }
    }

    private Optional<MatchOdds> parseEventNode(JsonNode event, String sportName, String competitionName, String sourceTag) {
        String eventName = firstText(event, "name", "displayName", "title");
        if (tableTennisOnly && !(containsTableTennisToken(sportName) || containsTableTennisToken(competitionName) || containsTableTennisToken(eventName))) {
            return Optional.empty();
        }

        String[] players = extractPlayers(event, eventName);
        if (players[0].isBlank() || players[1].isBlank()) {
            return Optional.empty();
        }

        double[] odds = extractDecimalOdds(event);
        if (odds[0] <= 1.0 || odds[1] <= 1.0) {
            return Optional.empty();
        }

        boolean live = firstBoolean(event,
                "live",
                "isLive",
                "inPlay",
                "isInPlay",
                "isStarted"
        ) || containsLiveToken(firstText(event, "status", "state", "phase"));

        String start = firstText(event,
                "eventStart",
                "startTime",
                "startDate",
                "startDateTime",
                "scheduledStartTime"
        );
        String liveScore = extractLiveScore(event, eventName);
        String matchPhase = isEventResulted(event)
                ? "FINISHED"
                : inferMatchPhase(live, start, liveScore);
        String externalEventId = firstText(event, "id", "eventId", "eventID");
        MatchOdds row = new MatchOdds(
                players[0],
                players[1],
                odds[0],
                odds[1],
                StringUtils.hasText(eventName) ? eventName : players[0] + " vs " + players[1],
                StringUtils.hasText(competitionName) ? competitionName : sportName,
                live,
                start,
                withExternalEventId(sourceTag, externalEventId),
                liveScore,
                matchPhase
        );
        row.setExternalEventId(sanitizeExternalEventId(externalEventId));
        row.setSourceType(SOURCE_TYPE_PUBLIC_MARKET);
        row.setSourceConfidence(SOURCE_CONFIDENCE_PUBLIC_MARKET);
        row.setDisplayed(firstBoolean(event, "displayed", "isDisplayed") || !event.has("displayed"));
        row.setResulted(isEventResulted(event));
        return Optional.of(row);
    }

    private void logCountOnlyEventsNode(String mode, JsonNode competition) {
        if (competition == null || competition.isMissingNode() || competition.isNull()) {
            return;
        }
        JsonNode eventsNode = competition.path("events");
        if (!eventsNode.isObject() || eventsNode.has("data") || eventsNode.has("children")) {
            return;
        }
        int count = readInt(eventsNode, "count", "total");
        if (count <= 0) {
            return;
        }
        String competitionName = firstText(competition, "name", "displayName", "title");
        log.debug("Hard Rock public tree {} parser saw count-only events node for competition '{}' (count={}); treating tree response as discovery/health only.",
                mode,
                StringUtils.hasText(competitionName) ? competitionName : "unknown",
                count);
    }

    private String[] extractPlayers(JsonNode event, String eventName) {
        JsonNode participants = firstArray(event, "participants", "competitors", "runners");
        if (participants != null && participants.size() >= 2) {
            String a = firstText(participants.get(0), "name", "displayName", "participantName");
            String b = firstText(participants.get(1), "name", "displayName", "participantName");
            if (StringUtils.hasText(a) && StringUtils.hasText(b)) {
                return new String[]{a.trim(), b.trim()};
            }
        }

        if (StringUtils.hasText(eventName)) {
            String normalized = eventName.replace('\u2013', '-').replace('\u2014', '-').trim();
            Matcher split = VS_SPLIT.matcher(normalized);
            if (split.find()) {
                int idx = split.start();
                String left = normalized.substring(0, idx).trim();
                String right = normalized.substring(split.end()).trim();
                if (StringUtils.hasText(left) && StringUtils.hasText(right)) {
                    return new String[]{left, right};
                }
            }
        }

        return new String[]{"", ""};
    }

    private String[] extractPlayersFromMarkets(JsonNode event) {
        JsonNode markets = firstArray(event, "markets", "betOffers", "marketGroups");
        if (markets == null) {
            return new String[]{"", ""};
        }
        for (JsonNode market : markets) {
            JsonNode selections = firstArray(market, "selection", "selections", "outcomes", "runners");
            if (selections == null || selections.size() < 2) {
                continue;
            }
            String a = firstText(selections.get(0), "name", "displayName", "participantName");
            String b = firstText(selections.get(1), "name", "displayName", "participantName");
            if (StringUtils.hasText(a) && StringUtils.hasText(b)) {
                return new String[]{a.trim(), b.trim()};
            }
        }
        return new String[]{"", ""};
    }

    private double[] extractDecimalOdds(JsonNode event) {
        JsonNode markets = firstArray(event, "markets", "betOffers", "marketGroups");
        if (markets == null) {
            return new double[]{0.0, 0.0};
        }

        for (JsonNode market : markets) {
            JsonNode selections = firstArray(market, "selections", "outcomes", "runners");
            if (selections == null || selections.size() < 2) {
                continue;
            }

            double a = extractSelectionDecimal(selections.get(0));
            double b = extractSelectionDecimal(selections.get(1));
            if (a > 1.0 && b > 1.0) {
                return new double[]{a, b};
            }
        }

        return new double[]{0.0, 0.0};
    }

    private double extractSelectionDecimal(JsonNode selection) {
        double direct = firstDouble(selection,
                "decimalOdds",
                "oddsDecimal",
                "priceDecimal",
                "decimal"
        );
        if (direct > 1.0) {
            return direct;
        }

        JsonNode odds = selection.path("odds");
        if (!odds.isMissingNode()) {
            double fromOdds = firstDouble(odds, "decimal", "decimalOdds", "oddsDecimal");
            if (fromOdds > 1.0) {
                return fromOdds;
            }
        }

        JsonNode price = selection.path("price");
        if (!price.isMissingNode()) {
            double fromPrice = firstDouble(price, "decimal", "decimalOdds", "oddsDecimal");
            if (fromPrice > 1.0) {
                return fromPrice;
            }
            String american = firstText(price, "american", "americanOdds", "us");
            if (StringUtils.hasText(american)) {
                try {
                    return americanToDecimal(american);
                } catch (Exception ignore) {
                    // ignored
                }
            }
        }

        String americanString = firstText(selection, "americanOdds", "usOdds", "oddsAmerican", "odds");
        if (StringUtils.hasText(americanString) && (americanString.startsWith("+") || americanString.startsWith("-"))) {
            try {
                return americanToDecimal(americanString);
            } catch (Exception ignore) {
                // ignored
            }
        }

        return 0.0;
    }

    private List<MatchOdds> fetchFromCustomJson(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("accept", "application/json")
                    .header("user-agent", "Mozilla/5.0 (compatible; TTLEliteSeries/2.0)")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || !StringUtils.hasText(response.body())) {
                return List.of();
            }
            return parseCustomJson(response.body());
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<MatchOdds> parseCustomJson(String body) {
        List<MatchOdds> out = new ArrayList<>();
        Pattern p = Pattern.compile(
                "\\\"playerA\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*?\\\"playerB\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*?\\\"oddsA\\\"\\s*:\\s*([0-9.]+).*?\\\"oddsB\\\"\\s*:\\s*([0-9.]+)",
                Pattern.DOTALL
        );
        Matcher m = p.matcher(body);
        while (m.find()) {
            MatchOdds odds = new MatchOdds(
                    m.group(1),
                    m.group(2),
                    Double.parseDouble(m.group(3)),
                    Double.parseDouble(m.group(4)),
                    m.group(1) + " vs " + m.group(2),
                    "Table Tennis",
                    false,
                    null,
                    "HARD_ROCK_CUSTOM_JSON"
            );
            odds.setSourceType(SOURCE_TYPE_CUSTOM_JSON);
            odds.setSourceConfidence(SOURCE_CONFIDENCE_CUSTOM_JSON);
            out.add(odds);
        }
        return out;
    }

    private List<MatchOdds> scrapeHtmlFallback() {
        List<MatchOdds> out = new ArrayList<>();
        try {
            Document doc = Jsoup.connect(homeUrl)
                    .userAgent("Mozilla/5.0")
                    .timeout(20_000)
                    .get();
            Pattern rowPattern = Pattern.compile(
                    "([A-Za-zÀ-ž'\\-]+\\s+[A-Za-zÀ-ž'\\-]+).*?([A-Za-zÀ-ž'\\-]+\\s+[A-Za-zÀ-ž'\\-]+).*?([+\\-]\\d+)\\s+([+\\-]\\d+)"
            );

            for (Element row : doc.select("[role=row]")) {
                String text = row.text();
                if (!StringUtils.hasText(text)) {
                    continue;
                }
                if (tableTennisOnly && !containsTableTennisToken(text)) {
                    continue;
                }

                Matcher mm = rowPattern.matcher(text);
                if (!mm.find()) {
                    continue;
                }

                String playerA = mm.group(1).trim();
                String playerB = mm.group(2).trim();
                double oddsA = americanToDecimal(mm.group(3));
                double oddsB = americanToDecimal(mm.group(4));

                MatchOdds odds = new MatchOdds(
                        playerA,
                        playerB,
                        oddsA,
                        oddsB,
                        playerA + " vs " + playerB,
                        "Hard Rock Sportsbook",
                        containsLiveToken(text),
                        null,
                        "HARD_ROCK_HTML",
                        extractScoreFromText(text),
                        inferMatchPhase(containsLiveToken(text), null, extractScoreFromText(text))
                );
                odds.setSourceType(SOURCE_TYPE_HTML_FALLBACK);
                odds.setSourceConfidence(SOURCE_CONFIDENCE_HTML);
                out.add(odds);
            }
        } catch (Exception ignore) {
            // ignored: fallback intentionally best-effort
        }
        return out;
    }

    private List<MatchOdds> dedupe(List<MatchOdds> input) {
        Set<String> seen = new LinkedHashSet<>();
        List<MatchOdds> out = new ArrayList<>();
        for (MatchOdds row : input) {
            String key = normalizeKey(row.getPlayerA(), row.getPlayerB(), row.getOddsA(), row.getOddsB());
            if (seen.add(key)) {
                out.add(row);
            }
        }
        return out;
    }

    private List<MatchOdds> dedupeScoreboard(List<MatchOdds> input) {
        List<MatchOdds> out = new ArrayList<>();
        if (input == null || input.isEmpty()) {
            return out;
        }
        java.util.Map<String, MatchOdds> byKey = new java.util.LinkedHashMap<>();
        for (MatchOdds row : input) {
            if (row == null || !StringUtils.hasText(row.getPlayerA()) || !StringUtils.hasText(row.getPlayerB())) {
                continue;
            }
            String key = normalizeScoreboardKey(row);
            MatchOdds existing = byKey.get(key);
            if (existing == null || preferScoreboardCandidate(row, existing)) {
                byKey.put(key, row);
            }
        }
        out.addAll(byKey.values());
        return out;
    }

    private String normalizeScoreboardKey(MatchOdds row) {
        String a = row.getPlayerA() == null ? "" : row.getPlayerA().trim().toLowerCase(Locale.ROOT);
        String b = row.getPlayerB() == null ? "" : row.getPlayerB().trim().toLowerCase(Locale.ROOT);
        if (a.compareTo(b) > 0) {
            String tmp = a;
            a = b;
            b = tmp;
        }
        String startBucket = "na";
        if (StringUtils.hasText(row.getStartTimeIso())) {
            String start = row.getStartTimeIso().trim();
            if (start.length() >= 16 && (start.charAt(10) == 'T' || start.charAt(10) == 't')) {
                startBucket = start.substring(0, 16).toLowerCase(Locale.ROOT);
            } else if (start.length() >= 10 && start.charAt(4) == '-' && start.charAt(7) == '-') {
                startBucket = start.substring(0, 10).toLowerCase(Locale.ROOT);
            } else {
                startBucket = start.toLowerCase(Locale.ROOT);
            }
        }
        String matchupKey = a + "|" + b + "|" + startBucket;
        if (!"na".equals(startBucket)) {
            return matchupKey;
        }

        String externalEventId = extractExternalEventIdFromSource(row.getSource());
        if (StringUtils.hasText(externalEventId)) {
            return "event|" + externalEventId.trim().toLowerCase(Locale.ROOT);
        }
        return matchupKey;
    }

    private List<String> normalizeExternalEventIds(Collection<String> externalEventIds) {
        if (externalEventIds == null || externalEventIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String raw : externalEventIds) {
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            String cleaned = raw.trim().replaceAll("[^A-Za-z0-9:_-]", "");
            if (StringUtils.hasText(cleaned)) {
                normalized.add(cleaned);
            }
        }
        if (normalized.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(normalized);
    }

    private String withExternalEventId(String baseSource, String externalEventId) {
        if (!StringUtils.hasText(baseSource)) {
            return "";
        }
        if (!StringUtils.hasText(externalEventId)) {
            return baseSource;
        }
        String cleaned = externalEventId.trim().replaceAll("[^A-Za-z0-9:_-]", "");
        if (!StringUtils.hasText(cleaned)) {
            return baseSource;
        }
        if (baseSource.contains("|event=" + cleaned)) {
            return baseSource;
        }
        return baseSource + "|event=" + cleaned;
    }

    private String extractExternalEventIdFromSource(String source) {
        if (!StringUtils.hasText(source)) {
            return "";
        }
        Matcher matcher = SOURCE_EVENT_ID_PATTERN.matcher(source.trim());
        if (!matcher.find()) {
            return "";
        }
        String raw = matcher.group(1);
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        return raw.replaceAll("[^A-Za-z0-9:_-]", "");
    }

    private boolean preferScoreboardCandidate(MatchOdds candidate, MatchOdds current) {
        int candidateRank = scoreboardSignalRank(candidate);
        int currentRank = scoreboardSignalRank(current);
        if (candidateRank != currentRank) {
            return candidateRank > currentRank;
        }
        int confidenceOrder = Double.compare(candidate.getSourceConfidence(), current.getSourceConfidence());
        if (confidenceOrder != 0) {
            return confidenceOrder > 0;
        }
        int candidateSourceRank = scoreboardSourceRank(candidate.getSource());
        int currentSourceRank = scoreboardSourceRank(current.getSource());
        if (candidateSourceRank != currentSourceRank) {
            return candidateSourceRank > currentSourceRank;
        }
        return candidate.getTimestamp() > current.getTimestamp();
    }

    private int scoreboardSignalRank(MatchOdds row) {
        int score = 0;
        if (StringUtils.hasText(row.getLiveScore())) {
            score += 3;
        }
        if (isFinishedState(row.getMatchPhase())) {
            score += 3;
        }
        if (row.isLive()) {
            score += 1;
        }
        return score;
    }

    private int scoreboardSourceRank(String source) {
        if (!StringUtils.hasText(source)) {
            return 0;
        }
        String normalized = source.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("HARD_ROCK_GQL_SCORE") && normalized.contains("_ONLINE")) {
            return 5;
        }
        if (normalized.contains("HARD_ROCK_PUBLIC_SCORE")) {
            return 4;
        }
        if (normalized.contains("HARD_ROCK_GQL_SCORE")) {
            return 3;
        }
        if (normalized.contains("HARD_ROCK_SCORE")) {
            return 2;
        }
        if (normalized.contains("SCORE")) {
            return 1;
        }
        return 0;
    }

    private String normalizeKey(String playerA, String playerB, double oddsA, double oddsB) {
        String a = playerA == null ? "" : playerA.trim().toLowerCase(Locale.ROOT);
        String b = playerB == null ? "" : playerB.trim().toLowerCase(Locale.ROOT);
        return a + "|" + b + "|" + Math.round(oddsA * 1000.0) + "|" + Math.round(oddsB * 1000.0);
    }

    private static String readSystemOverride(String key, String configuredValue, String fallback) {
        String override = System.getProperty(key);
        if (StringUtils.hasText(override)) {
            return override.trim();
        }
        if (StringUtils.hasText(configuredValue)) {
            return configuredValue.trim();
        }
        return fallback;
    }

    private static List<String> mergeCandidates(String primary, String fallbacksRaw, String defaultValue) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (StringUtils.hasText(primary)) {
            out.add(primary.trim());
        }
        if (StringUtils.hasText(fallbacksRaw)) {
            for (String token : fallbacksRaw.split(",")) {
                if (StringUtils.hasText(token)) {
                    out.add(token.trim());
                }
            }
        }
        if (out.isEmpty()) {
            out.add(defaultValue);
        }
        return Collections.unmodifiableList(new ArrayList<>(out));
    }

    private static String encodeQueryValue(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static JsonNode firstArray(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        for (String field : fields) {
            JsonNode candidate = node.path(field);
            if (candidate.isArray()) {
                return candidate;
            }
        }
        return null;
    }

    private static String firstText(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode()) {
            return "";
        }
        for (String field : fields) {
            String value = textOf(node, field);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return "";
        }
        return v.asText("").trim();
    }

    private static boolean firstBoolean(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode()) {
            return false;
        }
        for (String field : fields) {
            JsonNode v = node.path(field);
            if (v.isBoolean()) {
                return v.asBoolean(false);
            }
            if (v.isTextual()) {
                String txt = v.asText("").trim().toLowerCase(Locale.ROOT);
                if (Arrays.asList("true", "yes", "live", "in_play", "inplay").contains(txt)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double firstDouble(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode()) {
            return 0.0;
        }
        for (String field : fields) {
            JsonNode v = node.path(field);
            if (v.isNumber()) {
                return v.asDouble();
            }
            if (v.isTextual()) {
                try {
                    return Double.parseDouble(v.asText().trim());
                } catch (NumberFormatException ignore) {
                    // ignore and continue
                }
            }
        }
        return 0.0;
    }

    private double parseOddsToDecimal(String raw) {
        if (!StringUtils.hasText(raw)) {
            return 0.0;
        }
        String s = raw.trim();
        try {
            if (s.startsWith("+") || s.startsWith("-")) {
                return americanToDecimal(s);
            }
            if (s.contains("/")) {
                String[] p = s.split("/");
                if (p.length == 2) {
                    double num = Double.parseDouble(p[0].trim());
                    double den = Double.parseDouble(p[1].trim());
                    if (den != 0.0) {
                        return 1.0 + (num / den);
                    }
                }
            }
            double decimal = Double.parseDouble(s);
            if (decimal > 1.0) {
                return decimal;
            }
        } catch (Exception ignore) {
            // ignored
        }
        return 0.0;
    }

    private boolean isTableTennisSport(JsonNode sport) {
        String sportCode = textOf(sport, "code");
        if ("TABLE_TENNIS".equalsIgnoreCase(sportCode)) {
            return true;
        }
        return containsTableTennisToken(firstText(sport, "name", "displayName", "title"));
    }

    private boolean containsTtlCompetitionToken(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String s = value.toLowerCase(Locale.ROOT);
        return s.contains("tt elite") || s.contains("ttl elite") || s.contains("elite series");
    }

    private boolean containsTableTennisToken(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String s = value.toLowerCase(Locale.ROOT);
        return s.contains("table tennis") || s.contains("tabletennis") || s.contains("tt elite") || s.contains("ttl elite") || s.contains("elite series");
    }

    private boolean containsLiveToken(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String s = value.toLowerCase(Locale.ROOT);
        return s.contains(" live ") || s.startsWith("live") || s.endsWith("live") || s.contains("in play") || s.contains("inplay");
    }

    private String extractLiveScore(JsonNode event, String eventName) {
        String score = firstText(event,
                "liveScore",
                "setScore",
                "currentScore",
                "scoreDisplay"
        );
        if (looksLikeScore(score)) {
            return normalizeScore(score);
        }

        String fromMatchState = extractScoreFromMatchState(event.path("matchState"));
        if (looksLikeScore(fromMatchState)) {
            return normalizeScore(fromMatchState);
        }

        String fromScoreObject = extractScoreFromObject(event.path("score"));
        if (looksLikeScore(fromScoreObject)) {
            return normalizeScore(fromScoreObject);
        }

        String fromResultObject = extractScoreFromObject(event.path("result"));
        if (looksLikeScore(fromResultObject)) {
            return normalizeScore(fromResultObject);
        }

        String fromScoreboard = extractScoreFromObject(event.path("scoreboard"));
        if (looksLikeScore(fromScoreboard)) {
            return normalizeScore(fromScoreboard);
        }

        String fromLiveData = extractScoreFromObject(event.path("liveData"));
        if (looksLikeScore(fromLiveData)) {
            return normalizeScore(fromLiveData);
        }

        return extractScoreFromText(eventName);
    }

    private String extractScoreFromMatchState(JsonNode matchStateNode) {
        if (matchStateNode == null || matchStateNode.isMissingNode() || matchStateNode.isNull()) {
            return "";
        }
        if (matchStateNode.isTextual()) {
            String raw = matchStateNode.asText("");
            if (!StringUtils.hasText(raw)) {
                return "";
            }
            if (raw.trim().startsWith("{") || raw.trim().startsWith("[")) {
                try {
                    JsonNode parsed = MAPPER.readTree(raw);
                    return extractScoreFromMatchStateObject(parsed);
                } catch (Exception ignore) {
                    // ignore invalid embedded json
                }
            }
            if (looksLikeScore(raw)) {
                return normalizeScore(raw);
            }
            return "";
        }
        return extractScoreFromMatchStateObject(matchStateNode);
    }

    private String extractScoreFromMatchStateObject(JsonNode stateNode) {
        if (stateNode == null || stateNode.isMissingNode() || stateNode.isNull()) {
            return "";
        }
        JsonNode candidate = stateNode;
        if (candidate.isObject() && candidate.size() == 1) {
            JsonNode nested = candidate.elements().next();
            if (nested != null && nested.isObject()) {
                candidate = nested;
            }
        }

        int gamesA = readInt(candidate, "gamesA", "setsA", "homeSets", "player1Sets", "aSets");
        int gamesB = readInt(candidate, "gamesB", "setsB", "awaySets", "player2Sets", "bSets");
        int pointsA = readInt(candidate, "pointsInCurrentGameA", "pointsA", "currentPointsA", "homePoints", "player1Points", "aPoints");
        int pointsB = readInt(candidate, "pointsInCurrentGameB", "pointsB", "currentPointsB", "awayPoints", "player2Points", "bPoints");
        boolean preMatch = firstBoolean(candidate, "preMatch", "isPreMatch", "prematch");

        if (preMatch && gamesA <= 0 && gamesB <= 0 && pointsA <= 0 && pointsB <= 0) {
            return "";
        }

        if (gamesA >= 0 && gamesB >= 0) {
            if (pointsA >= 0 && pointsB >= 0) {
                return gamesA + "-" + gamesB + " (" + pointsA + "-" + pointsB + ")";
            }
            return gamesA + "-" + gamesB;
        }

        String flatScore = firstText(candidate, "score", "setScore", "currentScore", "displayScore", "scoreDisplay");
        if (looksLikeScore(flatScore)) {
            return normalizeScore(flatScore);
        }

        return extractScoreFromObject(candidate);
    }

    private boolean isEventResulted(JsonNode event) {
        if (event == null || event.isMissingNode() || event.isNull()) {
            return false;
        }
        if (firstBoolean(event, "resulted", "isResulted", "completed", "isCompleted", "matchCompleted")) {
            return true;
        }
        String state = firstText(event, "state", "status", "phase");
        if (isFinishedState(state)) {
            return true;
        }

        String matchStateRaw = firstText(event, "matchState");
        if (StringUtils.hasText(matchStateRaw)) {
            if (matchStateRaw.toLowerCase(Locale.ROOT).contains("\"matchcompleted\":true")) {
                return true;
            }
            if (matchStateRaw.trim().startsWith("{") || matchStateRaw.trim().startsWith("[")) {
                try {
                    JsonNode parsed = MAPPER.readTree(matchStateRaw);
                    JsonNode candidate = parsed;
                    if (candidate.isObject() && candidate.size() == 1) {
                        JsonNode nested = candidate.elements().next();
                        if (nested != null && nested.isObject()) {
                            candidate = nested;
                        }
                    }
                    if (firstBoolean(candidate, "matchCompleted", "resulted", "completed", "isResulted", "isCompleted")) {
                        return true;
                    }
                } catch (Exception ignore) {
                    // ignore invalid embedded json
                }
            }
        }

        JsonNode markets = firstArray(event, "markets", "betOffers", "marketGroups");
        if (markets == null) {
            return false;
        }
        for (JsonNode market : markets) {
            if (firstBoolean(market, "resulted", "isResulted", "settled", "isSettled")) {
                return true;
            }
            JsonNode selections = firstArray(market, "selection", "selections", "outcomes", "runners");
            if (selections == null) {
                continue;
            }
            for (JsonNode selection : selections) {
                if (firstBoolean(selection, "resulted", "isResulted", "settled", "isSettled")) {
                    return true;
                }
                String result = firstText(selection, "result", "outcome", "status");
                if (isFinishedState(result) || "WIN".equalsIgnoreCase(result) || "LOSE".equalsIgnoreCase(result) || "VOID".equalsIgnoreCase(result)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isFinishedState(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return normalized.contains("FINISH")
                || normalized.contains("FINAL")
                || normalized.contains("CLOSED")
                || normalized.contains("SETTLED")
                || normalized.contains("RESULT")
                || normalized.contains("COMPLETE")
                || normalized.contains("END");
    }

    private int readInt(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return -1;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isInt() || value.isLong()) {
                return value.asInt();
            }
            if (value.isTextual()) {
                try {
                    return Integer.parseInt(value.asText("").trim());
                } catch (Exception ignore) {
                    // continue
                }
            }
        }
        return -1;
    }

    private String extractScoreFromObject(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText("");
        }

        String direct = firstText(node,
                "score",
                "display",
                "displayScore",
                "currentSetScore"
        );
        if (looksLikeScore(direct)) {
            return direct;
        }

        String left = firstText(node, "home", "homeScore", "a", "player1", "one");
        String right = firstText(node, "away", "awayScore", "b", "player2", "two");
        if (isIntegerToken(left) && isIntegerToken(right)) {
            return left + "-" + right;
        }

        JsonNode currentSet = node.path("currentSet");
        if (!currentSet.isMissingNode() && !currentSet.isNull()) {
            String fromSet = extractScoreFromObject(currentSet);
            if (looksLikeScore(fromSet)) {
                return fromSet;
            }
        }

        JsonNode sets = node.path("sets");
        if (sets.isArray() && !sets.isEmpty()) {
            JsonNode lastSet = sets.get(sets.size() - 1);
            String fromSet = extractScoreFromObject(lastSet);
            if (looksLikeScore(fromSet)) {
                return fromSet;
            }
        }
        return "";
    }

    private String extractScoreFromText(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        Matcher matcher = SCORE_PATTERN.matcher(value);
        if (matcher.find()) {
            return matcher.group(1) + "-" + matcher.group(2);
        }
        return "";
    }

    private boolean looksLikeScore(String value) {
        return StringUtils.hasText(extractScoreFromText(value));
    }

    private boolean isIntegerToken(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String trimmed = value.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isDigit(trimmed.charAt(i))) {
                return false;
            }
        }
        return !trimmed.isEmpty();
    }

    private String normalizeScore(String score) {
        if (!StringUtils.hasText(score)) {
            return "";
        }
        Matcher matcher = SCORE_PATTERN.matcher(score);
        List<String> pairs = new ArrayList<>();
        while (matcher.find()) {
            pairs.add(matcher.group(1) + "-" + matcher.group(2));
        }
        if (pairs.isEmpty()) {
            return "";
        }
        if (pairs.size() == 1) {
            return pairs.get(0);
        }
        StringBuilder out = new StringBuilder(pairs.get(0));
        out.append(" (");
        for (int i = 1; i < pairs.size(); i++) {
            if (i > 1) out.append(", ");
            out.append(pairs.get(i));
        }
        out.append(')');
        return out.toString();
    }

    private String inferMatchPhase(boolean live, String startTimeIso, String liveScore) {
        int totalGames = scoreTotal(liveScore);
        if (!live) {
            if (totalGames > 0) {
                if (totalGames <= 1) return "LIVE_EARLY";
                if (totalGames <= 3) return "LIVE_MID";
                return "LIVE_LATE";
            }
            return "UPCOMING";
        }
        if (totalGames >= 0) {
            if (totalGames <= 1) return "LIVE_EARLY";
            if (totalGames <= 3) return "LIVE_MID";
            return "LIVE_LATE";
        }

        if (StringUtils.hasText(startTimeIso)) {
            try {
                long mins = ChronoUnit.MINUTES.between(Instant.parse(startTimeIso), Instant.now());
                if (mins <= 5) return "LIVE_EARLY";
                if (mins <= 15) return "LIVE_MID";
                return "LIVE_LATE";
            } catch (Exception ignore) {
                // ignore parse issues
            }
        }
        return "LIVE";
    }

    private int scoreTotal(String score) {
        String normalized = extractScoreFromText(score);
        if (!StringUtils.hasText(normalized)) {
            return -1;
        }
        String[] parts = normalized.split("-");
        if (parts.length != 2) {
            return -1;
        }
        try {
            return Integer.parseInt(parts[0]) + Integer.parseInt(parts[1]);
        } catch (Exception e) {
            return -1;
        }
    }

    private double americanToDecimal(String american) {
        double x = Double.parseDouble(american.trim());
        if (x > 0) {
            return 1.0 + x / 100.0;
        }
        return 1.0 + 100.0 / Math.abs(x);
    }

    private static final class MatchStateDetails {
        private final String liveScore;
        private final String scoreDetail;
        private final boolean matchCompleted;
        private final String sourceFeedCode;
        private final String sourceFeedEventId;

        private MatchStateDetails(String liveScore,
                                  String scoreDetail,
                                  boolean matchCompleted,
                                  String sourceFeedCode,
                                  String sourceFeedEventId) {
            this.liveScore = liveScore;
            this.scoreDetail = scoreDetail;
            this.matchCompleted = matchCompleted;
            this.sourceFeedCode = sourceFeedCode;
            this.sourceFeedEventId = sourceFeedEventId;
        }

        private static MatchStateDetails empty() {
            return new MatchStateDetails("", "", false, "", "");
        }

        private String liveScore() {
            return liveScore;
        }

        private String scoreDetail() {
            return scoreDetail;
        }

        private boolean matchCompleted() {
            return matchCompleted;
        }

        private String sourceFeedCode() {
            return sourceFeedCode;
        }

        private String sourceFeedEventId() {
            return sourceFeedEventId;
        }
    }

    private static class ParsedSelection {
        private final String name;
        private final double decimalOdds;
        private final String sideType;

        private ParsedSelection(String name, double decimalOdds, String sideType) {
            this.name = name;
            this.decimalOdds = decimalOdds;
            this.sideType = sideType;
        }
    }

    private static class RegionHint {
        private final String segment;
        private final String channel;

        private RegionHint(String segment, String channel) {
            this.segment = segment;
            this.channel = channel;
        }

        private String segment() {
            return segment;
        }

        private String channel() {
            return channel;
        }
    }
}
