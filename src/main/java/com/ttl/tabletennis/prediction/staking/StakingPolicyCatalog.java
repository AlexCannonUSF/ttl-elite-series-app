package com.ttl.tabletennis.prediction.staking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ttl.tabletennis.domain.SettlementPolicyAuditRecord;
import com.ttl.tabletennis.repository.SettlementPolicyAuditRecordRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Hot-reloading loader for {@code policy.yaml#prediction.staking} per
 * Prediction Engine Spec §11.2. Persists each reload to the shared
 * {@code settlement_policy_audit} table tagged
 * {@code policyName = "staking-v3"} so operators can diff every change.
 */
@Component
public class StakingPolicyCatalog {

    public static final String POLICY_NAME = "staking-v3";

    static final String STATUS_LOADED = "LOADED";
    static final String STATUS_RELOADED = "RELOADED";
    static final String STATUS_DEFAULTED_MISSING = "DEFAULTED_MISSING";
    static final String STATUS_RELOAD_FAILED = "RELOAD_FAILED";
    static final String STATUS_KILL_SWITCH_ON = "KILL_SWITCH_ON";
    static final String STATUS_KILL_SWITCH_OFF = "KILL_SWITCH_OFF";

    private static final Logger log = LoggerFactory.getLogger(StakingPolicyCatalog.class);
    private static final String MISSING_CHECKSUM = "MISSING";
    private static final int ERROR_MAX_LENGTH = 512;

    private final Path policyPath;
    private final SettlementPolicyAuditRecordRepository auditRepository;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private volatile Snapshot snapshot;

    @Value("${ttl.staking.policy.hotReloadEnabled:true}")
    private boolean hotReloadEnabled = true;

    @Autowired
    public StakingPolicyCatalog(@Value("${ttl.staking.policyPath:./policy.yaml}") String policyPath,
                                SettlementPolicyAuditRecordRepository auditRepository,
                                MeterRegistry meterRegistry) {
        this(policyPath, auditRepository, meterRegistry, new ObjectMapper(), Clock.systemDefaultZone());
    }

    StakingPolicyCatalog(String policyPath,
                         SettlementPolicyAuditRecordRepository auditRepository,
                         MeterRegistry meterRegistry,
                         ObjectMapper objectMapper,
                         Clock clock) {
        this.policyPath = Path.of(policyPath == null || policyPath.isBlank() ? "./policy.yaml" : policyPath)
                .toAbsolutePath()
                .normalize();
        this.auditRepository = auditRepository;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
        this.snapshot = new Snapshot(StakingPolicyConfig.defaults(), MISSING_CHECKSUM, this.policyPath.toString(), false, now());
        loadPolicy("startup", true);
    }

    public StakingPolicyConfig currentConfig() {
        return snapshot.config();
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    @Scheduled(fixedDelayString = "${ttl.staking.policy.reloadFixedDelayMs:30000}")
    @Transactional
    public boolean reloadIfChanged() {
        if (!hotReloadEnabled) {
            return false;
        }
        return reloadIfChanged("scheduled");
    }

    public synchronized boolean reloadIfChanged(String triggeredBy) {
        String checksum = currentChecksum();
        if (Objects.equals(snapshot.checksum(), checksum)) {
            return false;
        }
        loadPolicy(triggeredBy, false);
        return true;
    }

    public synchronized Snapshot reloadNow(String triggeredBy) {
        loadPolicy(triggeredBy, true);
        return snapshot;
    }

    /** Audit a kill-switch toggle. The actual flag state lives on {@link StakingKillSwitch}. */
    public void recordKillSwitchEvent(boolean activated, String triggeredBy, String reason) {
        String status = activated ? STATUS_KILL_SWITCH_ON : STATUS_KILL_SWITCH_OFF;
        audit(status, safeTriggeredBy(triggeredBy), snapshot, snapshot, reason);
        recordMetric(status);
    }

    private void loadPolicy(String triggeredBy, boolean forceAudit) {
        Snapshot before = snapshot;
        try {
            LoadedPolicy loaded = readPolicy();
            Snapshot after = new Snapshot(loaded.config(), loaded.checksum(), policyPath.toString(), loaded.fileBacked(), now());
            snapshot = after;
            String status = loaded.fileBacked()
                    ? (MISSING_CHECKSUM.equals(before.checksum()) ? STATUS_LOADED : STATUS_RELOADED)
                    : STATUS_DEFAULTED_MISSING;
            if (forceAudit || !Objects.equals(before.checksum(), after.checksum())) {
                audit(status, safeTriggeredBy(triggeredBy), before, after, null);
            }
            recordMetric(status);
            log.info("[staking-policy] {} {} from {}", status, POLICY_NAME, policyPath);
        } catch (RuntimeException ex) {
            audit(STATUS_RELOAD_FAILED, safeTriggeredBy(triggeredBy), before, before, ex.getMessage());
            recordMetric(STATUS_RELOAD_FAILED);
            log.warn("[staking-policy] keeping last good {} policy after reload failure from {}: {}",
                    POLICY_NAME,
                    policyPath,
                    ex.getMessage());
        }
    }

    private LoadedPolicy readPolicy() {
        if (!Files.exists(policyPath)) {
            return new LoadedPolicy(StakingPolicyConfig.defaults(), MISSING_CHECKSUM, false);
        }
        try {
            byte[] bytes = Files.readAllBytes(policyPath);
            try (InputStream inputStream = Files.newInputStream(policyPath)) {
                Object loaded = new Yaml().load(inputStream);
                Map<String, Object> root = loaded instanceof Map<?, ?> rawMap ? stringMap(rawMap) : Map.of();
                StakingPolicyConfig config = parseStaking(root);
                return new LoadedPolicy(config, checksum(bytes), true);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("unable to read staking policy YAML: " + ex.getMessage(), ex);
        }
    }

    private StakingPolicyConfig parseStaking(Map<String, Object> root) {
        StakingPolicyConfig defaults = StakingPolicyConfig.defaults();
        Map<String, Object> prediction = child(root, "prediction").orElse(Map.of());
        Map<String, Object> staking = child(prediction, "staking").orElse(child(root, "staking").orElse(Map.of()));
        Map<String, Object> minimumEdge = child(prediction, "minimumEdge", "minimum_edge").orElse(Map.of());
        Map<String, Object> drawdown = child(staking, "sessionDrawdownStop", "session_drawdown_stop").orElse(Map.of());

        double fractionalKelly = doubleValue(staking, defaults.fractionalKelly(), "fractionalKelly", "fractional_kelly");
        double kellyCapUnits = doubleValue(staking, defaults.kellyCapUnits(), "kellyCapUnits", "kelly_cap_units");
        double perEventCapUnits = doubleValue(staking, defaults.perEventCapUnits(), "perMatchCapUnits", "per_match_cap_units", "perEventCapUnits", "per_event_cap_units");
        double perPlayerDailyCapUnits = doubleValue(staking, defaults.perPlayerDailyCapUnits(), "perPlayerDailyCapUnits", "per_player_daily_cap_units");
        double maxOpenExposureUnits = doubleValue(staking, defaults.maxOpenExposureUnits(), "maxOpenExposureUnits", "max_open_exposure_units");
        double minStakeUnits = doubleValue(staking, defaults.minStakeUnits(), "minStakeUnits", "min_stake_units");
        double edgeDefault = doubleValue(minimumEdge, defaults.minimumEdge(), "default", "default_edge");

        int drawdownLookback = intValue(drawdown, defaults.drawdownLookbackBets(), "lookbackBets", "lookback_bets");
        double drawdownTriggerRoi = doubleValue(drawdown, defaults.drawdownTriggerRoi(), "triggerRoi", "trigger_roi");
        double drawdownFactor = doubleValue(drawdown, defaults.drawdownFactor(), "factor");

        return new StakingPolicyConfig(
                fractionalKelly,
                kellyCapUnits,
                perEventCapUnits,
                perPlayerDailyCapUnits,
                maxOpenExposureUnits,
                minStakeUnits,
                edgeDefault,
                drawdownLookback,
                drawdownTriggerRoi,
                drawdownFactor
        );
    }

    private String currentChecksum() {
        if (!Files.exists(policyPath)) {
            return MISSING_CHECKSUM;
        }
        try {
            return checksum(Files.readAllBytes(policyPath));
        } catch (IOException ex) {
            return MISSING_CHECKSUM;
        }
    }

    private void audit(String status, String triggeredBy, Snapshot before, Snapshot after, String errorMessage) {
        if (auditRepository == null) {
            return;
        }
        SettlementPolicyAuditRecord record = new SettlementPolicyAuditRecord();
        record.setPolicyName(POLICY_NAME);
        record.setStatus(status);
        record.setTriggeredBy(triggeredBy);
        record.setSourcePath(policyPath.toString());
        record.setChecksumBefore(before == null ? MISSING_CHECKSUM : before.checksum());
        record.setChecksumAfter(after == null ? MISSING_CHECKSUM : after.checksum());
        record.setReloadedAt(now());
        record.setPayloadJson(serializeDiff(before, after));
        if (errorMessage != null && !errorMessage.isBlank()) {
            record.setErrorMessage(errorMessage.length() > ERROR_MAX_LENGTH
                    ? errorMessage.substring(0, ERROR_MAX_LENGTH)
                    : errorMessage);
        }
        try {
            auditRepository.save(record);
        } catch (RuntimeException ex) {
            log.warn("[staking-policy] unable to persist audit row for {}: {}", status, ex.getMessage());
        }
    }

    private String serializeDiff(Snapshot before, Snapshot after) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("before", before == null ? null : configToMap(before.config()));
        payload.put("after", after == null ? null : configToMap(after.config()));
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private static Map<String, Object> configToMap(StakingPolicyConfig config) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("fractionalKelly", config.fractionalKelly());
        map.put("kellyCapUnits", config.kellyCapUnits());
        map.put("perEventCapUnits", config.perEventCapUnits());
        map.put("perPlayerDailyCapUnits", config.perPlayerDailyCapUnits());
        map.put("maxOpenExposureUnits", config.maxOpenExposureUnits());
        map.put("minStakeUnits", config.minStakeUnits());
        map.put("minimumEdge", config.minimumEdge());
        map.put("drawdownLookbackBets", config.drawdownLookbackBets());
        map.put("drawdownTriggerRoi", config.drawdownTriggerRoi());
        map.put("drawdownFactor", config.drawdownFactor());
        return map;
    }

    private void recordMetric(String status) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter("ttl.staking.policy.reloads", "policy", POLICY_NAME, "status", status).increment();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    static String safeTriggeredBy(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    static String checksum(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            return MISSING_CHECKSUM;
        }
    }

    static Map<String, Object> stringMap(Map<?, ?> raw) {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() instanceof String key) {
                out.put(key, entry.getValue());
            }
        }
        return out;
    }

    private static Optional<Map<String, Object>> child(Map<String, Object> parent, String... keys) {
        for (String key : keys) {
            Object value = parent.get(key);
            if (value instanceof Map<?, ?> map) {
                return Optional.of(stringMap(map));
            }
        }
        return Optional.empty();
    }

    private static double doubleValue(Map<String, Object> node, double defaultValue, String... keys) {
        for (String key : keys) {
            Object value = node.get(key);
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            if (value instanceof String text) {
                try {
                    return Double.parseDouble(text.trim());
                } catch (NumberFormatException ignored) {
                    // try next key
                }
            }
        }
        return defaultValue;
    }

    private static int intValue(Map<String, Object> node, int defaultValue, String... keys) {
        for (String key : keys) {
            Object value = node.get(key);
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value instanceof String text) {
                try {
                    return Integer.parseInt(text.trim());
                } catch (NumberFormatException ignored) {
                    // try next
                }
            }
        }
        return defaultValue;
    }

    public record Snapshot(StakingPolicyConfig config,
                           String checksum,
                           String sourcePath,
                           boolean fileBacked,
                           LocalDateTime loadedAt) { }

    private record LoadedPolicy(StakingPolicyConfig config, String checksum, boolean fileBacked) { }
}
