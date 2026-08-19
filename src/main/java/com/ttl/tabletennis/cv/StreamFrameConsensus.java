package com.ttl.tabletennis.cv;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

public class StreamFrameConsensus {

    static final double MIN_CONSENSUS_CONFIDENCE = 0.85;
    private static final int WINDOW_SIZE = 3;

    private final Deque<StreamScoreFrame> window = new ArrayDeque<>();
    private ScoreTuple lastEmitted;

    public Optional<StreamFrameObservationPayload> ingest(ScoreFrameTransition transition) {
        if (transition == null || !transition.acceptedForConsensus() || transition.frame() == null) {
            return Optional.empty();
        }
        StreamScoreFrame frame = transition.frame();
        window.addLast(frame);
        while (window.size() > WINDOW_SIZE) {
            window.removeFirst();
        }
        if (window.size() < WINDOW_SIZE) {
            return Optional.empty();
        }
        ScoreTuple tuple = frame.score();
        boolean allAgree = window.stream().allMatch(candidate -> candidate.score().equals(tuple));
        boolean confidenceOk = window.stream().mapToDouble(StreamScoreFrame::confidence).min().orElse(0.0) >= MIN_CONSENSUS_CONFIDENCE;
        if (!allAgree || !confidenceOk || tuple.equals(lastEmitted)) {
            return Optional.empty();
        }
        lastEmitted = tuple;
        return Optional.of(StreamFrameObservationPayload.from(frame));
    }
}
