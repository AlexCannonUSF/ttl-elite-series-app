package com.ttl.tabletennis.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class FeatureFlagCatalog {

    public static final String STREAM_CV_FLAG = "features.stream-cv";
    public static final String SCORE_TRUTH_FLAG = "features.score-truth";
    public static final String REDIS_STREAMS_FLAG = "features.redis-streams";
    public static final String PREDICT_V3_FLAG = "features.predict-v3";
    public static final String STAKE_POLICY_V3_FLAG = "features.stake-policy-v3";

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagCatalog.class);

    private final String catalogPathRaw;
    private final Path catalogPath;
    // #118 — volatile so the scheduled hot-reload can publish updates to
    // every reader without locking.
    private volatile Map<String, FeatureFlagDefinition> flags;
    private volatile String lastLoadedSha256;
    private final boolean hotReloadEnabled;

    @org.springframework.beans.factory.annotation.Autowired
    public FeatureFlagCatalog(@Value("${ttl.features.catalogPath:./features.yaml}") String catalogPath,
                              @Value("${ttl.features.hotReloadEnabled:true}") boolean hotReloadEnabled) {
        this.catalogPathRaw = catalogPath;
        this.hotReloadEnabled = hotReloadEnabled;
        if (catalogPath != null && catalogPath.startsWith("classpath:")) {
            // Used by the test-only features-test.yaml so a developer's
            // ./features.yaml state never reaches the test classloader.
            this.catalogPath = null;
            this.flags = loadFlagsFromClasspath(catalogPath.substring("classpath:".length()));
        } else {
            this.catalogPath = Path.of(catalogPath).toAbsolutePath().normalize();
            this.flags = loadFlags(this.catalogPath);
            this.lastLoadedSha256 = computeSha256OrNull(this.catalogPath);
        }
    }

    /**
     * Back-compat constructor for existing tests that don't pass the
     * hot-reload toggle.
     */
    public FeatureFlagCatalog(String catalogPath) {
        this(catalogPath, false);
    }

    public Path catalogPath() {
        return catalogPath;
    }

    /** Stable identifier for the catalog source (path or classpath: URI). */
    public String catalogSource() {
        return catalogPath == null ? catalogPathRaw : catalogPath.toString();
    }

    private Map<String, FeatureFlagDefinition> loadFlagsFromClasspath(String resourcePath) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = FeatureFlagCatalog.class.getClassLoader();
        }
        try (InputStream stream = classLoader.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                log.warn("[features] classpath:{} not found; defaulting all feature flags to off", resourcePath);
                return Map.of();
            }
            return parseFlags(stream, "classpath:" + resourcePath);
        } catch (IOException ex) {
            log.warn("[features] unable to read classpath:{}: {}; defaulting all feature flags to off",
                    resourcePath, ex.getMessage());
            return Map.of();
        }
    }

    public Map<String, FeatureFlagDefinition> snapshot() {
        return flags;
    }

    public Optional<FeatureFlagDefinition> find(String flagName) {
        return Optional.ofNullable(flags.get(flagName));
    }

    public String stateOf(String flagName) {
        return find(flagName)
                .map(FeatureFlagDefinition::normalizedState)
                .orElse("off");
    }

    public boolean isEnabled(String flagName) {
        return !"off".equals(stateOf(flagName));
    }

    /**
     * #118 — Hot reload. Mirrors the SHA-256 change-detection pattern used
     * by {@code StakingPolicyCatalog} and {@code BetSettlementPolicyCatalog}.
     * Runs every {@code ttl.features.reloadFixedDelayMs} (default 30s).
     * No-op when {@code ttl.features.hotReloadEnabled} is false or the
     * catalog was loaded from classpath (tests don't reload).
     */
    @Scheduled(
            initialDelayString = "${ttl.features.reloadInitialDelayMs:30000}",
            fixedDelayString = "${ttl.features.reloadFixedDelayMs:30000}")
    public void reloadIfChanged() {
        if (!hotReloadEnabled || catalogPath == null) {
            return;
        }
        String currentSha = computeSha256OrNull(catalogPath);
        if (currentSha == null) {
            // File missing or unreadable — keep last good state.
            return;
        }
        if (currentSha.equals(lastLoadedSha256)) {
            return;
        }
        Map<String, FeatureFlagDefinition> next;
        try {
            next = loadFlags(catalogPath);
        } catch (RuntimeException ex) {
            log.warn("[features] hot-reload failed for {}: {} (keeping last good state)",
                    catalogPath, ex.getMessage());
            return;
        }
        if (next == null || next.isEmpty()) {
            // Empty parse on a non-empty file usually means malformed YAML.
            // Keep the last good state rather than blanking every flag.
            log.warn("[features] hot-reload produced empty flag map; keeping last good state ({} flags)",
                    flags == null ? 0 : flags.size());
            return;
        }
        Map<String, FeatureFlagDefinition> previous = flags;
        flags = next;
        lastLoadedSha256 = currentSha;
        log.info("[features] RELOADED {} flags from {} (was {} flags)",
                flags.size(), catalogPath, previous == null ? 0 : previous.size());
    }

    private static String computeSha256OrNull(Path path) {
        if (path == null || !Files.exists(path)) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = Files.readAllBytes(path);
            byte[] hash = md.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (IOException | NoSuchAlgorithmException ex) {
            return null;
        }
    }

    private Map<String, FeatureFlagDefinition> loadFlags(Path path) {
        if (!Files.exists(path)) {
            log.warn("[features] catalog file not found at {}; defaulting all feature flags to off", path);
            return Map.of();
        }

        try (InputStream inputStream = Files.newInputStream(path)) {
            return parseFlags(inputStream, path.toString());
        } catch (IOException ex) {
            log.warn("[features] unable to read {}: {}; defaulting all feature flags to off", path, ex.getMessage());
            return Map.of();
        } catch (RuntimeException ex) {
            log.warn("[features] unable to parse {}: {}; defaulting all feature flags to off", path, ex.getMessage());
            return Map.of();
        }
    }

    private Map<String, FeatureFlagDefinition> parseFlags(InputStream inputStream, String sourceLabel) {
        Object loaded = new Yaml().load(inputStream);
        if (!(loaded instanceof Map<?, ?> rootMap)) {
            log.warn("[features] catalog root was not a map in {}; defaulting all feature flags to off", sourceLabel);
            return Map.of();
        }

        Object featuresNode = rootMap.get("features");
        if (!(featuresNode instanceof Map<?, ?> featureMap)) {
            log.warn("[features] catalog in {} had no 'features' map; defaulting all feature flags to off", sourceLabel);
            return Map.of();
        }

        Map<String, FeatureFlagDefinition> parsed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : featureMap.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof Map<?, ?> definitionMap)) {
                continue;
            }

            parsed.put(key, new FeatureFlagDefinition(
                    asString(definitionMap.get("owner")),
                    asString(definitionMap.get("expires_on")),
                    normalizeState(asString(definitionMap.get("state"))),
                    asString(definitionMap.get("description")),
                    asStringList(definitionMap.get("allowed_states"))
            ));
        }

        log.info("[features] loaded {} feature flags from {}", parsed.size(), sourceLabel);
        return Collections.unmodifiableMap(parsed);
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static List<String> asStringList(Object value) {
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }

        List<String> converted = new ArrayList<>();
        for (Object item : rawList) {
            if (item == null) {
                continue;
            }
            converted.add(String.valueOf(item).trim());
        }
        return List.copyOf(converted);
    }

    private static String normalizeState(String state) {
        if (state == null || state.isBlank()) {
            return "off";
        }
        return state.trim().toLowerCase(Locale.ROOT);
    }

    public record FeatureFlagDefinition(
            String owner,
            String expiresOn,
            String state,
            String description,
            List<String> allowedStates
    ) {
        public String normalizedState() {
            if (state == null || state.isBlank()) {
                return "off";
            }
            return state.trim().toLowerCase(Locale.ROOT);
        }
    }
}
