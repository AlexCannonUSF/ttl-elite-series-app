package com.ttl.tabletennis.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebConfigTests {

    @Test
    void fileLocationsResolveToNormalizedFileUris() {
        String v3Location = WebConfig.asFileLocation("./web-v3/dist");

        assertTrue(v3Location.startsWith("file:"));
        assertTrue(v3Location.endsWith("/web-v3/dist/"));
    }

    @Test
    void v3AssetsAreImmutableForOneYear() {
        // Phase 07 item 7 — fingerprinted bundles get the immutable header.
        // Spring's CacheControl renders directives in its own canonical order;
        // any compliant client treats Cache-Control directives as
        // order-insensitive, but we lock the actual wire bytes so a Spring
        // upgrade that changes the rendering is a visible failure.
        assertEquals("max-age=31536000, public, immutable", WebConfig.IMMUTABLE_ASSET_CACHE_CONTROL);

        CacheControl rendered = CacheControl.maxAge(Duration.ofDays(365))
                .cachePublic()
                .immutable();
        assertEquals(WebConfig.IMMUTABLE_ASSET_CACHE_CONTROL, rendered.getHeaderValue());
    }

    @Test
    void v3ShellUsesStaleWhileRevalidate() {
        // Phase 07 item 7 — the shell revalidates on every navigation but allows
        // CDN edges to serve a 60-second-stale copy during a deploy ripple.
        // `no-cache` + `must-revalidate` is semantically equivalent to
        // `max-age=0, must-revalidate`: both force revalidation before reuse.
        assertEquals(
                "no-cache, must-revalidate, public, stale-while-revalidate=60",
                WebConfig.SPA_SHELL_CACHE_CONTROL);

        CacheControl rendered = CacheControl.noCache()
                .cachePublic()
                .mustRevalidate()
                .staleWhileRevalidate(Duration.ofSeconds(60));
        assertEquals(WebConfig.SPA_SHELL_CACHE_CONTROL, rendered.getHeaderValue());
    }
}
