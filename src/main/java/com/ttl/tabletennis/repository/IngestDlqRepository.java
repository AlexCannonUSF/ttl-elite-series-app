package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.IngestDlqEntry;
import com.ttl.tabletennis.scrape.SourceId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestDlqRepository extends JpaRepository<IngestDlqEntry, Long> {

    long countBySourceId(SourceId sourceId);
}
