package com.aiolos.octopus.gateway.config;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "whitelist")
public class GatewayWhitelistProperties {

    private Map<String, ServiceConfig> services = new HashMap<>();
    
    @Data
    public static class ServiceConfig {
        @Schema(description = "live项目不需要校验token的域名")
        private List<String> urls;

        @Schema(description = "允许匿名访问的url，会生成匿名userId")
        private List<String> anonymousUrls;
    }
}
