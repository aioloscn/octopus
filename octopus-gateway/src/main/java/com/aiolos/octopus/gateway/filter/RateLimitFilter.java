package com.aiolos.octopus.gateway.filter;

import com.aiolos.common.enums.errors.ErrorEnum;
import com.aiolos.common.model.response.CommonResponse;
import com.aiolos.octopus.gateway.config.GatewayRateLimitProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

@Slf4j
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {
    
    @Resource
    private GatewayRateLimitProperties rateLimitProperties;
    @Resource
    private ReactiveRedisTemplate<String, Object> reactiveRedisTemplate;
    private final RedisScript<Long> rateLimitScript = createRateLimitScript();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        if (route == null) {
            return chain.filter(exchange);
        }

        String serviceId = route.getUri().getHost();
        String requestPath = exchange.getRequest().getPath().value();

        // 1. 获取服务级默认配置
        GatewayRateLimitProperties.ServiceConfig serviceConfig = rateLimitProperties.findServiceConfig(serviceId);

        if (serviceConfig == null) {
            return chain.filter(exchange);
        }

        // 2. 查找接口级配置
        GatewayRateLimitProperties.InterfaceConfig interfaceConfig = rateLimitProperties.findInterfaceConfig(serviceId, requestPath);

        // 3. 接口下的配置优先级高于默认的
        int maxRequests = interfaceConfig != null && interfaceConfig.getMaxRequests() != null
                ? interfaceConfig.getMaxRequests()
                : serviceConfig.getDefaultConfig().getMaxRequests();

        int timeWindow = interfaceConfig != null && interfaceConfig.getTimeWindow() != null
                ? interfaceConfig.getTimeWindow()
                : serviceConfig.getDefaultConfig().getTimeWindow();

        int banTime = interfaceConfig != null && interfaceConfig.getBanTime() != null
                ? interfaceConfig.getBanTime()
                : serviceConfig.getDefaultConfig().getBanTime();
        
        String path = exchange.getRequest().getPath().value();
        String ip = exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
        String baseKey = String.format("rate-limit:%s:%s", path, ip);

        return reactiveRedisTemplate.execute(
                        rateLimitScript,
                        List.of(baseKey),
                        List.of(maxRequests, timeWindow, banTime)
                )
                .next()
                .timeout(Duration.ofSeconds(2))
                .onErrorResume(e -> {
                    log.error("限流lua脚本执行失败: {}", e.getMessage());
                    return Mono.just(0L);
                })
                .flatMap(result -> {
                    if (result != null && result == 1) {
                        log.warn("触发限流: {}", baseKey);
                        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                        CommonResponse<Object> errorResp = CommonResponse.error(ErrorEnum.SYSTEM_GATEWAY_ERROR);
                        DataBuffer dataBuffer = null;
                        try {
                            dataBuffer = exchange.getResponse().bufferFactory().wrap(objectMapper.writeValueAsBytes(errorResp));
                        } catch (JsonProcessingException e) {
                            return Mono.error(new RuntimeException(e));
                        }
                        return exchange.getResponse().writeWith(Mono.just(dataBuffer));
                    }
                    return chain.filter(exchange);
                });
    }

    private RedisScript<Long> createRateLimitScript() {
        String script =
                "local key = KEYS[1] " +
                "local maxRequests = tonumber(ARGV[1]) " +
                "local timeWindow = tonumber(ARGV[2]) " +
                "local banTime = tonumber(ARGV[3]) " +
                "local limitKey = key .. ':lock' " +
                "local counterKey = key .. ':counter' " +
                "if redis.call('EXISTS', limitKey) == 1 then " +
                "    return 1 " +
                "end " +
                " " +
                "local count = redis.call('INCR', counterKey) " +
                "if count == 1 then " +
                "    redis.call('EXPIRE', counterKey, timeWindow) " +
                "end " +
                " " +
                "if count > maxRequests then " +
                "    redis.call('SETEX', limitKey, banTime, '1') " +
                "    redis.call('DEL', counterKey) " +
                "    return 1 " +
                "end " +
                "return 0";
        return new DefaultRedisScript<>(script, Long.class);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
