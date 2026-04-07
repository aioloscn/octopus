package com.aiolos.octopus.gateway.filter;

import cn.hutool.core.collection.CollectionUtil;
import com.aiolos.badger.identitycore.api.AccountTokenApi;
import com.aiolos.badger.identitycore.dto.AccountDTO;
import com.aiolos.common.enums.GatewayHeaderEnum;
import com.aiolos.octopus.gateway.config.GatewayWhitelistProperties;
import com.aiolos.octopus.gateway.util.JwtUtil;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

@Slf4j
@Component
public class AccountCheckFilter implements GlobalFilter, Ordered {

    @DubboReference
    private AccountTokenApi accountTokenApi;
    
    @Resource
    private GatewayWhitelistProperties gatewayWhitelistProperties;
    
    @Resource
    private DiscoveryClient discoveryClient;
    
    @Value("${spring.profiles.active}")
    private String activeProfile;
    @Value("${cookie-domain}")
    private String cookieDomain;

    private final AntPathMatcher antPathMatcher = new AntPathMatcher();
    
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

        List<String> anonymousUrls = new ArrayList<>();
        GatewayWhitelistProperties.ServiceConfig serviceConfig = gatewayWhitelistProperties.findServiceConfig(serviceId);

        /*
          兜底方案，可以在octopus-gateway-config.yaml中添加
          whitelist:
            services:
              - id: live-living-provider
                urls:
                  - /living-room/list
                anonymous-urls:
                  - /living-room/anchor-config
          octopus会热更新白名单
         */
        if (serviceConfig != null) {
            if (CollectionUtil.isNotEmpty(serviceConfig.getUrls())) {
                String pathWithoutServiceId = path.replaceFirst("/" + serviceId, "");
                for (String whitelistUrl : serviceConfig.getUrls()) {
                    if (antPathMatcher.match(whitelistUrl, path)) {
                        // 不需要token校验，放行到下游服务
                        return chain.filter(exchange);
                    }
                    // 兼容配置的路径不带服务名的情况
                    if (antPathMatcher.match(whitelistUrl, pathWithoutServiceId)) {
                        return chain.filter(exchange);
                    }
                }
            }
            if (CollectionUtil.isNotEmpty(serviceConfig.getAnonymousUrls())) {
                String pathWithoutServiceId = path.replaceFirst("/" + serviceId, "");
                for (String anonymousUrl : serviceConfig.getAnonymousUrls()) {
                    if (antPathMatcher.match(anonymousUrl, path)) {
                        anonymousUrls.add(anonymousUrl);
                    }
                    if (antPathMatcher.match(anonymousUrl, pathWithoutServiceId)) {
                        anonymousUrls.add(anonymousUrl);
                    }
                }
            }
        }
        
