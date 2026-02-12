package com.ttl.tabletennis;

import com.ttl.tabletennis.scrape.TtSeriesScraper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class TtlEliteSeriesApplication {

    public static void main(String[] args) {
        SpringApplication.run(TtlEliteSeriesApplication.class, args);
    }

    /** For JavaFX launcher to bootstrap Spring without a web server. */
    public static ConfigurableApplicationContext bootForFx() {
        System.setProperty("java.awt.headless", "false");
        SpringApplication app = new SpringApplication(TtlEliteSeriesApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        return app.run();
    }

    /**
     * Kick off the scraper shortly after startup.
     * Respects -Dscrape.auto (scraper.run() will no-op if false).
     */
    @Bean
    CommandLineRunner runScraperOnBoot(TtSeriesScraper scraper,
                                       @Value("${scrape.auto:true}") boolean auto) {
        return args -> {
            // fire on a background thread so we don't block startup
            new Thread(scraper::run, "ttl-scraper").start();
        };
    }
}