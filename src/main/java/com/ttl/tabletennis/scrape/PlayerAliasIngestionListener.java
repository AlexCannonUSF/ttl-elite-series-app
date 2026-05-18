package com.ttl.tabletennis.scrape;

import com.ttl.tabletennis.model.MatchOdds;
import com.ttl.tabletennis.service.PlayerIdentityService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.context.event.EventListener;

import java.util.LinkedHashSet;
import java.util.Set;

@Component
public class PlayerAliasIngestionListener {

    private final PlayerIdentityService playerIdentityService;

    public PlayerAliasIngestionListener(PlayerIdentityService playerIdentityService) {
        this.playerIdentityService = playerIdentityService;
    }

    @Async("ttlIngestionBusExecutor")
    @EventListener
    public void onIngestEvent(IngestEvent<?> event) {
        if (event == null || event.payload() == null) {
            return;
        }

        Set<String> playerNames = extractPlayerNames(event.payload());
        for (String playerName : playerNames) {
            playerIdentityService.resolveOrCreatePlayer(playerName);
        }
    }

    Set<String> extractPlayerNames(Object payload) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (payload instanceof MatchOdds odds) {
            addIfPresent(names, odds.getPlayerA());
            addIfPresent(names, odds.getPlayerB());
            return names;
        }
        if (payload instanceof TtSeriesScraper.OfficialLedgerMatch ledgerMatch) {
            addIfPresent(names, ledgerMatch.player1Raw());
            addIfPresent(names, ledgerMatch.player2Raw());
            addIfPresent(names, ledgerMatch.winnerRaw());
        }
        return names;
    }

    private void addIfPresent(Set<String> names, String rawName) {
        if (!StringUtils.hasText(rawName)) {
            return;
        }
        names.add(rawName.trim());
    }
}
