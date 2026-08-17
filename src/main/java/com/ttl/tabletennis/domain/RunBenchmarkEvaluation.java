package com.ttl.tabletennis.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** A synchronized non-model benchmark on the same opportunity. */
@Entity
@Table(name = "run_benchmark_evaluation", uniqueConstraints = @UniqueConstraint(name = "uk_benchmark_opportunity_key", columnNames = {"opportunity_id", "benchmark_key"}))
public class RunBenchmarkEvaluation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "opportunity_id", nullable = false) private Long opportunityId;
    @Column(name = "benchmark_key", nullable = false, length = 64) private String benchmarkKey;
    @Column(name = "selected_player_id") private Long selectedPlayerId;
    @Column(name = "selected_player_name", length = 180) private String selectedPlayerName;
    @Column(name = "probability") private Double probability;
    @Column(name = "american_odds") private Integer americanOdds;
    @Column(name = "source_code", length = 64) private String sourceCode;
    @Column(name = "captured_at", nullable = false) private LocalDateTime capturedAt;
    @PrePersist void prePersist() { if (capturedAt == null) capturedAt = LocalDateTime.now(); }
    public Long getId() { return id; }
    public Long getOpportunityId() { return opportunityId; } public void setOpportunityId(Long value) { opportunityId = value; }
    public String getBenchmarkKey() { return benchmarkKey; } public void setBenchmarkKey(String value) { benchmarkKey = value; }
    public Long getSelectedPlayerId() { return selectedPlayerId; } public void setSelectedPlayerId(Long value) { selectedPlayerId = value; }
    public String getSelectedPlayerName() { return selectedPlayerName; } public void setSelectedPlayerName(String value) { selectedPlayerName = value; }
    public Double getProbability() { return probability; } public void setProbability(Double value) { probability = value; }
    public Integer getAmericanOdds() { return americanOdds; } public void setAmericanOdds(Integer value) { americanOdds = value; }
    public String getSourceCode() { return sourceCode; } public void setSourceCode(String value) { sourceCode = value; }
    public LocalDateTime getCapturedAt() { return capturedAt; } public void setCapturedAt(LocalDateTime value) { capturedAt = value; }
}
