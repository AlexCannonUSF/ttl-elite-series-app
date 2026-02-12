package com.ttl.tabletennis.ui.fx;

import com.ttl.tabletennis.Repository.MatchRepository;
import com.ttl.tabletennis.TtlEliteSeriesApplication;
import com.ttl.tabletennis.scrape.TtSeriesScraper;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.concurrent.Executors;

public class FxApp extends Application {

    private ConfigurableApplicationContext ctx;
    private MatchRepository matchRepo;
    private TtSeriesScraper ttScraper;

    @Override
    public void init() {
        // Spring Boot bootstrap (headless=false set inside bootForFx)
        ctx = TtlEliteSeriesApplication.bootForFx();
        matchRepo = ctx.getBean(MatchRepository.class);
        ttScraper = ctx.getBean(TtSeriesScraper.class);
    }

    @Override
    public void start(Stage stage) {
        TabPane tabs = new TabPane();
        tabs.getTabs().addAll(
                new Tab("Live", new Label("Live view (placeholder)")),
                new Tab("Trends", new Label("Trends (placeholder)")),
                new Tab("Recs", new Label("Recommendations (placeholder)"))
        );

        BorderPane root = new BorderPane(tabs);
        Scene scene = new Scene(root, 1200, 800);
        stage.setScene(scene);
        stage.setTitle("TTL Analytics");
        stage.show();

        // after stage.show();
        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                ttScraper.autoScrape();   // fire regardless of a property
                matchRepo.findAll();      // touch repo so you see DB traffic in logs
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });
    }

    @Override
    public void stop() {
        if (ctx != null) ctx.close();
    }
}