package com.ttl.tabletennis.settlement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ttl.tabletennis.domain.SettlementPolicyAuditRecord;
import com.ttl.tabletennis.repository.SettlementPolicyAuditRecordRepository;
import com.ttl.tabletennis.scrape.SourceId;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Component
public class BetSettlementPolicyCatalog {

    public static final String POLICY_NAME = "bet-settlement";

    static final String STATUS_LOADED = "LOADED";
    static final String STATUS_RELOADED = "RELOADED";
    static final String STATUS_DEFAULTED_MISSING = "DEFAULTED_MISSING";
    static final String STATUS_RELOAD_FAILED = "RELOAD_FAILED";

    private static final Logger log = LoggerFactory.getLogger(BetSettlementPolicyCatalog.class);
    private static final String MISSING_CHECKSUM = "MISSING";
    private static final int ERROR_MAX_LENGTH = 512;

    private final Path policyPath;
    private final SettlementPolicyAuditRecordRepository auditRepository;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private volatile PolicySnapshot snapshot;

    @Value("${ttl.score-truth.policy.hotReloadEnabled:true}")
    private boolean hotReloadEnabled = true;

    @Autowired
    public BetSettlementPolicyCatalog(@Value("${ttl.score-truth.policyPath:./bet_settlement_policy.yaml}") String policyPath,
                                      SettlementPolicyAuditRecordRepository auditRepository,
                                      MeterRegistry meterRegistry) {
        this(policyPath, auditRepository, meterRegistry, new ObjectMapper(), Clock.systemDefaultZone());
    }

    BetSettlementPolicyCatalog(String policyPath,
                               SettlementPolicyAuditRecordRepository auditRepository,
                               MeterRegistry meterRegistry,
                               ObjectMapper objectMapper,
                               Clock clock) {
        this.policyPath = Path.of(policyPath == null || policyPath.isBlank() ? "./bet_settlement_policy.yaml" : policyPath)
                .toAbsolutePath()
                .normalize();
        this.auditRepository = auditRepository;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
        this.snapshot = new PolicySnapshot(SettlementPolicy.defaults(), MISSING_CHECKSUM, this.policyPath.toString(), false, now());
        loadPolicy("startup", true);
    }

    public SettlementPolicy currentPolicy() {
        return snapshot.policy();
    }

    public PolicySnapshot snapshot() {
        return snapshot;
    }

    @Scheduled(fixedDelayString = "${ttl.score-truth.policy.reloadFixedDelayMs:30000}")
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

    public synchronized PolicySnapshot reloadNow(String triggeredBy) {
        loadPolicy(triggeredBy, true);
        return snapshot;
    }

    private void loadPolicy(String triggeredBy, boolean forceAudit) {
        PolicySnapshot before = snapshot;
        try {
            LoadedPolicy loaded = readPolicy();
            PolicySnapshot after = new PolicySnapshot(loaded.policy(), loaded.checksum(), policyPath.toString(), loaded.fileBacked(), now());
            snapshot = after;
            String status = loaded.fileBacked()
                    ? (MISSING_CHECKSUM.equals(before.checksum()) ? STATUS_LOADED : STATUS_RELOADED)
                    : STATUS_DEFAULTED_MISSING;
            if (forceAudit || !Objects.equals(before.checksum(), after.checksum())) {
                audit(status, safeTriggeredBy(triggeredBy), before, after, null);
            }
            recordMetric(status);
            log.info("[score-truth-policy] {} {} from {}", status, POLICY_NAME, policyPath);
        } catch (RuntimeException ex) {
            audit(STATUS_RELOAD_FAILED, safeTriggeredBy(triggeredBy), before, before, ex.getMessage());
            recordMetric(STATUS_RELOAD_FAILED);
            log.warn("[score-truth-policy] keeping last good {} policy after reload failure from {}: {}",
                    POLICY_NAME,
                    policyPath,
                    ex.getMessage());
        }
    }

