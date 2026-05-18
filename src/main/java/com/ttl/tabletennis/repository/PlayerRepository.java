package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.Player;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlayerRepository extends JpaRepository<Player, Long> {

    List<Player> findByLastNameIgnoreCase(String lastName);

    Optional<Player> findByFirstNameIgnoreCaseAndLastNameIgnoreCase(String firstName, String lastName);

    Optional<Player> findByNormalizedName(String normalizedName);

    @Query("""
           SELECT p FROM Player p
           WHERE LOWER(p.firstName) LIKE LOWER(CONCAT('%', :search, '%'))
              OR LOWER(p.lastName) LIKE LOWER(CONCAT('%', :search, '%'))
           ORDER BY p.lastName ASC, p.firstName ASC
           """)
    List<Player> searchPlayers(String search);

    @Query("SELECT p FROM Player p ORDER BY p.lastName ASC, p.firstName ASC")
    List<Player> findAllByOrderByLastNameAscFirstNameAsc();
}
