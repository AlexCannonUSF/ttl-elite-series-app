package com.ttl.tabletennis.cv;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class StreamRouteCatalog {

    private static final Logger log = LoggerFactory.getLogger(StreamRouteCatalog.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");

    private final Path routesPath;
    private final List<StreamRoute> routes;
    private final List<String> warnings;

    @Autowired
    public StreamRouteCatalog(@Value("${ttl.streamCv.routesPath:./stream_routes.yaml}") String routesPath) {
        this(Path.of(routesPath));
    }

    public StreamRouteCatalog(Path routesPath) {
        this.routesPath = routesPath.toAbsolutePath().normalize();
        LoadedRoutes loadedRoutes = loadRoutes(this.routesPath);
        this.routes = loadedRoutes.routes();
        this.warnings = loadedRoutes.warnings();
    }

    public Path routesPath() {
        return routesPath;
    }

    public List<StreamRoute> routes() {
        return routes;
    }

    public List<String> warnings() {
        return warnings;
    }

    public Optional<StreamRoute> find(StreamRoutingRequest request) {
        return routes.stream()
                .filter(route -> route.matches(request))
                .findFirst();
    }

    private LoadedRoutes loadRoutes(Path path) {
        if (!Files.exists(path)) {
            log.info("[stream-cv] route catalog not found at {}; StreamRouter will rely on per-match URLs only", path);
            return LoadedRoutes.empty();
        }

        try (InputStream inputStream = Files.newInputStream(path)) {
            Object loaded = new Yaml().load(inputStream);
            Optional<List<?>> routesNode = routesNode(loaded);
            if (routesNode.isEmpty()) {
                log.warn("[stream-cv] route catalog {} did not contain a routes list", path);
                return new LoadedRoutes(List.of(), List.of("missing routes list"));
            }

            Map<String, StreamRoute> parsed = new LinkedHashMap<>();
            List<String> warnings = new ArrayList<>();
            int index = 0;
            for (Object rawRoute : routesNode.get()) {
                index++;
                int routeIndex = index;
                parseRoute(rawRoute, routeIndex, warnings).ifPresent(route -> {
                    if (parsed.containsKey(route.key())) {
                        warnings.add("duplicate route key " + route.key() + " at routes[" + routeIndex + "] ignored");
                    } else {
                        parsed.put(route.key(), route);
                    }
                });
            }
            if (warnings.isEmpty()) {
                log.info("[stream-cv] loaded {} stream route overrides from {}", parsed.size(), path);
            } else {
                log.warn("[stream-cv] loaded {} stream route overrides from {} with {} warning(s)",
                        parsed.size(), path, warnings.size());
            }
            return new LoadedRoutes(List.copyOf(parsed.values()), List.copyOf(warnings));
        } catch (IOException ex) {
            log.warn("[stream-cv] unable to read stream route catalog {}: {}", path, ex.getMessage());
            return new LoadedRoutes(List.of(), List.of("read error: " + ex.getMessage()));
        } catch (RuntimeException ex) {
            log.warn("[stream-cv] unable to parse stream route catalog {}: {}", path, ex.getMessage());
            return new LoadedRoutes(List.of(), List.of("parse error: " + ex.getMessage()));
        }
    }

    private Optional<List<?>> routesNode(Object loaded) {
        if (loaded instanceof List<?> rootList) {
            return Optional.of(rootList);
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            return Optional.empty();
        }
        Object routesNode = first(root, "routes", "streamRoutes", "stream_routes");
        if (routesNode instanceof List<?> rawRoutes) {
            return Optional.of(rawRoutes);
        }
        return Optional.empty();
    }

    private Optional<StreamRoute> parseRoute(Object rawRoute, int index, List<String> warnings) {
        if (!(rawRoute instanceof Map<?, ?> routeMap)) {
            warnings.add("routes[" + index + "] was not a map");
            return Optional.empty();
        }
        Map<?, ?> matchMap = routeMap.get("match") instanceof Map<?, ?> typedMatch ? typedMatch : Map.of();

        String eventCode = asString(first(matchMap, "eventCode", "event_code", "event"));
        if (eventCode.isBlank()) {
            eventCode = asString(first(routeMap, "eventCode", "event_code", "event"));
        }
        String tableNumber = asString(first(matchMap, "tableNumber", "table_number", "table"));
        if (tableNumber.isBlank()) {
            tableNumber = asString(first(routeMap, "tableNumber", "table_number", "table"));
        }
        StreamPlatform platform = StreamPlatform.fromValue(asString(first(routeMap, "platform", "streamPlatform")))
                .orElse(StreamPlatform.UNKNOWN);
        String channelId = asString(first(routeMap, "channelId", "channel_id", "channel", "youtubeChannelId", "youtube_channel_id"));
        String baseUrl = expandPlaceholders(asString(first(routeMap, "baseUrl", "base_url", "urlTemplate", "url_template")));
        String streamUrl = expandPlaceholders(asString(first(routeMap, "streamUrl", "stream_url", "url", "directUrl", "direct_url")));
        String roiTemplateId = asString(first(routeMap, "roiTemplateId", "roi_template_id", "templateId", "template_id"));
        if (channelId.isBlank() && baseUrl.isBlank() && streamUrl.isBlank()) {
            warnings.add("routes[" + index + "] for " + eventCode + ":" + tableNumber + " had no stream URL, base URL, or channel id");
            return Optional.empty();
        }
        try {
            return Optional.of(new StreamRoute(
                    eventCode,
                    tableNumber,
                    platform,
                    channelId,
                    baseUrl,
                    streamUrl,
                    roiTemplateId,
                    asString(first(routeMap, "notes", "note"))
            ));
        } catch (IllegalArgumentException ex) {
            warnings.add("routes[" + index + "] skipped: " + ex.getMessage());
            return Optional.empty();
        }
    }

    private static Object first(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String expandPlaceholders(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        Matcher matcher = PLACEHOLDER.matcher(value);
        StringBuilder expanded = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String fallback = matcher.group(2) == null ? "" : matcher.group(2);
            String replacement = Optional.ofNullable(System.getenv(key))
                    .or(() -> Optional.ofNullable(System.getProperty(key)))
                    .orElse(fallback);
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(expanded);
        return expanded.toString().trim();
    }

    private record LoadedRoutes(List<StreamRoute> routes, List<String> warnings) {
        private LoadedRoutes {
            routes = List.copyOf(routes == null ? List.of() : routes);
            warnings = List.copyOf(warnings == null ? List.of() : warnings);
        }

        static LoadedRoutes empty() {
            return new LoadedRoutes(List.of(), List.of());
        }
    }
}
