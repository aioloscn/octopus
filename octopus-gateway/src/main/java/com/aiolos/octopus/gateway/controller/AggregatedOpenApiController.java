package com.aiolos.octopus.gateway.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
public class AggregatedOpenApiController {
    
    @Value("${spring.application.name}")
    private String serviceId;

    private final DiscoveryClient discoveryClient;

    public AggregatedOpenApiController(DiscoveryClient discoveryClient) {
        this.discoveryClient = discoveryClient;
    }

    @GetMapping("/v3/api-docs/swagger-config")
    public Map<String, Object> swaggerConfig() {
        List<Map<String, String>> groups = discoveryClient.getServices().stream()
                .filter(service -> service.startsWith("live-") && !service.equals(serviceId))  // 排除网关自身
                .filter(this::isServiceHealthy)  // 过滤健康状态
                .map(service -> {
                    Map<String, String> group = new HashMap<>();
                    group.put("name", service);
                    group.put("url", "/" + service + "/v3/api-docs");
                    return group;
                }).collect(Collectors.toList());

        Map<String, Object> config = new HashMap<>();
        config.put("urls", groups);
        return config;
    }

    private boolean isServiceHealthy(String serviceId) {
        // 调用注册中心健康检查接口或自行实现探活逻辑
        return true;
    }
}
