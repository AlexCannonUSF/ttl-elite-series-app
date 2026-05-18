package com.ttl.tabletennis.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173,http://localhost:5174,http://127.0.0.1:5174}")
    private String allowedOrigins;

    @Value("${app.webUiLegacyDistDir:./web/dist}")
    private String legacyDistDir;

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
        registry.addResourceHandler("/v3/**")
                .addResourceLocations(asFileLocation(v3DistDir), "classpath:/static/v3/")
                .resourceChain(true)
                .addResolver(new SpaPageResourceResolver("index.html"));

        registry.addResourceHandler("/**")
                .addResourceLocations(asFileLocation(legacyDistDir), "classpath:/static/")
                .resourceChain(true)
                .addResolver(new SpaPageResourceResolver("index.html"));
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
        registry.addViewController("/v3").setViewName("forward:/v3/index.html");
        registry.addViewController("/v3/").setViewName("forward:/v3/index.html");
    }

    static String asFileLocation(String rawPath) {
        Path path = Path.of(rawPath).toAbsolutePath().normalize();
        return path.toUri().toString();
    }
}
