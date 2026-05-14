package com.edg.scheduler.repository;

import com.edg.scheduler.model.BatchMetricsSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 批次性能指标汇总数据访问层
 *
 * 提供批次性能指标（BatchMetricsSummary）实体的数据库操作方法
 */
@Repository
public interface BatchMetricsSummaryRepository extends JpaRepository<BatchMetricsSummary, String> {
    /**
     * 获取最近 100 条性能指标记录（按创建时间倒序）
     * @return 性能指标列表
     */
    List<BatchMetricsSummary> findTop100ByOrderByCreatedAtDesc();

    /**
     * 获取所有性能指标记录（按创建时间升序）
     * @return 性能指标列表
     */
    List<BatchMetricsSummary> findAllByOrderByCreatedAtAsc();

    /**
     * 检查指定批次是否已存在性能指标记录
     * @param batchId 批次ID
     * @return 是否存在
     */
    boolean existsByBatchId(String batchId);
}
