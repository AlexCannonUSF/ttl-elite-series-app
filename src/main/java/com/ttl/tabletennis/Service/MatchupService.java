package com.ttl.tabletennis.Service;

import com.ttl.tabletennis.Entity.Match;
import com.ttl.tabletennis.Entity.Player;
import com.ttl.tabletennis.Repository.MatchRepository;
import com.ttl.tabletennis.Repository.PlayerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class MatchupService {

    @Autowired
    private PlayerRepository playerRepository;

    @Autowired
    private MatchRepository matchRepository;

    @Transactional
    public String addMatchup(String lastName1, String lastName2){
        if(lastName1.equalsIgnoreCase("exit") || lastName2.equalsIgnoreCase("exit")){
            return "Matchup addition cancelled.";
        }

        List<Player> player1List = playerRepository.findByLastNameIgnoreCase(lastName1.trim());
        if(player1List.isEmpty()){
            return "No players found with last name: " + lastName1;
        }
        Player player1 = player1List.get(0);

        List<Player> player2List = playerRepository.findByLastNameIgnoreCase(lastName2.trim());
        if(player2List.isEmpty()){
            return "No players found with last name: " + lastName2;
        }
        Player player2 = player2List.get(0);

        Optional<Match> existingMatch = matchRepository.findByPlayersAndDate(
                player1.getId(),
                player2.getId(),
                LocalDate.now()
        );

        if(existingMatch.isPresent()){
            return "A match between " + player1.getName() + " and " + player2.getName() +
                    " on " + LocalDate.now() + " already exists.";
        }

        Match match = new Match();
        match.setPlayer1(player1);
        match.setPlayer2(player2);
        match.setDate(LocalDate.now());
        match.setResult("");

        matchRepository.save(match);
        return "Matchup added successfully between " +
                player1.getName() + " and " + player2.getName();
    }

    @Transactional(readOnly=true)
    public String viewAllMatchups(){
        List<Match> matchups = matchRepository.findAll();
        if(matchups.isEmpty()){
            return "No matchups have been added yet.";
        }

        StringBuilder sb = new StringBuilder();
        for(Match m : matchups){
            sb.append(m.toString()).append("\n");
        }
        return sb.toString();
    }

    @Transactional
    public String removeMatchup(Long matchupId){
        Optional<Match> matchupOpt = matchRepository.findById(matchupId);
        if(matchupOpt.isEmpty()){
            return "No matchup found with ID: " + matchupId;
        }
        matchRepository.deleteById(matchupId);
        return "Matchup with ID " + matchupId + " has been removed.";
    }

    @Transactional
    public String clearAllMatchups(){
        matchRepository.deleteAll();
        return "All matchups have been cleared.";
    }
}
