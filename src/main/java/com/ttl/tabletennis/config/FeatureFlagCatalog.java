package com.ttl.tabletennis.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagCatalog.class);

    private final Path catalogPath;
    private final Map<String, FeatureFlagDefinition> flags;

    public FeatureFlagCatalog(@Value("${ttl.features.catalogPath:./features.yaml}") String catalogPath) {
        this.catalogPath = Path.of(catalogPath).toAbsolutePath().normalize();
        this.flags = loadFlags(this.catalogPath);
    }

    public Path catalogPath() {
        return catalogPath;
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

    private Map<String, FeatureFlagDefinition> loadFlags(Path path) {
        if (!Files.exists(path)) {
            log.warn("[features] catalog file not found at {}; defaulting all feature flags to off", path);
            return Map.of();
        }

        try (InputStream inputStream = Files.newInputStream(path)) {
            Object loaded = new Yaml().load(inputStream);
            if (!(loaded instanceof Map<?, ?> rootMap)) {
                log.warn("[features] catalog root was not a map in {}; defaulting all feature flags to off", path);
                return Map.of();
            }

            Object featuresNode = rootMap.get("features");
            if (!(featuresNode instanceof Map<?, ?> featureMap)) {
                log.warn("[features] catalog in {} had no 'features' map; defaulting all feature flags to off", path);
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

            log.info("[features] loaded {} feature flags from {}", parsed.size(), path);
            return Collections.unmodifiableMap(parsed);
        } catch (IOException ex) {
            log.warn("[features] unable to read {}: {}; defaulting all feature flags to off", path, ex.getMessage());
            return Map.of();
        } catch (RuntimeException ex) {
            log.warn("[features] unable to parse {}: {}; defaulting all feature flags to off", path, ex.getMessage());
            return Map.of();
        }
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
