package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.FeedHealthSample;
import com.ttl.tabletennis.scrape.SourceId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FeedHealthSampleRepository extends JpaRepository<FeedHealthSample, Long> {

    Optional<FeedHealthSample> findTopBySourceIdOrderByObservedAtDesc(SourceId sourceId);
}
