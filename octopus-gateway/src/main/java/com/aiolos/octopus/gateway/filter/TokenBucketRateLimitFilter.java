package com.aiolos.octopus.gateway.filter;

import com.aiolos.common.model.response.CommonResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 秒杀令牌桶限流过滤器 (基于 Bucket4j 实现)
 */
@Slf4j
@Component
public class TokenBucketRateLimitFilter implements GlobalFilter, Ordered {

    @Resource
    private ProxyManager<byte[]> proxyManager;
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 默认速率：每秒补充500个令牌
    private static final int REPLENISH_RATE = 500; 
    // 桶容量：最大1000个令牌
    private static final int BURST_CAPACITY = 1000; 

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        
        // 仅拦截秒杀相关接口，如 /seckill/xxx
        if (!path.contains("/seckill/")) {
            return chain.filter(exchange);
        }

        // 获取或构建 Bucket
        String bucketKeyStr = "seckill:bucket:" + path;
        byte[] bucketKey = bucketKeyStr.getBytes();
        
        // 使用 Bucket4j 代理管理器获取 Bucket
        Bucket bucket = proxyManager.builder().build(bucketKey, this::createBucketConfig);
        
        // 尝试消费1个令牌
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        
        if (probe.isConsumed()) {
            // 获取令牌成功，放行
            return chain.filter(exchange);
        } else {
            // 获取令牌失败，触发限流
            log.warn("秒杀活动火爆，触发令牌桶限流: {}", bucketKeyStr);
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            
            // 返回活动火爆提示
            CommonResponse<Object> errorResp = CommonResponse.error(429, "活动火爆，请重试");
            DataBuffer dataBuffer;
            try {
                dataBuffer = exchange.getResponse().bufferFactory().wrap(objectMapper.writeValueAsBytes(errorResp));
            } catch (JsonProcessingException e) {
                return Mono.error(new RuntimeException(e));
            }
            return exchange.getResponse().writeWith(Mono.just(dataBuffer));
        }
    }

    private BucketConfiguration createBucketConfig() {
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(BURST_CAPACITY)
                        .refillGreedy(REPLENISH_RATE, Duration.ofSeconds(1))
                        .build())
                .build();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 3;
    }
}

