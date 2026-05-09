package com.edg.scheduler.controller;

import com.edg.scheduler.model.UAVNode;
import com.edg.scheduler.service.NodeService;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
