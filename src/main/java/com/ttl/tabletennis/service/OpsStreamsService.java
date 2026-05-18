package com.ttl.tabletennis.service;

import com.ttl.tabletennis.cv.BoardLocator;
import com.ttl.tabletennis.cv.FrameSampler;
import com.ttl.tabletennis.cv.RoiTemplateCatalog;
import com.ttl.tabletennis.cv.ScoreboardTextReader;
import com.ttl.tabletennis.cv.StreamCvComponentStatus;
import com.ttl.tabletennis.cv.StreamCvVlmFallbackHook;
import com.ttl.tabletennis.cv.StreamFetcher;
import com.ttl.tabletennis.cv.StreamRouteCatalog;
import com.ttl.tabletennis.cv.StreamRouter;
import com.ttl.tabletennis.dto.OpsStreamVlmUsageDto;
import com.ttl.tabletennis.dto.OpsStreamWorkerDto;
import com.ttl.tabletennis.dto.OpsStreamsDto;
import com.ttl.tabletennis.dto.OpsStreamsSummaryDto;
import com.ttl.tabletennis.repository.StreamRouteRecordRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class OpsStreamsService {

    private final StreamRouter streamRouter;
    private final StreamFetcher streamFetcher;
    private final FrameSampler frameSampler;
    private final BoardLocator boardLocator;
    private final ScoreboardTextReader scoreboardTextReader;
    private final StreamCvVlmFallbackHook vlmFallbackHook;
    private final StreamRouteCatalog streamRouteCatalog;
    private final RoiTemplateCatalog roiTemplateCatalog;
    private final StreamRouteRecordRepository streamRouteRecordRepository;

    public OpsStreamsService(StreamRouter streamRouter,
                             StreamFetcher streamFetcher,
                             FrameSampler frameSampler,
                             BoardLocator boardLocator,
                             ScoreboardTextReader scoreboardTextReader,
                             StreamCvVlmFallbackHook vlmFallbackHook,
                             StreamRouteCatalog streamRouteCatalog,
                             RoiTemplateCatalog roiTemplateCatalog,
                             StreamRouteRecordRepository streamRouteRecordRepository) {
        this.streamRouter = streamRouter;
        this.streamFetcher = streamFetcher;
        this.frameSampler = frameSampler;
        this.boardLocator = boardLocator;
        this.scoreboardTextReader = scoreboardTextReader;
        this.vlmFallbackHook = vlmFallbackHook;
        this.streamRouteCatalog = streamRouteCatalog;
        this.roiTemplateCatalog = roiTemplateCatalog;
        this.streamRouteRecordRepository = streamRouteRecordRepository;
    }

    public OpsStreamsDto snapshot() {
        Instant generatedAt = Instant.now();
        int activeForceRequests = vlmFallbackHook.activeForceCount(generatedAt);
        List<OpsStreamWorkerDto> workers = List.of(
                worker(streamRouter.status(), "ROUTER"),
                worker(streamFetcher.status(), "FETCH"),
                worker(frameSampler.status(), "SAMPLER"),
                worker(boardLocator.status(), "LOCATOR"),
                worker(scoreboardTextReader.status(), "OCR"),
                worker(vlmFallbackHook.status(), "VLM")
        );
        OpsStreamsSummaryDto summary = new OpsStreamsSummaryDto(
                workers.size(),
                (int) workers.stream().filter(OpsStreamWorkerDto::enabled).count(),
                (int) workers.stream().filter(worker -> !worker.enabled()).count(),
                routeOverrideCount(),
                streamRouteCatalog.warnings().size(),
                roiTemplateCatalog.templates().size(),
                activeForceRequests
        );
        return new OpsStreamsDto(generatedAt, summary, vlmUsage(activeForceRequests), workers);
    }

    private int routeOverrideCount() {
        long persistedRoutes = streamRouteRecordRepository.count();
        if (persistedRoutes > 0) {
            return toIntCount(persistedRoutes);
        }
        return streamRouteCatalog.routes().size();
    }

    private OpsStreamWorkerDto worker(StreamCvComponentStatus status, String workerType) {
        String normalizedState = normalize(status.rolloutState());
        String derivedStatus = status.enabled() ? "READY" : "OFF";
        return new OpsStreamWorkerDto(
                status.component(),
                workerType,
                normalizedState,
                status.enabled(),
                derivedStatus,
                status.detail()
        );
    }

    private OpsStreamVlmUsageDto vlmUsage(int activeForceRequests) {
        boolean enabled = vlmFallbackHook.status().enabled();
        String meteringState = enabled ? "HOOK_ONLY" : "OFF";
        String detail = enabled
                ? "Force/fallback hook is live; Phase 04 VLM client metering is not wired yet."
                : "VLM fallback hook is disabled.";
        return new OpsStreamVlmUsageDto(
                enabled,
                meteringState,
                activeForceRequests,
                0L,
                0L,
                0L,
                0.0,
                null,
                detail
        );
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "off";
        }
        return value.trim().toLowerCase();
    }

    private int toIntCount(long count) {
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }
}
