package com.aiolos.octopus.gateway.filter;

import com.aiolos.common.enums.GatewayHeaderEnum;
import com.aiolos.octopus.gateway.config.ApolloGrayRuleService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * 通用灰度过滤器
 * 只负责按 Apollo 规则打灰度标记
 */
@Slf4j
@Component
public class FeatureGrayFilter implements GlobalFilter, Ordered {

    @Resource
    private ApolloGrayRuleService apolloGrayRuleService;

    /**
     * 按 Apollo 规则对请求打灰度头
     * 仅标记，不负责实例路由与鉴权
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        if (route == null) {
            return chain.filter(exchange);
        }
        String serviceId = route.getUri().getHost();
        String path = exchange.getRequest().getURI().getPath();
        ServerHttpRequest.Builder builder = exchange.getRequest().mutate();
        // 先移除外部传入的灰度头，避免伪造请求污染路由决策
        builder.headers(headers -> {
            headers.remove(ApolloGrayRuleService.ES_GRAY_HEADER);
            headers.remove(ApolloGrayRuleService.ES_GRAY_INSTANCE_TAG_HEADER);
        });
        // 未命中灰度范围时直接透传
        if (!apolloGrayRuleService.shouldMarkEsGray(serviceId, path)) {
            return chain.filter(exchange.mutate().request(builder.build()).build());
        }

        Long userId = parseUserId(exchange.getRequest().getHeaders().getFirst(GatewayHeaderEnum.USER_LOGIN_ID.getHeaderName()));
        boolean hitGray = apolloGrayRuleService.hitEsGray(userId);
        builder.header(ApolloGrayRuleService.ES_GRAY_HEADER, hitGray ? "1" : "0");
        String instanceTag = apolloGrayRuleService.getGrayInstanceTag();
        if (hitGray && StringUtils.isNotBlank(instanceTag)) {
            builder.header(ApolloGrayRuleService.ES_GRAY_INSTANCE_TAG_HEADER, instanceTag);
        }
        return chain.filter(exchange.mutate().request(builder.build()).build());
    }

    /**
     * 解析请求头中的 userId
     */
    private Long parseUserId(String header) {
        if (StringUtils.isBlank(header)) {
            return null;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public int getOrder() {
        // 鉴权过滤器写入 userId 后再执行灰度规则
        return Ordered.HIGHEST_PRECEDENCE + 3;
    }
}
