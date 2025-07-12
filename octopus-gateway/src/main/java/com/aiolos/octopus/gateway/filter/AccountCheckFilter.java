package com.aiolos.octopus.gateway.filter;

import cn.hutool.core.collection.CollectionUtil;
import com.aiolos.badger.identitycore.api.AccountTokenApi;
import com.aiolos.badger.identitycore.dto.AccountDTO;
import com.aiolos.common.enums.GatewayHeaderEnum;
import com.aiolos.octopus.gateway.config.GatewayWhitelistProperties;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

@Slf4j
@Component
public class AccountCheckFilter implements GlobalFilter, Ordered {

    @DubboReference
    private AccountTokenApi accountTokenApi;
    
    @Resource
    private GatewayWhitelistProperties gatewayWhitelistProperties;
    
    @Value("${spring.profiles.active}")
    private String activeProfile;
    
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);

        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        
        if (route == null || path.contains("api-docs")) {
            return chain.filter(exchange);
        }
        if (StringUtils.isBlank(path)) {
            return Mono.empty();
        }

        String serviceId = route.getUri().getHost();
        if (serviceId == null) {
            log.error("无法识别的服务路径: {}", path);
            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            return exchange.getResponse().setComplete();
        }

        List<String> anonymousUrls = null;
        GatewayWhitelistProperties.ServiceConfig serviceConfig = gatewayWhitelistProperties.findServiceConfig(serviceId);

        if (serviceConfig != null) {
            for (String whitelistUrl : serviceConfig.getUrls()) {
                if (path.contains(whitelistUrl)) {
                    // 不需要token校验，放行到下游服务
                    return chain.filter(exchange);
                }
            }
            anonymousUrls = serviceConfig.getAnonymousUrls();
        }

        // 不在白名单的请求需要提取cookie做校验
        String token = resolveToken(exchange);
        AccountDTO accountDto = null;

        if (StringUtils.isNotBlank(token)) {
            // 根据token获取userId
            accountDto = accountTokenApi.getUserByToken(token);
        }

        ServerHttpRequest.Builder builder = request.mutate();
        
        if (accountDto != null && accountDto.getUserId() != null) {

            // 将用户信息放入请求头，下游服务可以从请求头中获取，再放入ContextInfo中
            builder.header(GatewayHeaderEnum.USER_LOGIN_ID.getHeaderName(), accountDto.getUserId().toString());

            String userJson = JSON.toJSONString(accountDto);
            String encodedJson = Base64.getEncoder().encodeToString(userJson.getBytes(StandardCharsets.UTF_8));
            builder.header(GatewayHeaderEnum.USER_INFO_JSON.getHeaderName(), encodedJson);
            builder.header(GatewayHeaderEnum.IS_ANONYMOUS.getHeaderName(), "false");
        } else {

            if (anonymousUrls != null && anonymousUrls.stream().anyMatch(path::contains)) {
                // 匿名用户处理
                String deviceId = resolveDeviceId(exchange);
                Long anonymousId = accountTokenApi.getOrCreateAnonymousId(deviceId);
                builder.header(GatewayHeaderEnum.USER_LOGIN_ID.getHeaderName(), anonymousId.toString());
                builder.header(GatewayHeaderEnum.DEVICE_ID.getHeaderName(), deviceId);
                builder.header(GatewayHeaderEnum.IS_ANONYMOUS.getHeaderName(), "true");
            } else {
                return Mono.empty();
            }
        }
        return chain.filter(exchange.mutate().request(builder.build()).build());
    }

    private String resolveToken(ServerWebExchange exchange) {
        List<HttpCookie> tokens = exchange.getRequest().getCookies().get("live-token");
        if (CollectionUtil.isNotEmpty(tokens) && StringUtils.isNotBlank(tokens.get(0).getValue())) {
            return tokens.get(0).getValue();
        }
        return null;
    }

    private String resolveDeviceId(ServerWebExchange exchange) {
        String deviceId = null;
        ServerHttpRequest request = exchange.getRequest();
        List<String> deviceHeaders = request.getHeaders().get("X-Device-ID");
        if (CollectionUtil.isNotEmpty(deviceHeaders)) {
            deviceId = deviceHeaders.get(0);
        }

        if (StringUtils.isBlank(deviceId)) {
            HttpCookie deviceCookie = request.getCookies().getFirst("device-id");
            if (deviceCookie != null) {
                deviceId = deviceCookie.getValue();
            }
        }

        if (StringUtils.isBlank(deviceId)) {
            deviceId = UUID.randomUUID().toString().replace("-", "");
            ResponseCookie deviceCookie = ResponseCookie.from("device-id", deviceId)
                    .maxAge(Duration.ofDays(7)) // 如果有未登录加购功能，可以设置365
                    .httpOnly(true)
                    .secure(activeProfile.equalsIgnoreCase("prod")) // 仅https传输
                    .domain("live.aiolos.com")
                    .path("/")
                    .build();
            exchange.getResponse().getHeaders().set("Access-Control-Allow-Credentials", "true");
            exchange.getResponse().addCookie(deviceCookie);
        }
        return deviceId;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }
}
