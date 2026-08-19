package com.ttl.tabletennis.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.time.Duration;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Phase 07 item 7 — Cache-Control policy.
     *
     * <p>Fingerprinted bundles under {@code /v3/assets/**} (and the legacy
     * {@code /assets/**} mount) get {@code public, max-age=31536000, immutable}.
     * Vite stamps a content hash into every asset filename, so the URL itself
     * is the cache key; any change ships a new URL.
     *
     * <p>The SPA shell ({@code /v3/index.html} and the SPA fallback for
     * {@code /v3/**}) gets {@code public, max-age=0, must-revalidate,
     * stale-while-revalidate=60}. That keeps revalidations on every navigation
     * while allowing CDN edges to serve a slightly stale shell for up to 60s
     * during a deploy ripple.
     */
    static final String IMMUTABLE_ASSET_CACHE_CONTROL =
            "max-age=31536000, public, immutable";
    static final String SPA_SHELL_CACHE_CONTROL =
            "no-cache, must-revalidate, public, stale-while-revalidate=60";

    @Value("${app.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173,http://localhost:5174,http://127.0.0.1:5174}")
    private String allowedOrigins;

    @Value("${app.webUiV3DistDir:./web-v3/dist}")
    private String v3DistDir;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = allowedOrigins.split(",");
        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        CacheControl immutable = CacheControl.maxAge(Duration.ofDays(365))
                .cachePublic()
                .immutable();
        CacheControl spaShell = CacheControl.noCache()
                .cachePublic()
                .mustRevalidate()
                .staleWhileRevalidate(Duration.ofSeconds(60));

        registry.addResourceHandler("/v3/assets/**")
                .addResourceLocations(asFileLocation(v3DistDir) + "assets/",
                        "classpath:/static/v3/assets/")
                .setCacheControl(immutable)
                .resourceChain(true);

        registry.addResourceHandler("/v3/**")
                .addResourceLocations(asFileLocation(v3DistDir), "classpath:/static/v3/")
                .setCacheControl(spaShell)
                .resourceChain(true)
                .addResolver(new SpaPageResourceResolver("index.html"));

        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(immutable)
                .resourceChain(true);

        registry.addResourceHandler("/*.svg", "/*.png", "/*.ico", "/*.webmanifest")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/v3/");
        registry.addViewController("/v3").setViewName("forward:/v3/index.html");
        registry.addViewController("/v3/").setViewName("forward:/v3/index.html");
    }

    static String asFileLocation(String rawPath) {
        Path path = Path.of(rawPath).toAbsolutePath().normalize();
        String uri = path.toUri().toString();
        return uri.endsWith("/") ? uri : uri + "/";
    }
}
