package com.ttl.tabletennis.prediction.shadow;

import com.ttl.tabletennis.config.FeatureFlagCatalog;
import com.ttl.tabletennis.domain.PredictionDiffLog;
import com.ttl.tabletennis.repository.PredictionDiffLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PredictionShadowServiceTests {

    @TempDir
    Path tempDir;

    @Test
    void shadowPersistsDiffOnHappyPath() throws IOException {
        PredictionDiffLogRepository repo = mock(PredictionDiffLogRepository.class);
        when(repo.save(any(PredictionDiffLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BlenderClient blender = stubBlender(BlenderClient.Result.ok(
                response(0.61),
                17L
        ));
        PredictionShadowService service = newService(repo, blender, "shadow", "abc-hash", samplerAlwaysOn());

        service.shadow(context(0.55, "match-1"));

        ArgumentCaptor<PredictionDiffLog> captor = ArgumentCaptor.forClass(PredictionDiffLog.class);
        verify(repo).save(captor.capture());
        PredictionDiffLog row = captor.getValue();
        assertEquals("OK", row.getShadowStatus());
        assertEquals(BigDecimal.valueOf(0.55).setScale(6), row.getV2P1Probability());
        assertEquals(BigDecimal.valueOf(0.61).setScale(6), row.getV3P1Probability());
        assertEquals(BigDecimal.valueOf(0.06).setScale(6), row.getAbsDiff());
        assertEquals("CONFIDENT_TOP", row.getV3UncertaintyLabel());
        assertEquals(17L, row.getLatencyMs());
    }

    @Test
    void shadowPersistsVariantBSanityFieldsWhenPresent() throws IOException {
        PredictionDiffLogRepository repo = mock(PredictionDiffLogRepository.class);
        when(repo.save(any(PredictionDiffLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BlenderResponse withSanity = response(0.61, java.util.Optional.of(sanity(0.57, 0.61)));
        BlenderClient blender = stubBlender(BlenderClient.Result.ok(withSanity, 19L));
        PredictionShadowService service = newService(repo, blender, "shadow", "abc-hash", samplerAlwaysOn());

        service.shadow(context(0.55, "match-1"));

        ArgumentCaptor<PredictionDiffLog> captor = ArgumentCaptor.forClass(PredictionDiffLog.class);
        verify(repo).save(captor.capture());
        PredictionDiffLog row = captor.getValue();
        assertEquals("OK", row.getShadowStatus());
        assertEquals(BigDecimal.valueOf(0.61).setScale(6), row.getV3P1Probability());
        assertEquals("v3.0.0-variant-b", row.getV3VariantBModelVersion());
        assertEquals(BigDecimal.valueOf(0.57).setScale(6), row.getV3VariantBP1Probability());
        assertEquals(BigDecimal.valueOf(0.04).setScale(6), row.getVariantAbAbsDiff());
    }

    @Test
    void shadowLeavesVariantBFieldsNullWhenSanityAbsent() throws IOException {
        PredictionDiffLogRepository repo = mock(PredictionDiffLogRepository.class);
        when(repo.save(any(PredictionDiffLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BlenderClient blender = stubBlender(BlenderClient.Result.ok(response(0.61), 17L));
        PredictionShadowService service = newService(repo, blender, "shadow", "abc-hash", samplerAlwaysOn());

        service.shadow(context(0.55, "match-1"));

        ArgumentCaptor<PredictionDiffLog> captor = ArgumentCaptor.forClass(PredictionDiffLog.class);
        verify(repo).save(captor.capture());
        PredictionDiffLog row = captor.getValue();
        assertNull(row.getV3VariantBModelVersion());
        assertNull(row.getV3VariantBP1Probability());
        assertNull(row.getVariantAbAbsDiff());
    }

    @Test
    void shadowPersistsRowWhenBlenderReturnsSchemaMismatch() throws IOException {
        PredictionDiffLogRepository repo = mock(PredictionDiffLogRepository.class);
        when(repo.save(any(PredictionDiffLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BlenderClient blender = stubBlender(BlenderClient.Result.schemaHashMismatch("hash mismatch", 11L));
        PredictionShadowService service = newService(repo, blender, "shadow", "abc-hash", samplerAlwaysOn());

        service.shadow(context(0.5, "m1"));

        ArgumentCaptor<PredictionDiffLog> captor = ArgumentCaptor.forClass(PredictionDiffLog.class);
        verify(repo).save(captor.capture());
        PredictionDiffLog row = captor.getValue();
        assertEquals("SCHEMA_HASH_MISMATCH", row.getShadowStatus());
        assertEquals("hash mismatch", row.getErrorReason());
        assertNull(row.getV3P1Probability());
        assertNull(row.getAbsDiff());
    }

    @Test
    void shadowPersistsDisabledWhenFeatureSchemaHashNotConfigured() throws IOException {
        PredictionDiffLogRepository repo = mock(PredictionDiffLogRepository.class);
        BlenderClient blender = stubBlender(BlenderClient.Result.ok(response(0.6), 5L));
        PredictionShadowService service = newService(repo, blender, "shadow", "", samplerAlwaysOn());

        service.shadow(context(0.5, "m1"));

        ArgumentCaptor<PredictionDiffLog> captor = ArgumentCaptor.forClass(PredictionDiffLog.class);
        verify(repo).save(captor.capture());
        assertEquals("DISABLED", captor.getValue().getShadowStatus());
    }

    @Test
    void shadowSkipsWhenFlagOff() throws IOException {
        PredictionDiffLogRepository repo = mock(PredictionDiffLogRepository.class);
        BlenderClient blender = stubBlender(BlenderClient.Result.ok(response(0.6), 5L));
        PredictionShadowService service = newService(repo, blender, "off", "abc", samplerAlwaysOn());

        service.shadow(context(0.5, "m1"));

        verify(repo, never()).save(any());
    }

    @Test
    void shadowSkipsWhenSamplerSaysNo() throws IOException {
        PredictionDiffLogRepository repo = mock(PredictionDiffLogRepository.class);
        BlenderClient blender = stubBlender(BlenderClient.Result.ok(response(0.6), 5L));
        PredictionShadowSampler sampler = new PredictionShadowSampler(0.0);
        PredictionShadowService service = newService(repo, blender, "shadow", "abc", sampler);

        service.shadow(context(0.5, "m1"));

        verify(repo, never()).save(any());
    }

    @Test
    void shadowSkipsWhenBlenderClientReportsDisabled() throws IOException {
        PredictionDiffLogRepository repo = mock(PredictionDiffLogRepository.class);
        BlenderClient blender = new DisabledBlenderClient("disabled-by-property");
        PredictionShadowService service = newService(repo, blender, "shadow", "abc", samplerAlwaysOn());

        service.shadow(context(0.5, "m1"));

        verify(repo, never()).save(any());
    }

    private PredictionShadowService newService(PredictionDiffLogRepository repo,
                                                BlenderClient blender,
                                                String flagState,
                                                String featureSchemaHash,
                                                PredictionShadowSampler sampler) throws IOException {
        FeatureFlagCatalog catalog = catalogWithState(flagState);
        return new PredictionShadowService(
                catalog,
                sampler,
                blender,
                repo,
                Runnable::run,
                Clock.fixed(Instant.parse("2026-05-18T03:04:05Z"), ZoneOffset.UTC),
                Duration.ofMillis(500),
                featureSchemaHash
        );
    }

    private FeatureFlagCatalog catalogWithState(String state) throws IOException {
        Path catalog = tempDir.resolve("features-" + UUID.randomUUID() + ".yaml");
        Files.writeString(catalog, """
                schema_version: 1
                features:
                  "features.predict-v3":
                    owner: "Alex"
                    expires_on: "2026-12-31"
                    state: "%s"
                    description: "Routes prediction traffic into the 3.0 prediction stack and Python microservice."
                    allowed_states:
                      - "off"
                      - "shadow"
                      - "on"
                """.formatted(state));
        return new FeatureFlagCatalog(catalog.toString());
    }

    private PredictionShadowSampler samplerAlwaysOn() {
        return new PredictionShadowSampler(1.0);
    }

    private static PredictionShadowService.ShadowContext context(double v2Prob, String matchId) {
        return new PredictionShadowService.ShadowContext(
                "pred-1",
                10L,
                20L,
                LocalDate.of(2026, 5, 18),
                matchId,
                "ensemble",
                "v2.3.1",
                v2Prob,
                5,
                false,
                true,
                Map.of("rater.ensemble.delta", 0.12)
        );
    }

    private static BlenderResponse response(double pTop) {
        return response(pTop, java.util.Optional.empty());
    }

    private static BlenderResponse response(double pTop, java.util.Optional<BlenderResponse.Sanity> sanity) {
        return new BlenderResponse(
                "m1",
                "v3.0.0",
                "platt+iso-v3.0.0",
                "mondrian-split-v3.0.0",
                "abc-hash",
                pTop,
                1.0 - pTop,
                pTop - 0.02,
                "CONFIDENT_TOP",
                0.1,
                12.5,
                sanity
        );
    }

    private static BlenderResponse.Sanity sanity(double pTop, double primaryPTop) {
        return new BlenderResponse.Sanity(
                "B",
                "v3.0.0-variant-b",
                "platt+iso-v3.0.0",
                "mondrian-split-v3.0.0",
                "def-hash",
                pTop,
                1.0 - pTop,
                "CONFIDENT_TOP",
                Math.abs(primaryPTop - pTop),
                9.0
        );
    }

    private static BlenderClient stubBlender(BlenderClient.Result result) {
        return new BlenderClient() {
            @Override
            public Result score(BlenderRequest request, Duration timeout) {
                assertTrue(request.matchId() != null && !request.matchId().isBlank());
                return result;
            }
        };
    }
}
