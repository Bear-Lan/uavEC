package com.edg.scheduler.repository;

import com.edg.scheduler.model.BatchMetricsSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 批次性能指标汇总数据访问层
 *
 * 继承JpaRepository，提供批次指标汇总（BatchMetricsSummary）实体的数据库操作：
 * - findTop100ByOrderByCreatedAtDesc: 获取最近100条批次指标（用于历史展示）
 * - findAllByOrderByCreatedAtAsc: 按时间升序获取全部批次指标（用于导出）
 * - existsByBatchId: 判断批次是否已存在（防止重复统计）
 */
@Repository
public interface BatchMetricsSummaryRepository extends JpaRepository<BatchMetricsSummary, String> {
    /**
     * 获取最近100条批次指标（按创建时间降序）
     *
     * @return 批次指标列表
     */
    List<BatchMetricsSummary> findTop100ByOrderByCreatedAtDesc();

    /**
     * 按时间升序获取全部批次指标
     *
     * @return 批次指标列表
     */
    List<BatchMetricsSummary> findAllByOrderByCreatedAtAsc();

    /**
     * 判断批次是否已存在
     *
     * @param batchId 批次ID
     * @return 是否存在
     */
    boolean existsByBatchId(String batchId);
}