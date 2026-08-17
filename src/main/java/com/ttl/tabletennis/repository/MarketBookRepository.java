package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.MarketBook;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface MarketBookRepository extends JpaRepository<MarketBook, Long> {
    Optional<MarketBook> findBySourceCodeIgnoreCase(String sourceCode);
    List<MarketBook> findByEnabledTrueOrderByDisplayNameAsc();
}
