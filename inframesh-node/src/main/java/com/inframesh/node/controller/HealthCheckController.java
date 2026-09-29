package com.inframesh.node.controller;

import com.inframesh.node.dto.NodeHealthResponse;
import com.inframesh.node.service.NodeHealthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthCheckController {

    private final NodeHealthService nodeHealthService;

    public HealthCheckController(NodeHealthService nodeHealthService) {
        this.nodeHealthService = nodeHealthService;
    }

    @GetMapping("/health")
    public NodeHealthResponse health() {
        return nodeHealthService.getHealth();
    }
}
