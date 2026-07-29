package com.ttl.tabletennis.cv;

import com.ttl.tabletennis.domain.StreamVlmCall;
import com.ttl.tabletennis.repository.StreamVlmCallRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class VlmCallRecorderTests {

    @Test
    void recordPopulatesRowFromResult() {
        StreamVlmCallRepository repo = mock(StreamVlmCallRepository.class);
        VlmCallRecorder recorder = new VlmCallRecorder(Optional.of(repo));
        VlmRequest request = new VlmRequest("img".getBytes(), "image/jpeg", "match-1", "frame-99", Duration.ofSeconds(1));
        VlmScoreReadingResult result = VlmScoreReadingResult.ok(
                new VlmScoreReading(1, 2, 3, 4, ServerSide.TOP, 0.92),
                Duration.ofMillis(310), 130, 18, 0.000234);

        recorder.record("worker-7", "match-1", "gemini-flash", request, result, Instant.parse("2026-05-17T10:00:00Z"));

        ArgumentCaptor<StreamVlmCall> captor = ArgumentCaptor.forClass(StreamVlmCall.class);
        verify(repo).save(captor.capture());
        StreamVlmCall row = captor.getValue();
        assertEquals("worker-7", row.getWorkerId());
        assertEquals("match-1", row.getMatchId());
        assertEquals("frame-99", row.getFrameId());
        assertEquals("gemini-flash", row.getModel());
        assertEquals("OK", row.getDecision());
        assertEquals(130, row.getTokensIn());
        assertEquals(18, row.getTokensOut());
        assertEquals(310L, row.getLatencyMs());
        assertTrue(row.isResponseValid());
        assertEquals(0.000234, row.getCostUsdEst().doubleValue(), 1e-9);
    }

    @Test
    void recordWritesErrorReasonForFailedCalls() {
        StreamVlmCallRepository repo = mock(StreamVlmCallRepository.class);
        VlmCallRecorder recorder = new VlmCallRecorder(Optional.of(repo));
        VlmScoreReadingResult result = VlmScoreReadingResult.error("gemini-status-429", Duration.ofMillis(40));

        recorder.record("w1", "m1", "gemini-flash",
                new VlmRequest("x".getBytes(), "image/jpeg", "m1", "f1", Duration.ofSeconds(1)),
                result, Instant.parse("2026-05-17T10:00:00Z"));

        ArgumentCaptor<StreamVlmCall> captor = ArgumentCaptor.forClass(StreamVlmCall.class);
        verify(repo).save(captor.capture());
        StreamVlmCall row = captor.getValue();
        assertEquals("ERROR", row.getDecision());
        assertEquals("gemini-status-429", row.getErrorReason());
        assertEquals(false, row.isResponseValid());
    }

    @Test
    void recordIsNoopWhenRepositoryMissing() {
        VlmCallRecorder recorder = new VlmCallRecorder(Optional.empty());
        recorder.record("w1", "m1", "gemini-flash",
                new VlmRequest("x".getBytes(), "image/jpeg", "m1", "f1", Duration.ofSeconds(1)),
                VlmScoreReadingResult.ok(new VlmScoreReading(0, 0, 0, 0, ServerSide.UNKNOWN, 0.5),
                        Duration.ofMillis(10), 0, 0, 0.0),
                Instant.parse("2026-05-17T10:00:00Z"));
    }

    @Test
    void recordSwallowsRepositoryExceptions() {
        StreamVlmCallRepository repo = mock(StreamVlmCallRepository.class);
        doThrow(new RuntimeException("db down")).when(repo).save(any(StreamVlmCall.class));
        VlmCallRecorder recorder = new VlmCallRecorder(Optional.of(repo));

        recorder.record("w1", "m1", "gemini-flash",
                new VlmRequest("x".getBytes(), "image/jpeg", "m1", "f1", Duration.ofSeconds(1)),
                VlmScoreReadingResult.ok(new VlmScoreReading(0, 0, 0, 0, ServerSide.UNKNOWN, 0.5),
                        Duration.ofMillis(10), 0, 0, 0.0),
                Instant.parse("2026-05-17T10:00:00Z"));
    }
}
