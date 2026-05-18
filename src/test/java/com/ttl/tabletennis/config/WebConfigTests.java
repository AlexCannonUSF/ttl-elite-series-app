package com.ttl.tabletennis.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WebConfigTests {

    @Test
    void fileLocationsResolveToNormalizedFileUris() {
        String legacyLocation = WebConfig.asFileLocation("./web/dist");
        String v3Location = WebConfig.asFileLocation("./web-v3/dist");

        assertTrue(legacyLocation.startsWith("file:"));
        assertTrue(legacyLocation.endsWith("/web/dist/"));
        assertTrue(v3Location.startsWith("file:"));
        assertTrue(v3Location.endsWith("/web-v3/dist/"));
    }
}
