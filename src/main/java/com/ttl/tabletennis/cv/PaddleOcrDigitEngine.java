package com.ttl.tabletennis.cv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
@Order(100)
public class PaddleOcrDigitEngine implements DigitOcrEngine {

    static final String READER_NAME = "paddle";
    private static final int MAX_STDERR_BYTES = 2048;

    private final List<String> command;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    @Autowired
    public PaddleOcrDigitEngine(@Value("${ttl.streamCv.paddleOcrCommand:}") String command,
                                @Value("${ttl.streamCv.paddleOcrTimeoutMs:1500}") long timeoutMs,
                                ObjectMapper objectMapper) {
        this.command = splitCommand(command);
        this.timeout = Duration.ofMillis(Math.max(250L, timeoutMs));
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    PaddleOcrDigitEngine(List<String> command, Duration timeout, ObjectMapper objectMapper) {
        this.command = List.copyOf(command == null ? List.of() : command);
        this.timeout = timeout == null ? Duration.ofMillis(1500) : timeout;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @Override
    public String readerName() {
        return READER_NAME;
    }

    @Override
    public Optional<DigitOcrRecognition> recognize(String fieldName, BufferedImage fieldImage) {
        if (command.isEmpty() || fieldImage == null) {
            return Optional.empty();
        }
        Path tempImage = null;
        try {
            tempImage = Files.createTempFile("ttl-stream-cv-", ".png");
            ImageIO.write(fieldImage, "png", tempImage.toFile());

            List<String> processCommand = new ArrayList<>(command);
            processCommand.add(tempImage.toString());
            Process process = new ProcessBuilder(processCommand).start();
            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                return Optional.empty();
            }
            String stdout = readUtf8(process.getInputStream(), Integer.MAX_VALUE);
            if (process.exitValue() != 0) {
                readUtf8(process.getErrorStream(), MAX_STDERR_BYTES);
                return Optional.empty();
            }
            return parseRecognition(fieldName, stdout, READER_NAME, objectMapper);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        } finally {
            if (tempImage != null) {
                try {
                    Files.deleteIfExists(tempImage);
                } catch (IOException ignored) {
                    // Best-effort cleanup for transient OCR crops.
                }
            }
        }
    }

    static Optional<DigitOcrRecognition> parseRecognition(String fieldName,
                                                          String json,
                                                          String reader,
                                                          ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        ObjectMapper mapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode candidate = root;
            if (root.isArray()) {
                if (root.isEmpty()) {
                    return Optional.empty();
                }
                candidate = root.get(0);
            }
            String text = firstText(candidate, "text", "value", "label", "prediction");
            if (text.isBlank() && candidate.isTextual()) {
                text = candidate.asText();
            }
            String digits = text.trim();
            if (!digits.matches("\\d{1,2}")) {
                return Optional.empty();
            }
            double confidence = firstDouble(candidate, 0.0, "confidence", "score", "probability");
            return Optional.of(new DigitOcrRecognition(
                    fieldName,
                    Integer.parseInt(digits),
                    digits,
                    confidence,
                    reader
            ));
        } catch (RuntimeException | IOException ex) {
            return Optional.empty();
        }
    }

    private static String firstText(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                return value.asText("").trim();
            }
        }
        return "";
    }

    private static double firstDouble(JsonNode node, double fallback, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isNumber()) {
                return value.asDouble();
            }
            if (value.isTextual()) {
                try {
                    return Double.parseDouble(value.asText());
                } catch (NumberFormatException ignored) {
                    // Try the next candidate field.
                }
            }
        }
        return fallback;
    }

    private static String readUtf8(InputStream inputStream, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[512];
        int total = 0;
        int read;
        while ((read = inputStream.read(buffer)) >= 0) {
            int accepted = Math.min(read, maxBytes - total);
            if (accepted > 0) {
                output.write(buffer, 0, accepted);
                total += accepted;
            }
            if (total >= maxBytes) {
                break;
            }
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static List<String> splitCommand(String command) {
        if (command == null || command.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                continue;
            }
            if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }
            if (Character.isWhitespace(ch) && !inSingleQuote && !inDoubleQuote) {
                addCommandPart(parts, current);
                continue;
            }
            current.append(ch);
        }
        addCommandPart(parts, current);
        return List.copyOf(parts.stream()
                .filter(part -> !part.isBlank())
                .map(part -> part.toLowerCase(Locale.ROOT).equals("none") ? "" : part)
                .filter(part -> !part.isBlank())
                .toList());
    }

    private static void addCommandPart(List<String> parts, StringBuilder current) {
        if (!current.isEmpty()) {
            parts.add(current.toString());
            current.setLength(0);
        }
    }
}
