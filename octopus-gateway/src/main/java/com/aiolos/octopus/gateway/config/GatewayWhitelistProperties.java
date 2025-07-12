package com.aiolos.octopus.gateway.config;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "whitelist")
public class GatewayWhitelistProperties {

    private List<ServiceConfig> services;
    
    @Data
    public static class ServiceConfig {
        private String id;
        @Schema(description = "live项目不需要校验token的域名")
        private List<String> urls = new ArrayList<>();
        @Schema(description = "允许匿名访问的url，会生成匿名userId")
        private List<String> anonymousUrls = new ArrayList<>();
    }

    public ServiceConfig findServiceConfig(String serviceId) {
        return services.stream().filter(service -> service.getId().equals(serviceId)).findFirst().orElse(null);
    }
}
