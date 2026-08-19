package com.ttl.tabletennis.domain;

import com.ttl.tabletennis.util.CorrelationContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "scrape_error", indexes = {
        @Index(name = "idx_scrape_error_run_id", columnList = "scrape_run_id"),
        @Index(name = "idx_scrape_error_occurred_at", columnList = "occurred_at")
})
public class ScrapeError {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scrape_run_id")
    private ScrapeRun scrapeRun;

    @Column(name = "run_number")
    private Integer runNumber;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "mode", length = 40)
    private String mode;

    @Column(name = "url", length = 1200)
    private String url;

    @Column(name = "context", length = 255)
    private String context;

    @Column(name = "message", length = 2000)
    private String message;

    @Column(name = "html_snippet", length = 4000)
    private String htmlSnippet;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    /**
     * #121 — Coarse classification of the failure ({@code GZIP},
     * {@code TIMEOUT}, {@code NETWORK}, {@code PARSE}, {@code OTHER}).
     * Populated by {@link #prePersist()} from the {@link #message} when
     * not explicitly set. Lets operators alert on specific failure
     * patterns (e.g. "GZIP regressions in the last hour") instead of a
     * flat counter that fires on every transient timeout.
     */
    @Column(name = "error_class", length = 32)
    private String errorClass;

    @PrePersist
    void prePersist() {
        if (occurredAt == null) {
            occurredAt = LocalDateTime.now();
        }
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = CorrelationContext.currentOrCreate();
        }
        if (errorClass == null || errorClass.isBlank()) {
            errorClass = classifyMessage(message);
        }
    }

    /**
     * Heuristic message → class mapping. Order matters — more specific
     * matchers come first.
     */
    static String classifyMessage(String message) {
        if (message == null || message.isBlank()) {
            return "OTHER";
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("gzip") || lower.contains("deflate")) {
            return "GZIP";
        }
        if (lower.contains("timed out") || lower.contains("timeout") || lower.contains("read time")) {
            return "TIMEOUT";
        }
        if (lower.contains("watchdog")) {
            return "WATCHDOG";
        }
        if (lower.contains("connection reset")
                || lower.contains("connection refused")
                || lower.contains("unknownhost")
                || lower.contains("unable to resolve")
                || lower.contains("no route to host")
                || lower.contains("ssl")
                || lower.contains("tls")) {
            return "NETWORK";
        }
        if (lower.contains("parse") || lower.contains("malformed") || lower.contains("expected") || lower.contains("unexpected")) {
            return "PARSE";
        }
        if (lower.contains("http 5") || lower.contains("status 5")) {
            return "SERVER_5XX";
        }
        if (lower.contains("http 4") || lower.contains("status 4")) {
            return "CLIENT_4XX";
        }
        return "OTHER";
    }

    public Long getId() {
        return id;
    }

    public ScrapeRun getScrapeRun() {
        return scrapeRun;
    }

    public void setScrapeRun(ScrapeRun scrapeRun) {
        this.scrapeRun = scrapeRun;
    }

    public Integer getRunNumber() {
        return runNumber;
    }

    public void setRunNumber(Integer runNumber) {
        this.runNumber = runNumber;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(LocalDateTime occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = context;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getHtmlSnippet() {
        return htmlSnippet;
    }

    public void setHtmlSnippet(String htmlSnippet) {
        this.htmlSnippet = htmlSnippet;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getErrorClass() {
        return errorClass;
    }

    public void setErrorClass(String errorClass) {
        this.errorClass = errorClass;
    }
}
