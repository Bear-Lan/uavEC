package com.edg.scheduler.repository;

import com.edg.scheduler.model.BatchMetricsSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BatchMetricsSummaryRepository extends JpaRepository<BatchMetricsSummary, String> {
    List<BatchMetricsSummary> findTop100ByOrderByCreatedAtDesc();

    List<BatchMetricsSummary> findAllByOrderByCreatedAtAsc();
    
    boolean existsByBatchId(String batchId);
}
