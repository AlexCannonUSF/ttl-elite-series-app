package com.ttl.tabletennis.prediction.shadow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ttl.tabletennis.config.FeatureFlagCatalog;
import com.ttl.tabletennis.repository.PredictionDiffLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executor;

@Configuration
public class PredictionShadowConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PredictionShadowConfiguration.class);

    @Bean
    public BlenderHttpExchange blenderHttpExchange() {
        return new JdkBlenderHttpExchange();
    }

    @Bean
    public PredictionShadowSampler predictionShadowSampler(
            @Value("${ttl.predict-v3.shadowRate:" + PredictionShadowSampler.DEFAULT_SAMPLE_RATE + "}") double sampleRate) {
        return new PredictionShadowSampler(sampleRate);
    }

    @Bean
    public BlenderClient blenderClient(
            @Value("${ttl.predict-v3.enabled:false}") boolean enabled,
            @Value("${ttl.predict-v3.endpoint:http://localhost:8090/v1/blend}") String endpoint,
            BlenderHttpExchange http,
            ObjectMapper objectMapper) {
        if (!enabled) {
            log.info("[predict-v3-shadow] disabled by ttl.predict-v3.enabled=false");
            return new DisabledBlenderClient("disabled-by-property");
        }
        try {
            log.info("[predict-v3-shadow] HTTP blender client active; endpoint={}", endpoint);
            return new HttpBlenderClient(endpoint, http, objectMapper);
        } catch (RuntimeException e) {
            log.warn("[predict-v3-shadow] unable to build HTTP client: {}; falling back to disabled", e.getMessage());
            return new DisabledBlenderClient("client-init-failed");
        }
    }

    @Bean(name = "ttlPredictionShadowExecutor")
    public Executor ttlPredictionShadowExecutor(
            @Value("${ttl.predict-v3.executor.corePoolSize:2}") int corePoolSize,
            @Value("${ttl.predict-v3.executor.maxPoolSize:4}") int maxPoolSize,
            @Value("${ttl.predict-v3.executor.queueCapacity:128}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("ttl-predict-v3-shadow-");
        executor.setRejectedExecutionHandler((task, exec) ->
                log.warn("[predict-v3-shadow] executor queue full; dropping shadow task"));
        executor.initialize();
        return executor;
    }

    @Bean
    public PredictionShadowService predictionShadowService(
            FeatureFlagCatalog featureFlagCatalog,
            PredictionShadowSampler sampler,
            BlenderClient blenderClient,
            Optional<PredictionDiffLogRepository> repository,
            @org.springframework.beans.factory.annotation.Qualifier("ttlPredictionShadowExecutor") Executor executor,
            @Value("${ttl.predict-v3.requestTimeoutMs:750}") long requestTimeoutMs,
            @Value("${ttl.predict-v3.featureSchemaHash:}") String featureSchemaHash) {
        return new PredictionShadowService(
                featureFlagCatalog,
                sampler,
                blenderClient,
                repository.orElse(null),
                executor,
                Clock.systemUTC(),
                Duration.ofMillis(Math.max(50L, requestTimeoutMs)),
                featureSchemaHash
        );
    }
}
