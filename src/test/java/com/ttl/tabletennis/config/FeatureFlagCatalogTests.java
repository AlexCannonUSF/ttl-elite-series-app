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

    // --- #118 tests: hot-reload behaviour ---

    @Test
    void reloadIfChanged_picksUpEditsToTheUnderlyingFile() throws IOException {
        Path catalogPath = tempDir.resolve("features.yaml");
        Files.writeString(catalogPath, """
                features:
                  "features.stream-cv":
                    owner: "alex"
                    state: "off"
                    allowed_states: ["off", "shadow", "on"]
                """);

        FeatureFlagCatalog catalog = new FeatureFlagCatalog(catalogPath.toString(), true);
        assertEquals("off", catalog.stateOf(FeatureFlagCatalog.STREAM_CV_FLAG));

        // Edit the file: flip stream-cv on.
        Files.writeString(catalogPath, """
                features:
                  "features.stream-cv":
                    owner: "alex"
                    state: "on"
                    allowed_states: ["off", "shadow", "on"]
                """);

        catalog.reloadIfChanged();
        assertEquals("on", catalog.stateOf(FeatureFlagCatalog.STREAM_CV_FLAG),
                "hot reload should pick up the new state");
    }

    @Test
    void reloadIfChanged_noOpWhenFileUnchanged() throws IOException {
        Path catalogPath = tempDir.resolve("features.yaml");
        Files.writeString(catalogPath, """
                features:
                  "features.stream-cv":
                    state: "shadow"
                    allowed_states: ["off", "shadow", "on"]
                """);

        FeatureFlagCatalog catalog = new FeatureFlagCatalog(catalogPath.toString(), true);
        catalog.reloadIfChanged();
        catalog.reloadIfChanged();
        assertEquals("shadow", catalog.stateOf(FeatureFlagCatalog.STREAM_CV_FLAG));
    }

    @Test
    void reloadIfChanged_disabledFlagSkipsReload() throws IOException {
        Path catalogPath = tempDir.resolve("features.yaml");
        Files.writeString(catalogPath, """
                features:
                  "features.stream-cv":
                    state: "off"
                    allowed_states: ["off", "shadow", "on"]
                """);

        FeatureFlagCatalog catalog = new FeatureFlagCatalog(catalogPath.toString(), false);
        assertEquals("off", catalog.stateOf(FeatureFlagCatalog.STREAM_CV_FLAG));

        Files.writeString(catalogPath, """
                features:
                  "features.stream-cv":
                    state: "on"
                    allowed_states: ["off", "shadow", "on"]
                """);

        catalog.reloadIfChanged();
        assertEquals("off", catalog.stateOf(FeatureFlagCatalog.STREAM_CV_FLAG),
                "with hot reload disabled, state must stay frozen at boot value");
    }

    @Test
    void reloadIfChanged_keepsLastGoodStateOnMalformedYaml() throws IOException {
        Path catalogPath = tempDir.resolve("features.yaml");
        Files.writeString(catalogPath, """
                features:
                  "features.stream-cv":
                    state: "on"
                    allowed_states: ["off", "shadow", "on"]
                """);

        FeatureFlagCatalog catalog = new FeatureFlagCatalog(catalogPath.toString(), true);
        assertEquals("on", catalog.stateOf(FeatureFlagCatalog.STREAM_CV_FLAG));

        // Overwrite with garbage that yields an empty flag map.
        Files.writeString(catalogPath, "this is not a yaml map: of features\n");
        catalog.reloadIfChanged();

        // Bug-safety: never blank every flag silently on a parse failure.
        assertEquals("on", catalog.stateOf(FeatureFlagCatalog.STREAM_CV_FLAG),
                "malformed reload should preserve last good state");
    }
}
