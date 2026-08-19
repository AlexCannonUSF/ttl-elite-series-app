package com.ttl.tabletennis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ttl.tabletennis.domain.PredictionModelRegistryEntry;
import com.ttl.tabletennis.repository.PredictionModelRegistryRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Resolves the immutable identity that must accompany every frozen model call. */
@Service
public class ModelArtifactIdentityService {

    private final PredictionModelRegistryRepository registryRepository;
    private final PredictionModelService predictionModelService;
    private final ObjectMapper objectMapper;

    public ModelArtifactIdentityService(PredictionModelRegistryRepository registryRepository,
                                        PredictionModelService predictionModelService,
                                        ObjectMapper objectMapper) {
        this.registryRepository = registryRepository;
        this.predictionModelService = predictionModelService;
        this.objectMapper = objectMapper;
    }

    public ModelArtifactIdentity resolve(String modelVersion) {
        String version = modelVersion == null ? "" : modelVersion.trim();
        if (!StringUtils.hasText(version) || isGenericSelector(version)) {
            return ModelArtifactIdentity.incomplete(version);
        }
        if ("baseline-runtime".equalsIgnoreCase(version)) {
            String schema = predictionModelService.featureSchemaChecksum();
            return new ModelArtifactIdentity(
                    version,
                    sha256("baseline-runtime|" + schema),
                    schema,
                    "NONE",
                    true);
        }
        PredictionModelRegistryEntry entry = registryRepository.findByModelVersion(version).orElse(null);
        if (entry == null || !StringUtils.hasText(entry.getPayloadJson())) {
            return ModelArtifactIdentity.incomplete(version);
        }
        try {
            JsonNode payload = objectMapper.readTree(entry.getPayloadJson());
            String schema = payload.path("featureSchemaHash").asText("").trim();
            String calibration = StringUtils.hasText(entry.getCalibrationMethod())
                    ? entry.getCalibrationMethod().trim()
                    : "NONE";
            boolean complete = StringUtils.hasText(schema);
            return new ModelArtifactIdentity(
                    version,
                    sha256(version + "|" + entry.getPayloadJson()),
                    schema,
                    calibration,
                    complete);
        } catch (Exception ignored) {
            return ModelArtifactIdentity.incomplete(version);
        }
    }

    public static boolean isGenericSelector(String modelVersion) {
        if (!StringUtils.hasText(modelVersion)) return true;
        String normalized = modelVersion.trim().toUpperCase(Locale.ROOT);
        return normalized.equals(PredictionModelService.FAMILY_ENSEMBLE)
                || normalized.equals(PredictionModelService.FAMILY_LOGISTIC)
                || normalized.equals(PredictionModelService.FAMILY_GBT_LIKE)
                || normalized.equals(PredictionModelService.FAMILY_RF_LIKE)
                || normalized.equals(PredictionModelService.FAMILY_BASELINE);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record ModelArtifactIdentity(String modelVersion,
                                        String artifactChecksum,
                                        String featureSchemaChecksum,
                                        String calibrationId,
                                        boolean complete) {
        static ModelArtifactIdentity incomplete(String version) {
            return new ModelArtifactIdentity(version, null, null, null, false);
        }
    }
}
