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
 * 提供无人机节点的资源配置接口：
 * - 更新节点硬件配置（maxCpu, maxMemory, networkBandwidth）
 *
 * 用于动态调整节点的计算和通信能力
 */
@RestController
@RequestMapping("/api/nodes")
@CrossOrigin(origins = "*")
public class ConfigController {

    @Autowired
    private NodeService nodeService;

    @Data
    public static class NodeConfigRequest {
        private Double maxCpu;
        private Double maxMemory;
        private Double networkBandwidth;
    }

    /**
     * 更新节点硬件配置
     *
     * 请求体参数：
     * - maxCpu: 最大CPU（可选）
     * - maxMemory: 最大内存（可选）
     * - networkBandwidth: 网络带宽（可选）
     *
     * @param id 节点ID
     * @param configReq 配置请求体
     * @return 更新后的节点信息
     *         节点不存在返回404
     */
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