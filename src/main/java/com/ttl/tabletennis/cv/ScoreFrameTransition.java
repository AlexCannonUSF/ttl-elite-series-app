package com.ttl.tabletennis.cv;

public record ScoreFrameTransition(TransitionKind kind,
                                   StreamScoreFrame frame,
                                   String reason) {

    public ScoreFrameTransition {
        kind = kind == null ? TransitionKind.REJECT : kind;
        reason = reason == null ? "" : reason.trim();
    }

    public boolean acceptedForConsensus() {
        return kind == TransitionKind.ACCEPT || kind == TransitionKind.REVISE;
    }

    public enum TransitionKind {
        ACCEPT,
        REVISE,
        REJECT
    }
}
