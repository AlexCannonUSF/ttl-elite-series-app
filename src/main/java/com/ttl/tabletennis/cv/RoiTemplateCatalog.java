package com.ttl.tabletennis.cv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

@Component
public class RoiTemplateCatalog {

    private static final Logger log = LoggerFactory.getLogger(RoiTemplateCatalog.class);

    private final Path templateRoot;
    private final Map<String, RoiTemplate> templates;
    private final ObjectMapper objectMapper;

    @Autowired
    public RoiTemplateCatalog(@Value("${ttl.streamCv.roiTemplateRoot:./cv-assets/roi}") String templateRoot,
                              ObjectMapper objectMapper) {
        this(Path.of(templateRoot), objectMapper);
    }

    public RoiTemplateCatalog(Path templateRoot, ObjectMapper objectMapper) {
        this.templateRoot = templateRoot.toAbsolutePath().normalize();
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.templates = loadTemplates(this.templateRoot);
    }

    public Path templateRoot() {
        return templateRoot;
    }

    public List<RoiTemplate> templates() {
        return List.copyOf(templates.values());
    }

    public Optional<RoiTemplate> find(String templateId) {
        if (templateId == null || templateId.trim().isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(templates.get(templateId.trim()));
    }

    private Map<String, RoiTemplate> loadTemplates(Path root) {
        if (!Files.isDirectory(root)) {
            log.info("[stream-cv] ROI template root not found at {}; BoardLocator will have no templates", root);
            return Map.of();
        }

        Map<String, RoiTemplate> parsed = new LinkedHashMap<>();
        try (Stream<Path> children = Files.list(root)) {
            children
                    .filter(Files::isDirectory)
                    .map(path -> path.resolve("roi.json"))
                    .filter(Files::isRegularFile)
                    .forEach(path -> parseTemplate(path).ifPresent(template -> parsed.put(template.templateId(), template)));
        } catch (IOException ex) {
            log.warn("[stream-cv] unable to list ROI templates under {}: {}", root, ex.getMessage());
        }
        log.info("[stream-cv] loaded {} ROI templates from {}", parsed.size(), root);
        return Map.copyOf(parsed);
    }

    private Optional<RoiTemplate> parseTemplate(Path path) {
        try {
            JsonNode root = objectMapper.readTree(path.toFile());
            RoiTemplate template = new RoiTemplate(
                    text(root, "templateId"),
                    integer(root, "frameWidth"),
                    integer(root, "frameHeight"),
                    rectangle(root.path("roi")),
                    text(root, "colorProfile"),
                    digitFields(root.path("digitFields"))
            );
            return Optional.of(template);
        } catch (RuntimeException | IOException ex) {
            log.warn("[stream-cv] skipping invalid ROI template {}: {}", path, ex.getMessage());
            return Optional.empty();
        }
    }

    private List<DigitFieldTemplate> digitFields(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<DigitFieldTemplate> fields = new ArrayList<>();
        for (JsonNode fieldNode : node) {
            fields.add(new DigitFieldTemplate(text(fieldNode, "name"), rectangleFromArray(fieldNode.path("rel"))));
        }
        return fields;
    }

    private RoiRectangle rectangle(JsonNode node) {
        return new RoiRectangle(
                integer(node, "x"),
                integer(node, "y"),
                integer(node, "w"),
                integer(node, "h")
        );
    }

    private RoiRectangle rectangleFromArray(JsonNode node) {
        if (!node.isArray() || node.size() != 4) {
            throw new IllegalArgumentException("relative rectangle must be [x,y,w,h]");
        }
        return new RoiRectangle(node.get(0).asInt(), node.get(1).asInt(), node.get(2).asInt(), node.get(3).asInt());
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText().trim();
    }

    private int integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return value.asInt();
    }
}
