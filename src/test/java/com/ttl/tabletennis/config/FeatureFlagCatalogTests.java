package com.ttl.tabletennis.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureFlagCatalogTests {

    @TempDir
    Path tempDir;

    @Test
    void loadsFlagsFromYamlAndNormalizesState() throws IOException {
        Path catalogPath = tempDir.resolve("features.yaml");
        Files.writeString(catalogPath, """
                schema_version: 1
                features:
                  "features.stream-cv":
                    owner: "Alex"
                    expires_on: "2026-07-15"
                    state: "Shadow"
                    description: "Enables Stream-CV workers."
                    allowed_states:
                      - "off"
                      - "shadow"
                      - "on"
                """);

        FeatureFlagCatalog catalog = new FeatureFlagCatalog(catalogPath.toString());

        assertEquals("shadow", catalog.stateOf(FeatureFlagCatalog.STREAM_CV_FLAG));
        assertTrue(catalog.isEnabled(FeatureFlagCatalog.STREAM_CV_FLAG));
        assertEquals("Alex", catalog.find(FeatureFlagCatalog.STREAM_CV_FLAG).orElseThrow().owner());
    }

    @Test
    void missingCatalogFailsClosedToOff() {
        FeatureFlagCatalog catalog = new FeatureFlagCatalog(tempDir.resolve("missing.yaml").toString());

        assertEquals("off", catalog.stateOf(FeatureFlagCatalog.STREAM_CV_FLAG));
        assertFalse(catalog.isEnabled(FeatureFlagCatalog.STREAM_CV_FLAG));
        assertTrue(catalog.snapshot().isEmpty());
    }
}
