package com.ttl.tabletennis.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SpaPageResourceResolverTests {

    @TempDir
    Path tempDir;

    @Test
    void returnsRequestedAssetWhenItExists() throws IOException {
        Path distDir = Files.createDirectories(tempDir.resolve("dist"));
        Path assetsDir = Files.createDirectories(distDir.resolve("assets"));
        Files.writeString(assetsDir.resolve("app.js"), "console.log('ok');");
        Files.writeString(distDir.resolve("index.html"), "<html></html>");

        SpaPageResourceResolver resolver = new SpaPageResourceResolver("index.html");

        Resource resource = resolver.resolveResource(null, "assets/app.js", java.util.List.of(new PathResource(distDir)), null);

        assertNotNull(resource);
        assertEquals("app.js", resource.getFilename());
    }

    @Test
    void fallsBackToIndexForExtensionlessSpaRoute() throws IOException {
        Path distDir = Files.createDirectories(tempDir.resolve("dist"));
        Files.writeString(distDir.resolve("index.html"), "<html>shell</html>");

        SpaPageResourceResolver resolver = new SpaPageResourceResolver("index.html");

        Resource resource = resolver.resolveResource(null, "review/queue", java.util.List.of(new PathResource(distDir)), null);

        assertNotNull(resource);
        assertEquals("index.html", resource.getFilename());
    }

    @Test
    void fallsBackToIndexForEmptyRootPath() throws IOException {
        Path distDir = Files.createDirectories(tempDir.resolve("dist"));
        Files.writeString(distDir.resolve("index.html"), "<html>shell</html>");

        SpaPageResourceResolver resolver = new SpaPageResourceResolver("index.html");

        Resource resource = resolver.resolveResource(null, "", java.util.List.of(new PathResource(distDir)), null);

        assertNotNull(resource);
        assertEquals("index.html", resource.getFilename());
    }

    @Test
    void doesNotFallbackToIndexForMissingFileWithExtension() throws IOException {
        Path distDir = Files.createDirectories(tempDir.resolve("dist"));
        Files.writeString(distDir.resolve("index.html"), "<html>shell</html>");

        SpaPageResourceResolver resolver = new SpaPageResourceResolver("index.html");

        Resource resource = resolver.resolveResource(null, "assets/missing.js", java.util.List.of(new PathResource(distDir)), null);

        assertNull(resource);
    }
}
