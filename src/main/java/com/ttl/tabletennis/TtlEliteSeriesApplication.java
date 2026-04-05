package com.ttl.tabletennis;

import com.ttl.tabletennis.scrape.TtSeriesScraper;
import com.ttl.tabletennis.service.Glicko2RatingService;
import com.ttl.tabletennis.service.TtSeriesEloSyncService;
import com.ttl.tabletennis.repository.RatingSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;

@SpringBootApplication
@EnableScheduling
public class TtlEliteSeriesApplication {

    private static final Logger log = LoggerFactory.getLogger(TtlEliteSeriesApplication.class);
    private static final String DEFAULT_LOCAL_H2_URL = "jdbc:h2:file:./data/ttl;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE";
    private static final String DEFAULT_LOCAL_H2_MEMORY_URL = "jdbc:h2:mem:ttl-dev-fallback;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
    private static final String DEFAULT_LOCAL_H2_USER = "sa";
    private static final String DEFAULT_LOCAL_H2_PASSWORD = "";
    private static final String DEFAULT_LOCAL_H2_DRIVER = "org.h2.Driver";
    private static final String DEFAULT_LOCAL_H2_DIALECT = "org.hibernate.dialect.H2Dialect";

    public static void main(String[] args) {
        ensureDefaultDataSourceConfig();
        SpringApplication.run(TtlEliteSeriesApplication.class, args);
    }

    /** For JavaFX launcher to bootstrap Spring without a web server. */
    public static ConfigurableApplicationContext bootForFx() {
        System.setProperty("java.awt.headless", "false");
        ensureDefaultDataSourceConfig();
        SpringApplication app = new SpringApplication(TtlEliteSeriesApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        return app.run();
    }

    private static void ensureDefaultDataSourceConfig() {
        boolean explicitDataSourceUrl = hasConfiguredValue("spring.datasource.url");

        applyDefaultWhenBlank("spring.datasource.url", DEFAULT_LOCAL_H2_URL);
        applyDefaultWhenBlank("spring.datasource.username", DEFAULT_LOCAL_H2_USER);
        applyDefaultWhenBlank("spring.datasource.password", DEFAULT_LOCAL_H2_PASSWORD);

        if (!explicitDataSourceUrl) {
            applyDefaultWhenBlank("spring.datasource.driver-class-name", DEFAULT_LOCAL_H2_DRIVER);
            applyDefaultWhenBlank("spring.jpa.properties.hibernate.dialect", DEFAULT_LOCAL_H2_DIALECT);

            String configuredUrl = System.getProperty("spring.datasource.url");
            String configuredUsername = System.getProperty("spring.datasource.username", DEFAULT_LOCAL_H2_USER);
            String configuredPassword = System.getProperty("spring.datasource.password", DEFAULT_LOCAL_H2_PASSWORD);

            if (DEFAULT_LOCAL_H2_URL.equals(configuredUrl) && !canOpenLocalH2(configuredUrl, configuredUsername, configuredPassword)) {
                System.setProperty("spring.datasource.url", DEFAULT_LOCAL_H2_MEMORY_URL);
                log.warn("[startup] local H2 file database is unavailable or locked; falling back to in-memory H2 for this run");
            }
        }
    }

    private static void applyDefaultWhenBlank(String springProperty, String fallbackValue) {
        String current = System.getProperty(springProperty);
        if (current != null && !current.isBlank()) {
            return;
        }

        String envKey = springProperty.toUpperCase(Locale.ROOT).replace('.', '_');
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return;
        }

        System.setProperty(springProperty, fallbackValue);
    }

    private static boolean hasConfiguredValue(String springProperty) {
        String current = System.getProperty(springProperty);
        if (current != null && !current.isBlank()) {
            return true;
        }

        String envKey = springProperty.toUpperCase(Locale.ROOT).replace('.', '_');
        String envValue = System.getenv(envKey);
        return envValue != null && !envValue.isBlank();
    }

    private static boolean canOpenLocalH2(String jdbcUrl, String username, String password) {
        try {
            Class.forName(DEFAULT_LOCAL_H2_DRIVER);
            try (Connection ignored = DriverManager.getConnection(jdbcUrl, username, password)) {
                return true;
            }
        } catch (ClassNotFoundException e) {
            log.warn("[startup] H2 driver not found while validating local datasource: {}", e.getMessage());
            return false;
        } catch (SQLException e) {
            log.warn("[startup] unable to open local H2 datasource '{}': {}", jdbcUrl, e.getMessage());
            return false;
        }
    }

    /**
     * Kick off the scraper shortly after startup.
     * Respects -Dscrape.auto (scraper.run() will no-op if false).
     */
    @Bean
    CommandLineRunner runScraperOnBoot(TtSeriesScraper scraper,
                                       @Value("${scrape.auto:true}") boolean auto) {
        return args -> {
            if (!auto) {
                return;
            }
            new Thread(scraper::run, "ttl-scraper").start();
        };
    }

    @Bean
    CommandLineRunner runEloSyncOnBoot(TtSeriesEloSyncService eloSyncService,
                                       @Value("${ttl.elo.sync.enabled:true}") boolean enabled,
                                       @Value("${ttl.elo.sync.onStartup:true}") boolean onStartup) {
        return args -> {
            if (!enabled || !onStartup) {
                return;
            }
            new Thread(eloSyncService::syncFromRankingPage, "ttl-elo-sync").start();
        };
    }

    @Bean
    CommandLineRunner runGlickoBootstrapOnBoot(Glicko2RatingService glicko2RatingService,
                                               RatingSnapshotRepository ratingSnapshotRepository,
                                               @Value("${ttl.glicko2.rebuildOnStartupIfMissing:true}") boolean rebuildOnStartupIfMissing) {
        return args -> {
            if (!rebuildOnStartupIfMissing) {
                return;
            }
            if (ratingSnapshotRepository.countByRatingSystem("GLICKO2") > 0) {
                return;
            }
            new Thread(() -> {
                try {
                    glicko2RatingService.rebuild(null, null);
                } catch (Exception ex) {
                    log.warn("[glicko2] startup bootstrap failed: {}", ex.getMessage(), ex);
                }
            }, "ttl-glicko2-bootstrap").start();
        };
    }
}
