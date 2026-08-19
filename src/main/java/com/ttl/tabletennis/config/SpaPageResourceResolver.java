package com.ttl.tabletennis.config;

import org.springframework.core.io.Resource;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

class SpaPageResourceResolver extends PathResourceResolver {

    private final String indexFileName;

    SpaPageResourceResolver(String indexFileName) {
        this.indexFileName = indexFileName;
    }

    @Override
    protected Resource getResource(String resourcePath, Resource location) throws IOException {
        if (resourcePath == null || resourcePath.isBlank()) {
            Resource index = location.createRelative(indexFileName);
            return (index.exists() && index.isReadable()) ? index : null;
        }

        Resource requested = location.createRelative(resourcePath);
        if (requested.exists() && requested.isReadable()) {
            return requested;
        }

        if (!isSpaRoute(resourcePath)) {
            return null;
        }

        Resource index = location.createRelative(indexFileName);
        return (index.exists() && index.isReadable()) ? index : null;
    }

    private static boolean isSpaRoute(@Nullable String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return true;
        }
        return !resourcePath.contains(".");
    }
}
