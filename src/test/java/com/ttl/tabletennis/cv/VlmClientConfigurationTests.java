package com.ttl.tabletennis.cv;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VlmClientConfigurationTests {

    private final VlmClientConfiguration config = new VlmClientConfiguration();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VlmResponseParser parser = new VlmResponseParser(objectMapper);
    private final VlmHttpExchange http = new NoopHttp();

    @Test
    void disabledEngineYieldsDisabledClient() {
        VlmClient client = build("disabled", "k1", "k2");
        assertInstanceOf(DisabledVlmClient.class, client);
        assertEquals(DisabledVlmClient.ENGINE_ID, client.engineId());
    }

    @Test
    void unknownEngineFallsBackToDisabled() {
        VlmClient client = build("mystery", "k1", "k2");
        assertInstanceOf(DisabledVlmClient.class, client);
    }

    @Test
    void geminiEngineWithKeyYieldsGeminiClient() {
        VlmClient client = build("gemini-flash", "gemini-key", "");
        assertInstanceOf(GeminiFlashVisionClient.class, client);
        assertEquals(GeminiFlashVisionClient.ENGINE_ID, client.engineId());
    }

    @Test
    void claudeEngineWithKeyYieldsClaudeClient() {
        VlmClient client = build("claude-haiku", "", "claude-key");
        assertInstanceOf(ClaudeHaikuVisionClient.class, client);
        assertEquals(ClaudeHaikuVisionClient.ENGINE_ID, client.engineId());
    }

    @Test
    void engineWithoutKeyFallsBackToDisabled() {
        assertInstanceOf(DisabledVlmClient.class, build("gemini-flash", "", ""));
        assertInstanceOf(DisabledVlmClient.class, build("claude-haiku", "", ""));
    }

    @Test
    void engineLookupIsCaseInsensitive() {
        assertInstanceOf(GeminiFlashVisionClient.class, build("Gemini-Flash", "k", ""));
        assertInstanceOf(ClaudeHaikuVisionClient.class, build("CLAUDE-HAIKU", "", "k"));
    }

    private VlmClient build(String engine, String geminiKey, String claudeKey) {
        return config.vlmClient(
                engine,
                geminiKey,
                GeminiFlashVisionClient.DEFAULT_ENDPOINT,
                claudeKey,
                ClaudeHaikuVisionClient.DEFAULT_MODEL_ID,
                ClaudeHaikuVisionClient.DEFAULT_ENDPOINT,
                ClaudeHaikuVisionClient.DEFAULT_ANTHROPIC_VERSION,
                http,
                objectMapper,
                parser
        );
    }

    private static final class NoopHttp implements VlmHttpExchange {
        @Override
        public Response post(String url, Map<String, String> headers, String body, Duration timeout) throws IOException {
            throw new IOException("not exercised in this test");
        }
    }
}
