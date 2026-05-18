package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.StreamRouteRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StreamRouteRecordRepository extends JpaRepository<StreamRouteRecord, Long> {

    Optional<StreamRouteRecord> findByEventCodeAndTableNumber(String eventCode, String tableNumber);

    long countByPlatform(String platform);
}
