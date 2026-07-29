package com.ttl.tabletennis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "stream_vlm_call", indexes = {
        @Index(name = "idx_stream_vlm_call_called_at", columnList = "called_at_utc"),
        @Index(name = "idx_stream_vlm_call_model_called", columnList = "model, called_at_utc"),
        @Index(name = "idx_stream_vlm_call_worker_called", columnList = "worker_id, called_at_utc")
})
public class StreamVlmCall {

    @Id
    @Column(name = "call_id", nullable = false, length = 36)
    private String callId;

    @Column(name = "match_id", nullable = false, length = 120)
    private String matchId;

    @Column(name = "worker_id", nullable = false, length = 120)
    private String workerId;

    @Column(name = "frame_id", length = 160)
    private String frameId;

    @Column(name = "model", nullable = false, length = 48)
    private String model;

    @Column(name = "decision", nullable = false, length = 32)
    private String decision;

    @Column(name = "tokens_in")
    private Integer tokensIn;

    @Column(name = "tokens_out")
    private Integer tokensOut;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "cost_usd_est", precision = 10, scale = 6)
    private BigDecimal costUsdEst;

    @Column(name = "response_valid", nullable = false)
    private boolean responseValid;

    @Column(name = "error_reason", length = 255)
    private String errorReason;

    @Column(name = "called_at_utc", nullable = false)
    private LocalDateTime calledAtUtc;

    public String getCallId() { return callId; }
    public void setCallId(String callId) { this.callId = callId; }

    public String getMatchId() { return matchId; }
    public void setMatchId(String matchId) { this.matchId = matchId; }

    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }

    public String getFrameId() { return frameId; }
    public void setFrameId(String frameId) { this.frameId = frameId; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }

    public Integer getTokensIn() { return tokensIn; }
    public void setTokensIn(Integer tokensIn) { this.tokensIn = tokensIn; }

    public Integer getTokensOut() { return tokensOut; }
    public void setTokensOut(Integer tokensOut) { this.tokensOut = tokensOut; }

    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }

    public BigDecimal getCostUsdEst() { return costUsdEst; }
    public void setCostUsdEst(BigDecimal costUsdEst) { this.costUsdEst = costUsdEst; }

    public boolean isResponseValid() { return responseValid; }
    public void setResponseValid(boolean responseValid) { this.responseValid = responseValid; }

    public String getErrorReason() { return errorReason; }
    public void setErrorReason(String errorReason) { this.errorReason = errorReason; }

    public LocalDateTime getCalledAtUtc() { return calledAtUtc; }
    public void setCalledAtUtc(LocalDateTime calledAtUtc) { this.calledAtUtc = calledAtUtc; }
}
