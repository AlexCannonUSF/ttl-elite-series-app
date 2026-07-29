package com.ttl.tabletennis.prediction.markov;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

@Service
public class MarkovSimulator {

    private static final Logger log = LoggerFactory.getLogger(MarkovSimulator.class);
    private static final double SECONDS_PER_POINT = 8.5;

    private final boolean enabled;
    private final String endpoint;
    private final Duration timeout;
    private final int maxAttempts;
    private final MarkovHttpExchange http;
    private final ObjectMapper objectMapper;

    @Autowired
    public MarkovSimulator(
            @Value("${ttl.markov.enabled:false}") boolean enabled,
            @Value("${ttl.markov.endpoint:http://localhost:8090/v1/markov}") String endpoint,
            @Value("${ttl.markov.requestTimeoutMs:750}") long requestTimeoutMs,
            @Value("${ttl.markov.maxAttempts:2}") int maxAttempts,
            MarkovHttpExchange http,
            ObjectMapper objectMapper
    ) {
        this(
                enabled,
                endpoint,
                Duration.ofMillis(Math.max(50L, requestTimeoutMs)),
                maxAttempts,
                http,
                objectMapper
        );
    }

    MarkovSimulator(
            boolean enabled,
            String endpoint,
            Duration timeout,
            int maxAttempts,
            MarkovHttpExchange http,
            ObjectMapper objectMapper
    ) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint must not be blank");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (http == null) {
            throw new IllegalArgumentException("http must not be null");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper must not be null");
        }
        this.enabled = enabled;
        this.endpoint = endpoint.trim();
        this.timeout = timeout;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.http = http;
        this.objectMapper = objectMapper;
    }

    public MarkovSimulationResult simulate(MarkovSimulationRequest request) {
        long startNanos = System.nanoTime();
        if (!enabled) {
            return localFallback(request, "disabled-by-property", startNanos);
        }

        String body;
        try {
            body = objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            return localFallback(request, "markov-request-build: " + e.getMessage(), startNanos);
        }

        String lastReason = "not-attempted";
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                MarkovHttpExchange.Response response = http.post(endpoint, Map.of(), body, timeout);
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return parseResponse(response.body(), elapsedMs(startNanos));
                }
                lastReason = "markov-status-" + status + ": " + truncate(response.body());
                if (status < 500) {
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return localFallback(request, "markov-interrupted", startNanos);
            } catch (Exception e) {
                lastReason = "markov-http: " + e.getMessage();
            }
        }

        log.warn("[markov-simulator] using local fallback after {} attempt(s): {}", maxAttempts, lastReason);
        return localFallback(request, lastReason, startNanos);
    }

    MarkovSimulationResult parseResponse(String body, long latencyMs) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(body);
        return MarkovSimulationResult.ok(
                root.path("matchId").asText(""),
                clampProbability(root.path("pMatchTop").asDouble(Double.NaN)),
                nullableProbability(root, "p_3_0"),
                nullableProbability(root, "p_3_1"),
                nullableProbability(root, "p_3_2"),
                nullablePositive(root, "expTotalPoints"),
                nullablePositive(root, "medianMatchMinutes"),
                root.path("method").asText("unknown"),
                root.path("version").asText("unknown"),
                root.path("note").asText(""),
                latencyMs
        );
    }

    private MarkovSimulationResult localFallback(
            MarkovSimulationRequest request,
            String reason,
            long startNanos
    ) {
        double pPoint = request.pPointTopOnReceive() == null
                ? request.pPointTopOnServe()
                : (request.pPointTopOnServe() + request.pPointTopOnReceive()) / 2.0;
        pPoint = clampProbability(pPoint);
        double pGame = gameWinProbability(pPoint);
        double pMatch = matchWinProbability(pGame, request.bestOf());
        int target = request.bestOf() / 2 + 1;
        Double p30 = null;
        Double p31 = null;
        Double p32 = null;
        if (target == 3) {
            double qGame = 1.0 - pGame;
            p30 = clampProbability(Math.pow(pGame, 3));
            p31 = clampProbability(3.0 * Math.pow(pGame, 3) * qGame);
            p32 = clampProbability(6.0 * Math.pow(pGame, 3) * Math.pow(qGame, 2));
        }
        double expGames = expectedGames(pGame, request.bestOf());
        double expPoints = expGames * approximateExpectedPointsPerGame(pPoint);
        return MarkovSimulationResult.fallback(
                request.matchId(),
                pMatch,
                p30,
                p31,
                p32,
                round(expPoints),
                round(expPoints * SECONDS_PER_POINT / 60.0),
                "java-local-fallback-iid",
                reason,
                elapsedMs(startNanos)
        );
    }

    private static double gameWinProbability(double p) {
        double q = 1.0 - p;
        double winBeforeDeuce = 0.0;
        for (int loserPoints = 0; loserPoints <= 9; loserPoints++) {
            winBeforeDeuce += binomial(10 + loserPoints, loserPoints)
                    * Math.pow(p, 11)
                    * Math.pow(q, loserPoints);
        }
        double deuce = binomial(20, 10) * Math.pow(p, 10) * Math.pow(q, 10);
        double deuceWin = (p * p) / Math.max(1.0e-12, (p * p) + (q * q));
        return clampProbability(winBeforeDeuce + deuce * deuceWin);
    }

    private static double matchWinProbability(double pGame, int bestOf) {
        int target = bestOf / 2 + 1;
        double qGame = 1.0 - pGame;
        double total = 0.0;
        for (int losses = 0; losses < target; losses++) {
            total += binomial(target - 1 + losses, losses)
                    * Math.pow(pGame, target)
                    * Math.pow(qGame, losses);
        }
        return clampProbability(total);
    }

    private static double expectedGames(double pGame, int bestOf) {
        int target = bestOf / 2 + 1;
        double qGame = 1.0 - pGame;
        double total = 0.0;
        for (int losses = 0; losses < target; losses++) {
            double paths = binomial(target - 1 + losses, losses);
            double topWins = paths * Math.pow(pGame, target) * Math.pow(qGame, losses);
            double bottomWins = paths * Math.pow(qGame, target) * Math.pow(pGame, losses);
            total += (target + losses) * (topWins + bottomWins);
        }
        return Math.max(1.0, total);
    }

    private static double approximateExpectedPointsPerGame(double pPoint) {
        double balance = 1.0 - Math.min(1.0, Math.abs(pPoint - 0.5) * 2.0);
        return 17.0 + 4.5 * balance;
    }

    private static double binomial(int n, int k) {
        if (k < 0 || k > n) {
            return 0.0;
        }
        int m = Math.min(k, n - k);
        double result = 1.0;
        for (int i = 1; i <= m; i++) {
            result = result * (n - (m - i)) / i;
        }
        return result;
    }

    private static Double nullableProbability(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return clampProbability(node.asDouble(Double.NaN));
    }

    private static Double nullablePositive(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        double value = node.asDouble(Double.NaN);
        if (!Double.isFinite(value)) {
            return null;
        }
        return Math.max(0.0, value);
    }

    private static double clampProbability(double value) {
        if (!Double.isFinite(value)) {
            return 0.5;
        }
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private static String truncate(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.length() > 200 ? raw.substring(0, 200) + "..." : raw;
    }
}