    private LoadedPolicy readPolicy() {
        if (!Files.exists(policyPath)) {
            return new LoadedPolicy(SettlementPolicy.defaults(), MISSING_CHECKSUM, false);
        }

        try (InputStream inputStream = Files.newInputStream(policyPath)) {
            byte[] bytes = Files.readAllBytes(policyPath);
            Object loaded = new Yaml().load(inputStream);
            Map<String, Object> root = loaded instanceof Map<?, ?> rawMap ? stringMap(rawMap) : Map.of();
            Map<String, Object> policyNode = child(root, "settlement_policy", "settlementPolicy", "bet_settlement_policy", "betSettlementPolicy")
                    .orElse(root);
            return new LoadedPolicy(parsePolicy(policyNode), checksum(bytes), true);
        } catch (IOException ex) {
            throw new IllegalStateException("unable to read policy YAML: " + ex.getMessage(), ex);
        }
    }

    private SettlementPolicy parsePolicy(Map<String, Object> policyNode) {
        SettlementPolicy defaults = SettlementPolicy.defaults();
        Map<String, Object> ambiguity = child(policyNode, "ambiguity").orElse(Map.of());
        Map<String, Object> settlement = child(policyNode, "settlement").orElse(Map.of());
        Map<String, Object> staleLiveRecovery = child(policyNode, "stale_live_recovery", "staleLiveRecovery").orElse(Map.of());
        Map<String, Object> heuristic = child(policyNode, "heuristic").orElse(Map.of());

        double maxAllowedWithoutTiebreaker = doubleValue(
                ambiguity,
                defaults.ambiguity().maxAllowedWithoutTiebreaker(),
                "max_allowed_without_tiebreaker",
                "maxAllowedWithoutTiebreaker"
        );
        double minConfidenceToAutoSettle = doubleValue(
                settlement,
                defaults.settlement().minConfidenceToAutoSettle(),
                "min_confidence_to_auto_settle",
                "minConfidenceToAutoSettle"
        );
        double contradictionBlockSeverity = doubleValue(
                settlement,
                defaults.settlement().contradictionBlockSeverity(),
                "contradiction_block_severity",
                "contradictionBlockSeverity"
        );
        int requireSources = intValue(
                settlement,
                defaults.settlement().requireSources(),
                "require_sources",
                "requireSources"
        );
        int enterAfterMinutesDark = intValue(
                staleLiveRecovery,
                defaults.staleLiveRecovery().enterAfterMinutesDark(),
                "enter_after_minutes_dark",
                "enterAfterMinutesDark"
        );
        int officialWindowMinutes = intValue(
                staleLiveRecovery,
                defaults.staleLiveRecovery().officialWindowMinutes(),
                "official_window_minutes",
                "officialWindowMinutes"
        );
        List<SourceId> escalationOrder = sourceList(
                staleLiveRecovery,
                defaults.staleLiveRecovery().escalationOrder(),
                "escalation_order",
                "escalationOrder"
        );
        boolean heuristicAllowed = booleanValue(
                heuristic,
                defaults.heuristic().allowed(),
                "allowed"
        );
        int heuristicAfterDarkMinutes = intValue(
                heuristic,
                defaults.heuristic().afterDarkMinutes(),
                "after_dark_minutes",
                "afterDarkMinutes"
        );

        return new SettlementPolicy(
                new SettlementPolicy.Ambiguity(maxAllowedWithoutTiebreaker),
                new SettlementPolicy.Settlement(minConfidenceToAutoSettle, contradictionBlockSeverity, requireSources),
                new SettlementPolicy.StaleLiveRecovery(enterAfterMinutesDark, officialWindowMinutes, escalationOrder),
                new SettlementPolicy.Heuristic(heuristicAllowed, heuristicAfterDarkMinutes)
        );
    }

    private Optional<Map<String, Object>> child(Map<String, Object> source, String... names) {
        return value(source, names)
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(this::stringMap);
    }

    private Optional<Object> value(Map<String, Object> source, String... names) {
        if (source == null || source.isEmpty()) {
            return Optional.empty();
        }
        Set<String> normalizedNames = new LinkedHashSet<>();
        for (String name : names) {
            normalizedNames.add(normalizeKey(name));
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (normalizedNames.contains(normalizeKey(entry.getKey()))) {
                return Optional.ofNullable(entry.getValue());
            }
        }
        return Optional.empty();
    }

