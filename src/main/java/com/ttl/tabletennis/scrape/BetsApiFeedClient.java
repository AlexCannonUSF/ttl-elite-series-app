package com.ttl.tabletennis.scrape;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;

@Component
public class BetsApiFeedClient extends AbstractJsonMirrorFeedClient {

    static final SourceId SOURCE = SourceId.BETSAPI;

    @Autowired
    public BetsApiFeedClient(IngestionBus ingestionBus,
                             OddsSnapshotFactory oddsSnapshotFactory,
                             @Value("${ttl.score-truth.integration.betsapi-enabled:${ttl.betsapi.enabled:false}}") boolean enabled,
                             @Value("${ttl.betsapi.apiBaseUrl:https://api.b365api.com}") String apiBaseUrl,
                             @Value("${ttl.betsapi.liveEventsPath:/v2/events/inplay?sport_id=92}") String liveEventsPath,
                             @Value("${ttl.betsapi.eventPathTemplate:/v1/event/view?event_id=%s}") String eventPathTemplate,
                             @Value("${ttl.betsapi.token:}") String apiToken,
                             @Value("${ttl.betsapi.refererUrl:}") String refererUrl,
                             @Value("${ttl.betsapi.userAgent:TTLEliteSeries/3.0}") String userAgent,
                             @Value("${ttl.betsapi.requestTimeoutMs:5000}") long requestTimeoutMs,
                             @Value("${ttl.betsapi.defaultConfidence:0.84}") double defaultConfidence,
                             @Value("${ttl.betsapi.completionConfidence:0.93}") double completionConfidence) {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                ingestionBus,
                oddsSnapshotFactory,
                enabled,
                apiBaseUrl,
                liveEventsPath,
                eventPathTemplate,
                apiToken,
                refererUrl,
                userAgent,
                requestTimeoutMs,
                defaultConfidence,
                completionConfidence
        );
    }

    BetsApiFeedClient(HttpClient httpClient,
                      IngestionBus ingestionBus,
                      OddsSnapshotFactory oddsSnapshotFactory,
                      boolean enabled,
                      String apiBaseUrl,
                      String liveEventsPath,
                      String eventPathTemplate,
                      String apiToken,
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
                "token",
                apiToken,
                "",
                "",
                refererUrl,
                userAgent,
                requestTimeoutMs,
                defaultConfidence,
                completionConfidence
        );
    }
}
