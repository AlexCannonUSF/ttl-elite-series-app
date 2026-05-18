package com.ttl.tabletennis.cv;

import com.ttl.tabletennis.config.FeatureFlagCatalog;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Component
public class StreamRouter {

    static final Duration ZERO_FRAME_RERESOLVE_INTERVAL = Duration.ofSeconds(90);
    static final int MAX_CONSECUTIVE_RESOLVE_FAILURES = 3;

    private final FeatureFlagCatalog featureFlagCatalog;
    private final StreamRouteCatalog streamRouteCatalog;

    public StreamRouter(FeatureFlagCatalog featureFlagCatalog, StreamRouteCatalog streamRouteCatalog) {
        this.featureFlagCatalog = featureFlagCatalog;
        this.streamRouteCatalog = streamRouteCatalog;
    }

    public Optional<StreamRouteResolution> resolve(StreamRoutingRequest request) {
        if (!featureFlagCatalog.isEnabled(FeatureFlagCatalog.STREAM_CV_FLAG) || request == null) {
            return Optional.empty();
        }
        if (!request.directStreamUrl().isBlank()) {
            return Optional.of(resolution(
                    request,
                    StreamPlatform.inferFromUrl(request.directStreamUrl()),
                    request.directStreamUrl(),
                    request.roiTemplateId(),
                    StreamRouteSource.DETAIL_PAGE,
                    "detail:" + request.matchId()
            ));
        }
        if (!request.hardRockStreamHint().isBlank()) {
            return Optional.of(resolution(
                    request,
                    StreamPlatform.inferFromUrl(request.hardRockStreamHint()),
                    request.hardRockStreamHint(),
                    request.roiTemplateId(),
                    StreamRouteSource.HARD_ROCK_HINT,
                    "hardrock:" + request.matchId()
            ));
        }

        return streamRouteCatalog.find(request)
                .flatMap(route -> route.resolvedStreamUrl(request.tableNumber())
                        .map(url -> resolution(
                                request,
                                route.platform(),
                                url,
                                route.roiTemplateId(),
                                StreamRouteSource.ROUTE_OVERRIDE,
                                route.key()
                        )));
    }

    public boolean shouldReresolveAfterZeroFrames(int validFrameCount,
                                                  int consecutiveResolveFailures,
                                                  Instant lastResolvedAt,
                                                  Instant now) {
        if (!featureFlagCatalog.isEnabled(FeatureFlagCatalog.STREAM_CV_FLAG)) {
            return false;
        }
        if (validFrameCount > 0 || consecutiveResolveFailures >= MAX_CONSECUTIVE_RESOLVE_FAILURES) {
            return false;
        }
        Instant effectiveLastResolved = lastResolvedAt == null ? Instant.EPOCH : lastResolvedAt;
        Instant effectiveNow = now == null ? Instant.now() : now;
        return Duration.between(effectiveLastResolved, effectiveNow).compareTo(ZERO_FRAME_RERESOLVE_INTERVAL) >= 0;
    }

    public StreamCvComponentStatus status() {
        String warningDetail = streamRouteCatalog.warnings().isEmpty()
                ? ""
                : " " + streamRouteCatalog.warnings().size() + " route catalog warning(s).";
        return new StreamCvComponentStatus(
                "StreamRouter",
                featureFlagCatalog.stateOf(FeatureFlagCatalog.STREAM_CV_FLAG),
                featureFlagCatalog.isEnabled(FeatureFlagCatalog.STREAM_CV_FLAG),
                "Phase 03 route resolver; loaded " + streamRouteCatalog.routes().size()
                        + " operator route overrides." + warningDetail
        );
    }

    private StreamRouteResolution resolution(StreamRoutingRequest request,
                                             StreamPlatform platform,
                                             String streamUrl,
                                             String roiTemplateId,
                                             StreamRouteSource source,
                                             String routeKey) {
        return new StreamRouteResolution(
                request.matchId(),
                platform,
                streamUrl,
                roiTemplateId,
                source,
                routeKey,
                Instant.now()
        );
    }
}
