package com.ttl.tabletennis.cv;

import java.util.Optional;

public class ScoreStateMachine {

    private final int bestOf;
    private StreamScoreFrame lastAccepted;

    public ScoreStateMachine(int bestOf) {
        this.bestOf = bestOf <= 1 ? 5 : bestOf;
    }

    public Optional<StreamScoreFrame> lastAccepted() {
        return Optional.ofNullable(lastAccepted);
    }

    public ScoreFrameTransition ingest(StreamScoreFrame frame) {
        if (frame == null) {
            return new ScoreFrameTransition(ScoreFrameTransition.TransitionKind.REJECT, null, "FRAME_NULL");
        }
        if (!frame.score().plausibleForBestOf(bestOf)) {
            return new ScoreFrameTransition(ScoreFrameTransition.TransitionKind.REJECT, frame, "SCORE_OUT_OF_BOUNDS");
        }
        if (lastAccepted == null || frame.score().validNextAfter(lastAccepted.score(), bestOf)) {
            lastAccepted = frame;
            return new ScoreFrameTransition(ScoreFrameTransition.TransitionKind.ACCEPT, frame, "VALID_PROGRESS");
        }
        if (canReviseOverSingleMiss(lastAccepted.score(), frame.score())) {
            lastAccepted = frame;
            return new ScoreFrameTransition(ScoreFrameTransition.TransitionKind.REVISE, frame, "SINGLE_FRAME_MISS");
        }
        return new ScoreFrameTransition(ScoreFrameTransition.TransitionKind.REJECT, frame, "INVALID_PROGRESS");
    }

    private boolean canReviseOverSingleMiss(ScoreTuple previous, ScoreTuple current) {
        if (previous == null || current == null || !current.sameGameAs(previous)) {
            return false;
        }
        int topDelta = current.topPoints() - previous.topPoints();
        int botDelta = current.botPoints() - previous.botPoints();
        return (topDelta == 2 && botDelta == 0) || (topDelta == 0 && botDelta == 2);
    }
}
