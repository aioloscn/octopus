package com.aiolos.octopus.gateway.filter;

import com.aiolos.common.enums.GatewayHeaderEnum;
import com.aiolos.octopus.gateway.config.ApolloGrayRuleService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ReactiveLoadBalancerClientFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * 灰度实例路由过滤器
 * 命中灰度并携带实例标签时，将 lb://service 重写到指定标签实例
 */
@Slf4j
@Component
public class GrayInstanceRouteFilter implements GlobalFilter, Ordered {

    @Resource
    private DiscoveryClient discoveryClient;

    @Resource
    private ApolloGrayRuleService apolloGrayRuleService;

    /**
     * 根据灰度头和实例标签重写请求目标实例
     * 仅对 lb://service 请求生效
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        URI requestUrl = exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR);
        if (route == null || requestUrl == null || !"lb".equalsIgnoreCase(requestUrl.getScheme())) {
            return chain.filter(exchange);
        }
        String serviceId = route.getUri().getHost();
        String path = exchange.getRequest().getURI().getPath();
        // 二次校验灰度范围和总开关，保证 enabled=false 时不会做实例重写
        if (!apolloGrayRuleService.shouldMarkEsGray(serviceId, path)) {
            return chain.filter(exchange);
        }

        String grayHeader = exchange.getRequest().getHeaders().getFirst(ApolloGrayRuleService.ES_GRAY_HEADER);
        String instanceTag = exchange.getRequest().getHeaders().getFirst(ApolloGrayRuleService.ES_GRAY_INSTANCE_TAG_HEADER);
        // 未命中灰度或未指定实例标签时走默认负载均衡
        if (!"1".equals(grayHeader) || StringUtils.isBlank(instanceTag)) {
            return chain.filter(exchange);
        }
        if (StringUtils.isBlank(serviceId)) {
            return chain.filter(exchange);
        }

        List<ServiceInstance> instances = discoveryClient.getInstances(serviceId);
        if (instances == null || instances.isEmpty()) {
            return chain.filter(exchange);
        }

        String metaKey = apolloGrayRuleService.getGrayInstanceMetaKey();
        List<ServiceInstance> tagged = instances.stream()
                .filter(instance -> {
                    Map<String, String> metadata = instance.getMetadata();
                    if (metadata == null) {
                        return false;
                    }
                    return instanceTag.equalsIgnoreCase(metadata.get(metaKey));
                })
                .toList();
        if (tagged.isEmpty()) {
            log.warn("灰度标签未匹配到实例, serviceId={}, tag={}, metaKey={}", serviceId, instanceTag, metaKey);
            return chain.filter(exchange);
        }

        Long userId = parseUserId(exchange.getRequest().getHeaders().getFirst(GatewayHeaderEnum.USER_LOGIN_ID.getHeaderName()));
        ServiceInstance target = chooseInstance(tagged, userId);
        URI original = exchange.getRequest().getURI();
        String scheme = target.isSecure() ? "https" : "http";
        URI resolved = URI.create(String.format("%s://%s:%d%s%s",
                scheme,
                target.getHost(),
                target.getPort(),
                StringUtils.defaultString(original.getRawPath()),
                original.getRawQuery() == null ? "" : "?" + original.getRawQuery()));
        exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, resolved);
        return chain.filter(exchange);
    }

    /**
     * 在同标签实例内做稳定选择
     * 使用 userId 保证用户路由稳定
     */
    private ServiceInstance chooseInstance(List<ServiceInstance> candidates, Long userId) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        candidates = candidates.stream()
                .sorted(Comparator.comparing(ServiceInstance::getInstanceId, Comparator.nullsLast(String::compareTo)))
                .toList();
        if (userId == null) {
            return candidates.get(0);
        }
        int index = (int) (Math.abs(userId) % candidates.size());
        return candidates.get(index);
    }

    /**
     * 解析请求头中的 userId
     */
    private Long parseUserId(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public int getOrder() {
        // 比 Spring Cloud Gateway 的 LB 过滤器更早执行
        return ReactiveLoadBalancerClientFilter.LOAD_BALANCER_CLIENT_FILTER_ORDER - 1;
    }
}