    private double doubleValue(Map<String, Object> source, double fallback, String... names) {
        return value(source, names)
                .map(raw -> {
                    if (raw instanceof Number number) {
                        return number.doubleValue();
                    }
                    try {
                        return Double.parseDouble(String.valueOf(raw).trim());
                    } catch (NumberFormatException ex) {
                        throw new IllegalArgumentException("invalid decimal policy value for " + names[0] + ": " + raw);
                    }
                })
                .orElse(fallback);
    }

    private int intValue(Map<String, Object> source, int fallback, String... names) {
        return value(source, names)
                .map(raw -> {
                    if (raw instanceof Number number) {
                        return number.intValue();
                    }
                    try {
                        return Integer.parseInt(String.valueOf(raw).trim());
                    } catch (NumberFormatException ex) {
                        throw new IllegalArgumentException("invalid integer policy value for " + names[0] + ": " + raw);
                    }
                })
                .orElse(fallback);
    }

    private boolean booleanValue(Map<String, Object> source, boolean fallback, String... names) {
        return value(source, names)
                .map(raw -> {
                    if (raw instanceof Boolean bool) {
                        return bool;
                    }
                    return Boolean.parseBoolean(String.valueOf(raw).trim());
                })
                .orElse(fallback);
    }

    private List<SourceId> sourceList(Map<String, Object> source, List<SourceId> fallback, String... names) {
        Optional<Object> rawValue = value(source, names);
        if (rawValue.isEmpty()) {
            return fallback;
        }
        Object raw = rawValue.get();
        if (!(raw instanceof List<?> rawList)) {
            throw new IllegalArgumentException("policy value " + names[0] + " must be a list");
        }
        List<SourceId> parsed = new ArrayList<>();
        for (Object item : rawList) {
            SourceId sourceId = SourceId.fromValue(String.valueOf(item))
                    .orElseThrow(() -> new IllegalArgumentException("unknown policy source id: " + item));
            parsed.add(sourceId);
        }
        return List.copyOf(parsed);
    }

