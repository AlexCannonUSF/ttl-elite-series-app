package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.PlayerAlias;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PlayerAliasRepository extends JpaRepository<PlayerAlias, Long> {

    Optional<PlayerAlias> findByNormalizedAlias(String normalizedAlias);

    boolean existsByNormalizedAlias(String normalizedAlias);

    List<PlayerAlias> findByPlayerIdOrderByAliasNameAsc(Long playerId);

    List<PlayerAlias> findAllByOrderByAliasNameAsc();

    @EntityGraph(attributePaths = "player")
    @Query("SELECT alias FROM PlayerAlias alias ORDER BY alias.aliasName ASC")
    List<PlayerAlias> findAllWithPlayerOrderByAliasNameAsc();
}
