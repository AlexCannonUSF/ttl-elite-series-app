package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.ModelCallViewerReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ModelCallViewerReviewRepository extends JpaRepository<ModelCallViewerReview, Long> {

    List<ModelCallViewerReview> findBySessionIdOrderByCreatedAtDesc(Long sessionId);

    List<ModelCallViewerReview> findByCallIdOrderByCreatedAtDesc(Long callId);
}
