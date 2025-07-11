package com.aiolos.octopus.gateway.config;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "rate-limit")
public class GatewayRateLimitProperties {
    
    private List<ServiceConfig> services;
    
    @Data
    public static class ServiceConfig {
        private String id;
        private DefaultConfig defaultConfig;
        private List<InterfaceConfig> interfaces;
    }

    @Data
    public static class DefaultConfig {
        @Schema(description = "允许最大请求数")
        private int maxRequests = 100;
        @Schema(description = "时间窗口")
        private int timeWindow = 10;
        @Schema(description = "触发限流后的封禁时间")
        private int banTime = 60;
    }

    @Data
    public static class InterfaceConfig {
        private String path;
        private Integer maxRequests;
        private Integer timeWindow;
        private Integer banTime;
    }

    // 查找服务配置的辅助方法
    public ServiceConfig findServiceConfig(String serviceId) {
        return services.stream()
                .filter(service -> service.getId().equals(serviceId))
                .findFirst()
                .orElse(null);
    }

    // 查找接口配置的辅助方法
    public InterfaceConfig findInterfaceConfig(String serviceId, String requestPath) {
        ServiceConfig serviceConfig = findServiceConfig(serviceId);
        if (serviceConfig == null || serviceConfig.getInterfaces() == null) {
            return null;
        }

        return serviceConfig.getInterfaces().stream()
                .filter(o -> requestPath.contains(o.getPath()))
                .findFirst()
                .orElse(null);
    }
}
