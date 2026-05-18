package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.StreamWorkerHealthMinute;
import com.ttl.tabletennis.domain.StreamWorkerHealthMinuteId;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface StreamWorkerHealthMinuteRepository extends JpaRepository<StreamWorkerHealthMinute, StreamWorkerHealthMinuteId> {

    List<StreamWorkerHealthMinute> findByMatchIdOrderByMinuteBucketUtcDesc(String matchId, Pageable pageable);

    List<StreamWorkerHealthMinute> findByMinuteBucketUtcGreaterThanEqualOrderByMinuteBucketUtcDesc(LocalDateTime from, Pageable pageable);
}
