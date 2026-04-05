package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.PlayerAlias;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlayerAliasRepository extends JpaRepository<PlayerAlias, Long> {

    Optional<PlayerAlias> findByNormalizedAlias(String normalizedAlias);

    boolean existsByNormalizedAlias(String normalizedAlias);

    List<PlayerAlias> findByPlayerIdOrderByAliasNameAsc(Long playerId);

    List<PlayerAlias> findAllByOrderByAliasNameAsc();
}
