package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.MirrorObservation;
import com.ttl.tabletennis.scrape.SourceId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MirrorObservationRepository extends JpaRepository<MirrorObservation, Long> {

    long countBySourceId(SourceId sourceId);
}
