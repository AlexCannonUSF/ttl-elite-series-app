package com.ttl.tabletennis.domain;

import com.ttl.tabletennis.util.CorrelationContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "odds_quote", indexes = {
        @Index(name = "idx_odds_quote_source_scraped", columnList = "source, scraped_at"),
        @Index(name = "idx_odds_quote_players", columnList = "player1_normalized, player2_normalized")
})
public class OddsQuote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source", nullable = false, length = 64)
    private String source;

    @Column(name = "player1_display", nullable = false, length = 180)
    private String player1Display;

    @Column(name = "player2_display", nullable = false, length = 180)
    private String player2Display;

    @Column(name = "player1_normalized", nullable = false, length = 180)
    private String player1Normalized;

    @Column(name = "player2_normalized", nullable = false, length = 180)
    private String player2Normalized;

    @Column(name = "event_name", length = 240)
    private String eventName;

    @Column(name = "competition_name", length = 180)
    private String competitionName;

    @Column(name = "live_at_quote")
    private Boolean liveAtQuote;

    @Column(name = "start_time_iso", length = 80)
    private String startTimeIso;

    @Column(name = "live_score", length = 80)
    private String liveScore;

    @Column(name = "match_phase", length = 80)
    private String matchPhase;

    @Column(name = "quote_timestamp_ms")
    private long quoteTimestampMs;

    @Column(name = "american_odds_player1", nullable = false)
    private int americanOddsPlayer1;

    @Column(name = "american_odds_player2", nullable = false)
    private int americanOddsPlayer2;

    @Column(name = "decimal_odds_player1", nullable = false)
    private double decimalOddsPlayer1;

    @Column(name = "decimal_odds_player2", nullable = false)
    private double decimalOddsPlayer2;

    @Column(name = "scraped_at", nullable = false)
    private LocalDateTime scrapedAt;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @PrePersist
    void prePersist() {
        if (scrapedAt == null) {
            scrapedAt = LocalDateTime.now();
        }
        if (source == null || source.isBlank()) {
            source = "UNKNOWN";
        }
        if (liveAtQuote == null) {
            liveAtQuote = Boolean.FALSE;
        }
        if (quoteTimestampMs <= 0L) {
            quoteTimestampMs = System.currentTimeMillis();
        }
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = CorrelationContext.currentOrCreate();
        }
    }

    public Long getId() {
        return id;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getPlayer1Display() {
        return player1Display;
    }

    public void setPlayer1Display(String player1Display) {
        this.player1Display = player1Display;
    }

    public String getPlayer2Display() {
        return player2Display;
    }

    public void setPlayer2Display(String player2Display) {
        this.player2Display = player2Display;
    }

    public String getPlayer1Normalized() {
        return player1Normalized;
    }

    public void setPlayer1Normalized(String player1Normalized) {
        this.player1Normalized = player1Normalized;
    }

    public String getPlayer2Normalized() {
        return player2Normalized;
    }

    public void setPlayer2Normalized(String player2Normalized) {
        this.player2Normalized = player2Normalized;
    }

    public String getEventName() {
        return eventName;
    }

    public void setEventName(String eventName) {
        this.eventName = eventName;
    }

    public String getCompetitionName() {
        return competitionName;
    }

    public void setCompetitionName(String competitionName) {
        this.competitionName = competitionName;
    }

    public boolean isLiveAtQuote() {
        return Boolean.TRUE.equals(liveAtQuote);
    }

    public void setLiveAtQuote(Boolean liveAtQuote) {
        this.liveAtQuote = liveAtQuote;
    }

    public String getStartTimeIso() {
        return startTimeIso;
    }

    public void setStartTimeIso(String startTimeIso) {
        this.startTimeIso = startTimeIso;
    }

    public String getLiveScore() {
        return liveScore;
    }

    public void setLiveScore(String liveScore) {
        this.liveScore = liveScore;
    }

    public String getMatchPhase() {
        return matchPhase;
    }

    public void setMatchPhase(String matchPhase) {
        this.matchPhase = matchPhase;
    }

    public long getQuoteTimestampMs() {
        return quoteTimestampMs;
    }

    public void setQuoteTimestampMs(long quoteTimestampMs) {
        this.quoteTimestampMs = quoteTimestampMs;
    }

    public int getAmericanOddsPlayer1() {
        return americanOddsPlayer1;
    }

    public void setAmericanOddsPlayer1(int americanOddsPlayer1) {
        this.americanOddsPlayer1 = americanOddsPlayer1;
    }

    public int getAmericanOddsPlayer2() {
        return americanOddsPlayer2;
    }

    public void setAmericanOddsPlayer2(int americanOddsPlayer2) {
        this.americanOddsPlayer2 = americanOddsPlayer2;
    }

    public double getDecimalOddsPlayer1() {
        return decimalOddsPlayer1;
    }

    public void setDecimalOddsPlayer1(double decimalOddsPlayer1) {
        this.decimalOddsPlayer1 = decimalOddsPlayer1;
    }

    public double getDecimalOddsPlayer2() {
        return decimalOddsPlayer2;
    }

    public void setDecimalOddsPlayer2(double decimalOddsPlayer2) {
        this.decimalOddsPlayer2 = decimalOddsPlayer2;
    }

    public LocalDateTime getScrapedAt() {
        return scrapedAt;
    }

    public void setScrapedAt(LocalDateTime scrapedAt) {
        this.scrapedAt = scrapedAt;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }
}
