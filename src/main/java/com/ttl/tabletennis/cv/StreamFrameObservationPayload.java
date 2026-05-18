package com.ttl.tabletennis.cv;

public record StreamFrameObservationPayload(int topGames,
                                            int botGames,
                                            int topPoints,
                                            int botPoints,
                                            String server,
                                            String phase,
                                            double confidence,
                                            String templateId,
                                            String reader,
                                            String frameId) {

    public static StreamFrameObservationPayload from(StreamScoreFrame frame) {
        ScoreTuple score = frame.score();
        return new StreamFrameObservationPayload(
                score.topGames(),
                score.botGames(),
                score.topPoints(),
                score.botPoints(),
                score.server().name(),
                score.phase().name(),
                frame.confidence(),
                frame.templateId(),
                frame.reader(),
                frame.frameId()
        );
    }
}
