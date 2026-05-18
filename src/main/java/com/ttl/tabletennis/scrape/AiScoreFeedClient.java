package com.ttl.tabletennis.scrape;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;

@Component
public class AiScoreFeedClient extends AbstractJsonMirrorFeedClient {

    static final SourceId SOURCE = SourceId.AISCORE;

    @Autowired
    public AiScoreFeedClient(IngestionBus ingestionBus,
                             OddsSnapshotFactory oddsSnapshotFactory,
                             @Value("${ttl.aiscore.enabled:false}") boolean enabled,
                             @Value("${ttl.aiscore.apiBaseUrl:https://www.aiscore.com/api}") String apiBaseUrl,
                             @Value("${ttl.aiscore.liveEventsPath:/v3/matchs/live?type=4}") String liveEventsPath,
                             @Value("${ttl.aiscore.eventPathTemplate:/v3/match/detail?match_id=%s}") String eventPathTemplate,
                             @Value("${ttl.aiscore.apiKeyHeaderName:}") String apiKeyHeaderName,
                             @Value("${ttl.aiscore.apiKeyHeaderValue:}") String apiKeyHeaderValue,
                             @Value("${ttl.aiscore.refererUrl:https://www.aiscore.com/table-tennis}") String refererUrl,
                             @Value("${ttl.aiscore.userAgent:Mozilla/5.0 (compatible; TTLEliteSeries/3.0)}") String userAgent,
                             @Value("${ttl.aiscore.requestTimeoutMs:5000}") long requestTimeoutMs,
                             @Value("${ttl.aiscore.defaultConfidence:0.72}") double defaultConfidence,
                             @Value("${ttl.aiscore.completionConfidence:0.86}") double completionConfidence) {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                ingestionBus,
                oddsSnapshotFactory,
                enabled,
                apiBaseUrl,
                liveEventsPath,
                eventPathTemplate,
                apiKeyHeaderName,
                apiKeyHeaderValue,
                refererUrl,
                userAgent,
                requestTimeoutMs,
                defaultConfidence,
                completionConfidence
        );
    }

    AiScoreFeedClient(HttpClient httpClient,
                      IngestionBus ingestionBus,
                      OddsSnapshotFactory oddsSnapshotFactory,
                      boolean enabled,
                      String apiBaseUrl,
                      String liveEventsPath,
                      String eventPathTemplate,
                      String apiKeyHeaderName,
                      String apiKeyHeaderValue,
                      String refererUrl,
                      String userAgent,
                      long requestTimeoutMs,
                      double defaultConfidence,
                      double completionConfidence) {
        super(
                SOURCE,
                httpClient,
                ingestionBus,
                oddsSnapshotFactory,
                enabled,
                apiBaseUrl,
                liveEventsPath,
                eventPathTemplate,
                "",
                "",
                apiKeyHeaderName,
                apiKeyHeaderValue,
                refererUrl,
                userAgent,
                requestTimeoutMs,
                defaultConfidence,
                completionConfidence
        );
    }
}