    private Map<String, Object> stringMap(Map<?, ?> rawMap) {
        Map<String, Object> converted = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> {
            if (key != null) {
                converted.put(String.valueOf(key), value);
            }
        });
        return converted;
    }

    private String normalizeKey(String key) {
        if (key == null) {
            return "";
        }
        return key.trim().replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
    }

    private String currentChecksum() {
        if (!Files.exists(policyPath)) {
            return MISSING_CHECKSUM;
        }
        try {
            return checksum(Files.readAllBytes(policyPath));
        } catch (IOException ex) {
            return "UNREADABLE:" + ex.getClass().getSimpleName();
        }
    }

    private String checksum(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest unavailable", ex);
        }
    }

    private void audit(String status,
                       String triggeredBy,
                       PolicySnapshot before,
                       PolicySnapshot after,
                       String errorMessage) {
        if (auditRepository == null) {
            return;
        }
        try {
            SettlementPolicyAuditRecord record = new SettlementPolicyAuditRecord();
            record.setPolicyName(POLICY_NAME);
            record.setStatus(status);
            record.setTriggeredBy(triggeredBy);
            record.setSourcePath(policyPath.toString());
            record.setChecksumBefore(before == null ? null : before.checksum());
            record.setChecksumAfter(after == null ? null : after.checksum());
            record.setErrorMessage(truncate(errorMessage, ERROR_MAX_LENGTH));
            record.setReloadedAt(now());
            record.setPayloadJson(payloadJson(before, after, errorMessage));
            auditRepository.save(record);
        } catch (RuntimeException ex) {
            log.warn("[score-truth-policy] unable to persist policy audit row: {}", ex.getMessage());
        }
    }

    private String payloadJson(PolicySnapshot before, PolicySnapshot after, String errorMessage) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("policyName", POLICY_NAME);
        payload.put("sourcePath", policyPath.toString());
        payload.put("before", before == null ? null : policyMap(before.policy()));
        payload.put("after", after == null ? null : policyMap(after.policy()));
        payload.put("changedFields", changedFields(before == null ? null : before.policy(), after == null ? null : after.policy()));
        if (errorMessage != null && !errorMessage.isBlank()) {
            payload.put("errorMessage", truncate(errorMessage, ERROR_MAX_LENGTH));
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            return "{\"serializationError\":\"" + sanitize(ex.getMessage()) + "\"}";
        }
    }

    private List<Map<String, Object>> changedFields(SettlementPolicy before, SettlementPolicy after) {
        Map<String, Object> beforeFlat = flatten(policyMap(before));
        Map<String, Object> afterFlat = flatten(policyMap(after));
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(beforeFlat.keySet());
        keys.addAll(afterFlat.keySet());

        return keys.stream()
                .filter(key -> !Objects.equals(beforeFlat.get(key), afterFlat.get(key)))
                .sorted(Comparator.naturalOrder())
                .map(key -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("field", key);
                    row.put("before", beforeFlat.get(key));
                    row.put("after", afterFlat.get(key));
                    return row;
                })
                .toList();
    }

    private Map<String, Object> flatten(Map<String, Object> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        flattenInto("", source, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private void flattenInto(String prefix, Map<String, Object> source, Map<String, Object> out) {
        if (source == null) {
            return;
        }
        source.forEach((key, value) -> {
            String path = prefix.isBlank() ? key : prefix + "." + key;
            if (value instanceof Map<?, ?> nested) {
                flattenInto(path, stringMap(nested), out);
            } else {
                out.put(path, value);
            }
        });
    }

    private Map<String, Object> policyMap(SettlementPolicy policy) {
        if (policy == null) {
            return Map.of();
        }
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> ambiguity = new LinkedHashMap<>();
        ambiguity.put("maxAllowedWithoutTiebreaker", policy.ambiguity().maxAllowedWithoutTiebreaker());
        root.put("ambiguity", ambiguity);

        Map<String, Object> settlement = new LinkedHashMap<>();
        settlement.put("minConfidenceToAutoSettle", policy.settlement().minConfidenceToAutoSettle());
        settlement.put("contradictionBlockSeverity", policy.settlement().contradictionBlockSeverity());
        settlement.put("requireSources", policy.settlement().requireSources());
        root.put("settlement", settlement);

        Map<String, Object> staleLiveRecovery = new LinkedHashMap<>();
        staleLiveRecovery.put("enterAfterMinutesDark", policy.staleLiveRecovery().enterAfterMinutesDark());
        staleLiveRecovery.put("officialWindowMinutes", policy.staleLiveRecovery().officialWindowMinutes());
        staleLiveRecovery.put("escalationOrder", policy.staleLiveRecovery().escalationOrder().stream().map(SourceId::id).toList());
        root.put("staleLiveRecovery", staleLiveRecovery);

        Map<String, Object> heuristic = new LinkedHashMap<>();
        heuristic.put("allowed", policy.heuristic().allowed());
        heuristic.put("afterDarkMinutes", policy.heuristic().afterDarkMinutes());
        root.put("heuristic", heuristic);
        return root;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private String safeTriggeredBy(String triggeredBy) {
        if (triggeredBy == null || triggeredBy.isBlank()) {
            return "unknown";
        }
        return truncate(triggeredBy.trim(), 96);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength);
    }

    private String sanitize(String value) {
        if (value == null) {
            return "unknown";
        }
        return value.replace("\\", "\\\\").replace("\"", "'");
    }

    private void recordMetric(String status) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter(
                "ttl.score_truth.policy.reloads",
                "policy",
                POLICY_NAME,
                "status",
                status == null || status.isBlank() ? "UNKNOWN" : status
        ).increment();
    }

    public record PolicySnapshot(
            SettlementPolicy policy,
            String checksum,
            String sourcePath,
            boolean fileBacked,
            LocalDateTime loadedAt
    ) {
    }

    private record LoadedPolicy(SettlementPolicy policy, String checksum, boolean fileBacked) {
    }
}
