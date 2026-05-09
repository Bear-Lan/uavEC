package com.edg.scheduler.repository;

import com.edg.scheduler.model.OperationLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OperationLogRepository extends JpaRepository<OperationLog, Long> {
    Page<OperationLog> findByUsername(String username, Pageable pageable);
    Page<OperationLog> findByRole(String role, Pageable pageable);
    Page<OperationLog> findByCreateTimeBetween(LocalDateTime start, LocalDateTime end, Pageable pageable);
    List<OperationLog> findByUsernameOrderByCreateTimeDesc(String username);
}