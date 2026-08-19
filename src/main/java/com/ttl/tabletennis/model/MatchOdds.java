package com.ttl.tabletennis.model;

public class MatchOdds {
    private String playerA;
    private String playerB;
    private double oddsA;
    private double oddsB;
    private String eventName;
    private String competitionName;
    private boolean live;
    private String startTimeIso;
    private String source;
    private String liveScore;
    private String matchPhase;
    private long timestamp;
    private String externalEventId;
    private String sourceType;
    private double sourceConfidence;
    private boolean displayed = true;
    private boolean resulted;
    private boolean matchCompleted;
    private String sourceFeedCode;
    private String sourceFeedEventId;
    private String scoreDetail;

    public MatchOdds() {}

    public MatchOdds(String playerA, String playerB, double oddsA, double oddsB) {
        this(playerA, playerB, oddsA, oddsB, null, null, false, null, "HARD_ROCK_HTML");
    }

    public MatchOdds(String playerA,
                     String playerB,
                     double oddsA,
                     double oddsB,
                     String eventName,
                     String competitionName,
                     boolean live,
                     String startTimeIso,
                     String source) {
        this(playerA, playerB, oddsA, oddsB, eventName, competitionName, live, startTimeIso, source, null, null);
    }

    public MatchOdds(String playerA,
                     String playerB,
                     double oddsA,
                     double oddsB,
                     String eventName,
                     String competitionName,
                     boolean live,
                     String startTimeIso,
                     String source,
                     String liveScore,
                     String matchPhase) {
        this.playerA = playerA;
        this.playerB = playerB;
        this.oddsA = oddsA;
        this.oddsB = oddsB;
        this.eventName = eventName;
        this.competitionName = competitionName;
        this.live = live;
        this.startTimeIso = startTimeIso;
        this.source = source;
        this.liveScore = liveScore;
        this.matchPhase = matchPhase;
        this.timestamp = System.currentTimeMillis();
    }

    public String getPlayerA() { return playerA; }
    public String getPlayerB() { return playerB; }
    public double getOddsA() { return oddsA; }
    public double getOddsB() { return oddsB; }
    public String getEventName() { return eventName; }
    public String getCompetitionName() { return competitionName; }
    public boolean isLive() { return live; }
    public String getStartTimeIso() { return startTimeIso; }
    public String getSource() { return source; }
    public String getLiveScore() { return liveScore; }
    public String getMatchPhase() { return matchPhase; }
    public long getTimestamp() { return timestamp; }
    public String getExternalEventId() { return externalEventId; }
    public String getSourceType() { return sourceType; }
    public double getSourceConfidence() { return sourceConfidence; }
    public boolean isDisplayed() { return displayed; }
    public boolean isResulted() { return resulted; }
    public boolean isMatchCompleted() { return matchCompleted; }
    public String getSourceFeedCode() { return sourceFeedCode; }
    public String getSourceFeedEventId() { return sourceFeedEventId; }
    public String getScoreDetail() { return scoreDetail; }

    public void setLiveScore(String liveScore) { this.liveScore = liveScore; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    public void setMatchPhase(String matchPhase) { this.matchPhase = matchPhase; }
    public void setExternalEventId(String externalEventId) { this.externalEventId = externalEventId; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public void setSourceConfidence(double sourceConfidence) { this.sourceConfidence = sourceConfidence; }
    public void setDisplayed(boolean displayed) { this.displayed = displayed; }
    public void setResulted(boolean resulted) { this.resulted = resulted; }
    public void setMatchCompleted(boolean matchCompleted) { this.matchCompleted = matchCompleted; }
    public void setSourceFeedCode(String sourceFeedCode) { this.sourceFeedCode = sourceFeedCode; }
    public void setSourceFeedEventId(String sourceFeedEventId) { this.sourceFeedEventId = sourceFeedEventId; }
    public void setScoreDetail(String scoreDetail) { this.scoreDetail = scoreDetail; }
}
