package com.ttl.tabletennis.Repository;

import com.ttl.tabletennis.Entity.Player;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlayerRepository extends JpaRepository<Player, Long> {

    List<Player> findByLastNameIgnoreCase(String lastName);

    Optional<Player> findByFirstNameIgnoreCaseAndLastNameIgnoreCase(String firstName, String lastName);

    // For partial name search
    @Query("SELECT p FROM Player p WHERE " +
            "LOWER(p.firstName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
            "LOWER(p.lastName) LIKE LOWER(CONCAT('%', :search, '%')) " +
            "ORDER BY p.lastName ASC, p.firstName ASC")
    List<Player> searchPlayers(String search);

    // NEW: Get all players in alphabetical order by lastName, then firstName
    @Query("SELECT p FROM Player p ORDER BY p.lastName ASC, p.firstName ASC")
    List<Player> findAllByOrderByLastNameAscFirstNameAsc();
}
