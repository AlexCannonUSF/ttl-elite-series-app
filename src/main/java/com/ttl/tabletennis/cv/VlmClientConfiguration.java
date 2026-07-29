package com.ttl.tabletennis.cv;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ttl.tabletennis.repository.StreamVlmCallRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.Locale;
import java.util.Optional;

@Configuration
public class VlmClientConfiguration {

    public static final String ENGINE_DISABLED = "disabled";
    public static final String ENGINE_GEMINI_FLASH = "gemini-flash";
    public static final String ENGINE_CLAUDE_HAIKU = "claude-haiku";

    private static final Logger log = LoggerFactory.getLogger(VlmClientConfiguration.class);

    @Bean
    public VlmHttpExchange vlmHttpExchange() {
        return new JdkHttpVlmExchange();
    }

    @Bean
    public VlmResponseParser vlmResponseParser(ObjectMapper objectMapper) {
        return new VlmResponseParser(objectMapper);
    }

    @Bean
    public VlmClient vlmClient(
            @Value("${ttl.streamCv.vlm.engine:disabled}") String engine,
            @Value("${ttl.streamCv.vlm.gemini.apiKey:${GEMINI_API_KEY:}}") String geminiApiKey,
            @Value("${ttl.streamCv.vlm.gemini.endpoint:" + GeminiFlashVisionClient.DEFAULT_ENDPOINT + "}") String geminiEndpoint,
            @Value("${ttl.streamCv.vlm.claude.apiKey:${ANTHROPIC_API_KEY:}}") String claudeApiKey,
            @Value("${ttl.streamCv.vlm.claude.model:" + ClaudeHaikuVisionClient.DEFAULT_MODEL_ID + "}") String claudeModelId,
            @Value("${ttl.streamCv.vlm.claude.endpoint:" + ClaudeHaikuVisionClient.DEFAULT_ENDPOINT + "}") String claudeEndpoint,
            @Value("${ttl.streamCv.vlm.claude.version:" + ClaudeHaikuVisionClient.DEFAULT_ANTHROPIC_VERSION + "}") String claudeVersion,
            VlmHttpExchange http,
            ObjectMapper objectMapper,
            VlmResponseParser parser) {
        String normalized = engine == null ? ENGINE_DISABLED : engine.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case ENGINE_GEMINI_FLASH -> buildGemini(geminiApiKey, geminiEndpoint, http, objectMapper, parser);
            case ENGINE_CLAUDE_HAIKU -> buildClaude(claudeApiKey, claudeModelId, claudeEndpoint, claudeVersion, http, objectMapper, parser);
            default -> {
                log.info("[vlm] engine='{}'; VLM Tier C fallback disabled", normalized);
                yield new DisabledVlmClient();
            }
        };
    }

    private VlmClient buildGemini(String apiKey,
                                  String endpoint,
                                  VlmHttpExchange http,
                                  ObjectMapper objectMapper,
                                  VlmResponseParser parser) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[vlm] engine='gemini-flash' selected but no API key set; disabling VLM");
            return new DisabledVlmClient();
        }
        log.info("[vlm] engine='gemini-flash' active; endpoint={}", endpoint);
        return new GeminiFlashVisionClient(apiKey, endpoint, http, objectMapper, parser, null);
    }

    private VlmClient buildClaude(String apiKey,
                                  String modelId,
                                  String endpoint,
                                  String version,
                                  VlmHttpExchange http,
                                  ObjectMapper objectMapper,
                                  VlmResponseParser parser) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[vlm] engine='claude-haiku' selected but no API key set; disabling VLM");
            return new DisabledVlmClient();
        }
        log.info("[vlm] engine='claude-haiku' active; model={} endpoint={}", modelId, endpoint);
        return new ClaudeHaikuVisionClient(apiKey, modelId, endpoint, version, http, objectMapper, parser, null);
    }

    @Bean
    public StreamVlmMetrics streamVlmMetrics(MeterRegistry meterRegistry) {
        return new StreamVlmMetrics(meterRegistry);
    }

    @Bean
    public CostGovernor costGovernor(
            @Value("${ttl.streamCv.vlm.governor.enabled:true}") boolean enabled,
            @Value("${ttl.streamCv.vlm.governor.dailySoftCap:" + CostGovernor.DEFAULT_DAILY_SOFT_CAP + "}") int dailySoftCap,
            @Value("${ttl.streamCv.vlm.governor.dailyHardCap:" + CostGovernor.DEFAULT_DAILY_HARD_CAP + "}") int dailyHardCap,
            @Value("${ttl.streamCv.vlm.governor.perWorkerHourCap:" + CostGovernor.DEFAULT_PER_WORKER_HOUR_CAP + "}") int perWorkerHourCap,
            StreamVlmMetrics metrics) {
        return new CostGovernor(dailySoftCap, dailyHardCap, perWorkerHourCap, enabled, Clock.systemUTC(), metrics);
    }

    @Bean
    public VlmCallRecorder vlmCallRecorder(Optional<StreamVlmCallRepository> repository) {
        return new VlmCallRecorder(repository);
    }

    @Bean
    public GovernedVlmCaller governedVlmCaller(VlmClient vlmClient,
                                               CostGovernor costGovernor,
                                               VlmCallRecorder recorder) {
        return new GovernedVlmCaller(vlmClient, costGovernor, recorder, Clock.systemUTC());
    }
}