        try {
            // 从Nacos元数据获取动态白名单（合并所有实例的配置，防止灰度发布期间配置不一致）
            List<ServiceInstance> instances = discoveryClient.getInstances(serviceId);
            if (CollectionUtil.isNotEmpty(instances)) {
                String pathWithoutServiceId = path.replaceFirst("/" + serviceId, "");
                for (ServiceInstance instance : instances) {
                    Map<String, String> metadata = instance.getMetadata();
                    if (metadata == null) continue;
                    
                    String whitelistStr = metadata.get("whitelist-urls");
                    if (StringUtils.isNotBlank(whitelistStr)) {
                        String[] urls = whitelistStr.split(",");
                        for (String url : urls) {
                            if (antPathMatcher.match(url, path)) {
                                return chain.filter(exchange);
                            }
                            // 兼容Nacos中配置的路径不带服务名的情况
                            if (antPathMatcher.match(url, pathWithoutServiceId)) {
                                return chain.filter(exchange);
                            }
                        }
                    }
                    
                    String anonymousStr = metadata.get("anonymous-urls");
                    if (StringUtils.isNotBlank(anonymousStr)) {
                        String[] urls = anonymousStr.split(",");
                        for (String url : urls) {
                            if (!anonymousUrls.contains(url)) {
                                anonymousUrls.add(url);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch metadata from Nacos for service: {}", serviceId, e);
        }

        // 不在白名单的请求需要提取cookie做校验
        String token = resolveToken(exchange);
        Long userId = null;

        if (StringUtils.isNotBlank(token)) {
            try {
                // 优先使用 JWT 本地解析
                String subject = JwtUtil.getSubjectFromToken(token);
                userId = Long.parseLong(subject);
            } catch (ExpiredJwtException e) {
                // 关键修改：捕获过期异常
                log.warn("JWT is expired. Token: {}, Error: {}", token, e.getMessage());
                // 不降级调用 RPC，让 userId 保持为 null
            } catch (Exception e) {
                log.warn("JWT validation failed, fallback to RPC: {}", e.getMessage());
                // 如果是其他类型的异常（如签名错误等），可以尝试回退 RPC
                try {
                    AccountDTO accountDto = accountTokenApi.getUserByToken(token);
                    if (accountDto != null && accountDto.getUserId() != null) {
                        userId = accountDto.getUserId();
                    }
                } catch (Exception rpcException) {
                    log.warn("RPC Token validation failed: {}", rpcException.getMessage());
                }
            }
            
            // 关键修复：如果传了 token，但经过 JWT 和 RPC 校验后 userId 依然为空，说明 token 是伪造的或已过期。
            // 此时我们不直接拦截返回 401，而是记录日志，让它以“未登录”的身份继续走下面的匿名判断逻辑。
            // 这样如果是允许匿名的接口（如加购物车），它依然可以获得匿名 ID 正常工作；如果是不允许匿名的接口，会在下面统一拦截。
            if (userId == null) {
                log.warn("Invalid token provided, degrading to anonymous request. Path: {}", path);
            }
        }

        ServerHttpRequest.Builder builder = request.mutate();
        
        if (userId != null) {

            // 将用户信息放入请求头，下游服务可以从请求头中获取，再放入ContextInfo中
            builder.header(GatewayHeaderEnum.USER_LOGIN_ID.getHeaderName(), userId.toString());
            builder.header(GatewayHeaderEnum.IS_ANONYMOUS.getHeaderName(), "false");
        } else {

            if (CollectionUtil.isNotEmpty(anonymousUrls)) {
                String pathWithoutServiceId = path.replaceFirst("/" + serviceId, "");
                for (String anonymousUrl : anonymousUrls) {
                    if (antPathMatcher.match(anonymousUrl, path)) {
                        return handleAnonymous(exchange, chain, builder);
                    }
                    if (antPathMatcher.match(anonymousUrl, pathWithoutServiceId)) {
                        return handleAnonymous(exchange, chain, builder);
                    }
                }
            }
            log.warn("Request intercepted due to lack of authentication, path: {}", path);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange.mutate().request(builder.build()).build());
    }

    private Mono<Void> handleAnonymous(ServerWebExchange exchange, GatewayFilterChain chain, ServerHttpRequest.Builder builder) {
        String deviceId = resolveDeviceId(exchange);
        Long anonymousId = accountTokenApi.getOrCreateAnonymousId(deviceId);
        builder.header(GatewayHeaderEnum.USER_LOGIN_ID.getHeaderName(), anonymousId.toString());
        builder.header(GatewayHeaderEnum.DEVICE_ID.getHeaderName(), deviceId);
        builder.header("device-id", deviceId);
        builder.header("X-Device-ID", deviceId);
        builder.header(GatewayHeaderEnum.IS_ANONYMOUS.getHeaderName(), "true");
        return chain.filter(exchange.mutate().request(builder.build()).build());
    }

    private String resolveToken(ServerWebExchange exchange) {
        // 直接从 Header 获取 Authorization: Bearer <token>
        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (StringUtils.isNotBlank(authHeader) && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
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
            List<String> plainDeviceHeaders = request.getHeaders().get("device-id");
            if (CollectionUtil.isNotEmpty(plainDeviceHeaders)) {
                deviceId = plainDeviceHeaders.get(0);
            }
        }

        if (StringUtils.isBlank(deviceId)) {
            HttpCookie deviceCookie = request.getCookies().getFirst("device-id");
            if (deviceCookie != null) {
                deviceId = deviceCookie.getValue();
            }
        }

        if (StringUtils.isBlank(deviceId)) {
            deviceId = UUID.randomUUID().toString().replace("-", "");
            ResponseCookie.ResponseCookieBuilder cookieBuilder = ResponseCookie.from("device-id", deviceId)
                    .maxAge(Duration.ofDays(7)) // 如果有未登录加购功能，可以设置365
                    .httpOnly(true)
                    .secure(activeProfile.equalsIgnoreCase("prod")) // 仅https传输
                    .path("/");
            // 关键保护：只有 cookie-domain 与当前请求主机匹配时才下发 Domain，避免出现 Domain=localhost 导致浏览器不回传 cookie
            String requestHost = request.getHeaders().getFirst("Host");
            if (shouldSetCookieDomain(requestHost)) {
                cookieBuilder.domain(cookieDomain);
            }
            ResponseCookie deviceCookie = cookieBuilder.build();
            exchange.getResponse().getHeaders().set("Access-Control-Allow-Credentials", "true");
            exchange.getResponse().addCookie(deviceCookie);
        }
        return deviceId;
    }

    private boolean shouldSetCookieDomain(String requestHost) {
        if (StringUtils.isBlank(cookieDomain)) {
            return false;
        }
        String normalizedDomain = StringUtils.removeStart(cookieDomain.toLowerCase(), ".");
        String normalizedHost = StringUtils.defaultString(requestHost).toLowerCase();
        int separatorIndex = normalizedHost.indexOf(":");
        if (separatorIndex >= 0) {
            normalizedHost = normalizedHost.substring(0, separatorIndex);
        }
        if ("localhost".equals(normalizedDomain)) {
            return "localhost".equals(normalizedHost);
        }
        return StringUtils.isBlank(normalizedHost)
                || normalizedHost.equals(normalizedDomain)
                || normalizedHost.endsWith("." + normalizedDomain);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }
}
