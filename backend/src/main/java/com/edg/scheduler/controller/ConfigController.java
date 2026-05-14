package com.edg.scheduler.controller;

import com.edg.scheduler.model.UAVNode;
import com.edg.scheduler.service.NodeService;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 节点配置控制器
 *
 * 提供无人机节点配置管理 API（需 ADMIN 权限）：
 * - 更新节点硬件配置（/api/nodes/{id}/config）
 *   - maxCpu: 最大 CPU 核心数
 *   - maxMemory: 最大内存（MB）
 *   - networkBandwidth: 网络带宽（Mbps）
 */
@RestController
@RequestMapping("/api/nodes")
@CrossOrigin(origins = "*") // Allow frontend requests
public class ConfigController {

    @Autowired
    private NodeService nodeService;

    @Data
    public static class NodeConfigRequest {
        private Double maxCpu;
        private Double maxMemory;
        private Double networkBandwidth;
    }

    @PutMapping("/{id}/config")
    public ResponseEntity<UAVNode> updateNodeConfig(
            @PathVariable String id,
            @RequestBody NodeConfigRequest configReq) {

        UAVNode updatedNode = nodeService.updateNodeConfig(
                id,
                configReq.getMaxCpu(),
                configReq.getMaxMemory(),
                configReq.getNetworkBandwidth());

        if (updatedNode != null) {
            return ResponseEntity.ok(updatedNode);
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}
