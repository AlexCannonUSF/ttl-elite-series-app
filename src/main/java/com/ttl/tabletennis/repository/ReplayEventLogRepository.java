package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.ReplayEventLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReplayEventLogRepository extends JpaRepository<ReplayEventLog, Long> {
    List<ReplayEventLog> findByReplayIdOrderBySequenceNumberAsc(Long replayId);
    long countByReplayId(Long replayId);
}
