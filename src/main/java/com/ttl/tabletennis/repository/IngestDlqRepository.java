package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.IngestDlqEntry;
import com.ttl.tabletennis.scrape.SourceId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface IngestDlqRepository extends JpaRepository<IngestDlqEntry, Long> {

    interface SourceDepth {
        SourceId getSourceId();

        long getDepth();
    }

    long countBySourceId(SourceId sourceId);

    @Query("select d.sourceId as sourceId, count(d.id) as depth from IngestDlqEntry d group by d.sourceId")
    List<SourceDepth> summarizeDepthBySource();
}
