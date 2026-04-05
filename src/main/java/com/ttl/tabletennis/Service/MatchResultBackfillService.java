package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.util.MatchResultParser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class MatchResultBackfillService {

    private final MatchRepository matchRepository;

    public MatchResultBackfillService(MatchRepository matchRepository) {
        this.matchRepository = matchRepository;
    }

    @Transactional
    public int backfillStructuredResults() {
        List<Match> matches = matchRepository.findAll();
        int updated = 0;

        for (Match match : matches) {
            boolean needsUpdate = match.getPlayer1SetsWon() == null ||
                    match.getPlayer2SetsWon() == null ||
                    (match.getWinnerPlayerId() == null && match.getResult() != null && !match.getResult().isBlank());
            if (!needsUpdate) {
                continue;
            }

            MatchResultParser.applyToMatch(match, match.getResult());
            updated++;
        }

        return updated;
    }
}
