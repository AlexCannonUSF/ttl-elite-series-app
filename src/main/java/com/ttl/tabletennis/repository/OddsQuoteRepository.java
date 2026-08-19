package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.OddsQuote;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OddsQuoteRepository extends JpaRepository<OddsQuote, Long> {

    List<OddsQuote> findBySourceOrderByScrapedAtDesc(String source, Pageable pageable);

    Slice<OddsQuote> findAllByOrderByScrapedAtAscIdAsc(Pageable pageable);

    @Modifying
    @Query("delete from OddsQuote o where o.scrapedAt < :cutoff")
    void deleteByScrapedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
