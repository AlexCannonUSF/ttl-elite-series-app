package com.ttl.tabletennis.cv;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public class StreamVlmMetrics {

    static final String METRIC_CALLS_TOTAL = "stream_vlm_calls_total";
    static final String METRIC_COST_USD_TOTAL = "stream_vlm_cost_usd_total";
    static final String METRIC_TOKENS_TOTAL = "stream_vlm_tokens_total";
    static final String METRIC_LATENCY = "stream_vlm_latency";
    static final String METRIC_BLOCKS_TOTAL = "stream_vlm_governor_blocks_total";
    static final String METRIC_DAILY_CALLS = "stream_vlm_daily_calls";
    static final String METRIC_DAILY_COST = "stream_vlm_daily_cost_usd_estimate";

    private final MeterRegistry meterRegistry;
    private final Map<String, AtomicReference<Double>> dailyCallsByModel = new ConcurrentHashMap<>();
    private final Map<String, AtomicReference<Double>> dailyCostByModel = new ConcurrentHashMap<>();

    public StreamVlmMetrics(MeterRegistry meterRegistry) {
        if (meterRegistry == null) {
            throw new IllegalArgumentException("meterRegistry must not be null");
        }
        this.meterRegistry = meterRegistry;
    }

    public void recordCall(String model, VlmScoreReadingResult result) {
        String safeModel = safeModel(model);
        String reason = switch (result.status()) {
            case OK -> "ok";
            case UNREADABLE -> "unreadable";
            case ERROR -> "error";
        };
        Counter.builder(METRIC_CALLS_TOTAL)
                .tag("model", safeModel)
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
        if (result.costEstimateUsd() > 0.0) {
            Counter.builder(METRIC_COST_USD_TOTAL)
                    .tag("model", safeModel)
                    .baseUnit("usd")
                    .register(meterRegistry)
                    .increment(result.costEstimateUsd());
        }
        if (result.tokensIn() > 0) {
            tokensCounter(safeModel, "input").increment(result.tokensIn());
        }
        if (result.tokensOut() > 0) {
            tokensCounter(safeModel, "output").increment(result.tokensOut());
        }
        Timer.builder(METRIC_LATENCY)
                .tag("model", safeModel)
                .tag("reason", reason)
                .register(meterRegistry)
                .record(result.latency());
    }

    public void recordBlock(String reason) {
        Counter.builder(METRIC_BLOCKS_TOTAL)
                .tag("reason", reason == null ? "unknown" : reason)
                .register(meterRegistry)
                .increment();
    }

    public void updateDailyCalls(String model, double value) {
        gaugeHolder(dailyCallsByModel, METRIC_DAILY_CALLS, model).set(value);
    }

    public void updateDailyCost(String model, double value) {
        gaugeHolder(dailyCostByModel, METRIC_DAILY_COST, model).set(value);
    }

    private AtomicReference<Double> gaugeHolder(Map<String, AtomicReference<Double>> store, String metricName, String model) {
        return store.computeIfAbsent(safeModel(model), key -> {
            AtomicReference<Double> ref = new AtomicReference<>(0.0);
            Gauge.builder(metricName, ref, holder -> holder.get() == null ? 0.0 : holder.get())
                    .tag("model", key)
                    .register(meterRegistry);
            return ref;
        });
    }

    private Counter tokensCounter(String model, String kind) {
        return Counter.builder(METRIC_TOKENS_TOTAL)
                .tag("model", model)
                .tag("kind", kind)
                .register(meterRegistry);
    }

    private static String safeModel(String model) {
        return model == null || model.isBlank() ? "unknown" : model;
    }
}
