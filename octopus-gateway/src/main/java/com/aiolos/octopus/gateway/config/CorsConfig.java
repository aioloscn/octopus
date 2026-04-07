package com.aiolos.octopus.gateway.config;

import com.google.common.collect.Lists;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * 推荐配置在yaml里
 */
@Configuration
@ConditionalOnProperty(prefix = "gateway.cors", name = "code-config-enabled", havingValue = "true")
public class CorsConfig {

    /**
     * Gateway 得用raactive.CorsWebFilter
     * @return
     */
    @Bean
    public CorsWebFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.setAllowedOriginPatterns(Lists.newArrayList(
                "http://127.0.0.1:*",
                "http://localhost:*",
                "https://127.0.0.1:*",
                "https://localhost:*",
                "http://*.aiolos.com",
                "https://*.aiolos.com"
        ));
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        // 预检请求的缓存时间（秒），即在这个时间段里，对于相同的跨域请求不会再预检了
        config.setMaxAge(36000L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
