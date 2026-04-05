package com.ttl.tabletennis.util;

import com.ttl.tabletennis.domain.Match;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MatchResultParser {

    private static final Pattern SCORE_PATTERN = Pattern.compile("(\\d+)\\s*[:/-]\\s*(\\d+)");
    private static final Pattern COMPACT_SET_SCORE_PATTERN = Pattern.compile("^\\s*([0-7])\\s*([0-7])\\s*$");
    private static final Pattern WALKOVER_PATTERN = Pattern.compile("(?i)\\b(walk\\s*over|walkover|w\\s*/\\s*o|wo)\\b");

    public enum WinnerSide {
        PLAYER1,
        PLAYER2,
        NONE
    }

    public record ParsedResult(Integer player1SetsWon,
                               Integer player2SetsWon,
                               boolean complete,
                               WinnerSide winnerSide) {
    }

    private MatchResultParser() {
    }

    public static ParsedResult parse(String rawResult) {
        if (rawResult == null || rawResult.isBlank()) {
            return new ParsedResult(null, null, false, WinnerSide.NONE);
        }

        String normalized = normalize(rawResult);
        if (WALKOVER_PATTERN.matcher(normalized).find()) {
            return new ParsedResult(null, null, false, WinnerSide.NONE);
        }

        List<int[]> pairs = extractScorePairs(normalized);
        if (pairs.isEmpty()) {
            int[] compact = extractCompactSetScore(normalized);
            if (compact != null) {
                return fromSetScore(compact[0], compact[1]);
            }
            return new ParsedResult(null, null, false, WinnerSide.NONE);
        }

        int[] first = pairs.get(0);
        int maxFirst = Math.max(first[0], first[1]);
        if (maxFirst >= 3 && maxFirst <= 7) {
            return fromSetScore(first[0], first[1]);
        }

        ParsedResult fromSetPoints = fromSetPointSequence(pairs);
        if (fromSetPoints.complete()) {
            return fromSetPoints;
        }

        Matcher matcher = SCORE_PATTERN.matcher(normalized);
        if (!matcher.find()) {
            return new ParsedResult(null, null, false, WinnerSide.NONE);
        }

        int left = Integer.parseInt(matcher.group(1));
        int right = Integer.parseInt(matcher.group(2));
        return fromSetScore(left, right);
    }

    private static ParsedResult fromSetScore(int left, int right) {
        WinnerSide winnerSide = WinnerSide.NONE;
        if (left > right) winnerSide = WinnerSide.PLAYER1;
        if (right > left) winnerSide = WinnerSide.PLAYER2;

        boolean complete = (left >= 3 || right >= 3) && left != right;
        return new ParsedResult(left, right, complete, complete ? winnerSide : WinnerSide.NONE);
    }

    private static ParsedResult fromSetPointSequence(List<int[]> pairs) {
        int leftSets = 0;
        int rightSets = 0;
        for (int[] pair : pairs) {
            int left = pair[0];
            int right = pair[1];
            if (left == right) {
                continue;
            }
            if (left > right) {
                leftSets++;
            } else {
                rightSets++;
            }
        }
        boolean complete = (leftSets >= 3 || rightSets >= 3) && leftSets != rightSets;
        WinnerSide winnerSide = WinnerSide.NONE;
        if (complete) {
            winnerSide = leftSets > rightSets ? WinnerSide.PLAYER1 : WinnerSide.PLAYER2;
        }
        return new ParsedResult(leftSets, rightSets, complete, winnerSide);
    }

    private static List<int[]> extractScorePairs(String normalized) {
        Matcher matcher = SCORE_PATTERN.matcher(normalized);
        List<int[]> out = new ArrayList<>();
        while (matcher.find()) {
            int left = Integer.parseInt(matcher.group(1));
            int right = Integer.parseInt(matcher.group(2));
            out.add(new int[]{left, right});
        }
        return out;
    }

    private static int[] extractCompactSetScore(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        Matcher compact = COMPACT_SET_SCORE_PATTERN.matcher(normalized);
        if (!compact.matches()) {
            return null;
        }
        int left = Integer.parseInt(compact.group(1));
        int right = Integer.parseInt(compact.group(2));
        return new int[]{left, right};
    }

    private static String normalize(String raw) {
        return raw.trim()
                .replace('\u2013', '-') // en dash
                .replace('\u2014', '-') // em dash
                .replace('\u2212', '-'); // unicode minus
    }

    public static void applyToMatch(Match match, String rawResult) {
        String normalized = rawResult == null ? null : normalize(rawResult);
        if (normalized != null && !isAcceptedResultFormat(normalized)) {
            normalized = null;
        }
        match.setResult(normalized);

        ParsedResult parsed = parse(normalized);
        match.setPlayer1SetsWon(parsed.player1SetsWon());
        match.setPlayer2SetsWon(parsed.player2SetsWon());
        match.setComplete(parsed.complete());

        Long winnerPlayerId = null;
        if (parsed.winnerSide() == WinnerSide.PLAYER1 && match.getPlayer1() != null) {
            winnerPlayerId = match.getPlayer1().getId();
        } else if (parsed.winnerSide() == WinnerSide.PLAYER2 && match.getPlayer2() != null) {
            winnerPlayerId = match.getPlayer2().getId();
        }
        match.setWinnerPlayerId(winnerPlayerId);
    }

    public static boolean isAcceptedResultFormat(String rawResult) {
        if (rawResult == null || rawResult.isBlank()) {
            return true;
        }
        String normalized = normalize(rawResult);
        if (WALKOVER_PATTERN.matcher(normalized).find()) {
            return true;
        }
        ParsedResult parsed = parse(normalized);
        return parsed.player1SetsWon() != null || parsed.player2SetsWon() != null;
    }
}
