package com.aiolos.octopus.gateway.config;

import com.ctrip.framework.apollo.Config;
import com.ctrip.framework.apollo.ConfigService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Apollo 灰度规则服务
 * 读取 Apollo 配置并实时决策是否命中用户画像ES灰度
 */
@Slf4j
@Component
public class ApolloGrayRuleService {

    public static final String ES_GRAY_HEADER = "X-Es-Profile-Gray";
    public static final String ES_GRAY_INSTANCE_TAG_HEADER = "X-Es-Profile-Instance-Tag";

    /**
     * 灰度总开关
     * 例子：true
     */
    private static final String KEY_ENABLED = "gateway.es.gray.enabled";

    /**
     * 用户白名单
     * 多个用户ID用逗号分隔
     */
    private static final String KEY_USER_IDS = "gateway.es.gray.user-ids";

    /**
     * 灰度百分比
     * 取值范围 0-100
     */
    private static final String KEY_PERCENT = "gateway.es.gray.percent";

    /**
     * 灰度生效服务列表
     * 多个服务ID用逗号分隔
     */
    private static final String KEY_MATCH_SERVICES = "gateway.es.gray.match.service-ids";

    /**
     * 灰度生效路径列表
     * 多个路径规则用逗号分隔
     * 例子：/shop/searchES,/shop/profile/searchES
     */
    private static final String KEY_MATCH_PATHS = "gateway.es.gray.match.path-patterns";

    /**
     * 灰度目标实例标签值
     * 例子：es-v2
     */
    private static final String KEY_INSTANCE_TAG = "gateway.es.gray.instance-tag";

    /**
     * 实例元数据中的标签键名
     * 例子：gray-tag
     */
    private static final String KEY_INSTANCE_META_KEY = "gateway.es.gray.instance-meta-key";

    /**
     * 是否按实例标签强制命中灰度
     * 例子：true
     */
    private static final String KEY_FORCE_GRAY_BY_INSTANCE_TAG = "gateway.es.gray.force-gray-by-instance-tag";

    /**
     * 业务命名空间，默认 application
     */
    private static final String KEY_NAMESPACE = "gateway.es.gray.apollo-namespace";

    /**
     * 是否命中灰度
     */
    public boolean hitEsGray(Long userId) {
        Config config = resolveConfig();
        if (!config.getBooleanProperty(KEY_ENABLED, false)) {
            return false;
        }
        // 配置了强制按实例标签灰度时，直接命中灰度
        if (config.getBooleanProperty(KEY_FORCE_GRAY_BY_INSTANCE_TAG, false)
                && StringUtils.isNotBlank(config.getProperty(KEY_INSTANCE_TAG, ""))) {
            return true;
        }
        if (userId != null && parseLongSet(config.getProperty(KEY_USER_IDS, "")).contains(userId)) {
            return true;
        }
        int percent = normalizePercent(config.getIntProperty(KEY_PERCENT, 0));
        if (percent <= 0 || userId == null) {
            return false;
        }
        long hash = Math.abs(userId % 100);
        return hash < percent;
    }

    /**
     * 获取目标实例标签
     */
    public String getGrayInstanceTag() {
        return StringUtils.trimToNull(resolveConfig().getProperty(KEY_INSTANCE_TAG, ""));
    }

    /**
     * 获取实例标签元数据键名
     */
    public String getGrayInstanceMetaKey() {
        return StringUtils.defaultIfBlank(
                StringUtils.trimToNull(resolveConfig().getProperty(KEY_INSTANCE_META_KEY, "")),
                "gray-tag"
        );
    }

    /**
     * 是否需要对当前请求打灰度标记
     * 通过 Apollo 配置驱动服务和路径匹配范围
     */
    public boolean shouldMarkEsGray(String serviceId, String path) {
        Config config = resolveConfig();
        if (!config.getBooleanProperty(KEY_ENABLED, false)) {
            return false;
        }
        Set<String> services = parseStringSet(config.getProperty(KEY_MATCH_SERVICES, ""));
        if (!services.isEmpty() && !services.contains(StringUtils.defaultString(serviceId).toLowerCase())) {
            return false;
        }
        Set<String> pathPatterns = parseStringSet(config.getProperty(KEY_MATCH_PATHS, ""));
        if (pathPatterns.isEmpty()) {
            return true;
        }
        String normalizedPath = StringUtils.defaultString(path).trim().toLowerCase();
        for (String pattern : pathPatterns) {
            String normalizedPattern = StringUtils.defaultString(pattern).trim().toLowerCase();
            if (normalizedPath.equals(normalizedPattern)
                    || normalizedPath.startsWith(normalizedPattern)
                    || normalizedPath.endsWith(normalizedPattern)) {
                return true;
            }
        }
        return false;
    }

    private Config resolveConfig() {
        Config appConfig = ConfigService.getAppConfig();
        String namespace = appConfig.getProperty(KEY_NAMESPACE, "application");
        if (StringUtils.isBlank(namespace) || "application".equalsIgnoreCase(namespace)) {
            return appConfig;
        }
        try {
            return ConfigService.getConfig(namespace);
        } catch (Exception e) {
            log.warn("读取 Apollo 命名空间失败，降级使用 application, namespace={}", namespace, e);
            return appConfig;
        }
    }

    private int normalizePercent(Integer percent) {
        if (percent == null) {
            return 0;
        }
        return Math.max(0, Math.min(100, percent));
    }

    private Set<Long> parseLongSet(String raw) {
        if (StringUtils.isBlank(raw)) {
            return Collections.emptySet();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .map(item -> {
                    try {
                        return Long.parseLong(item);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private Set<String> parseStringSet(String raw) {
        if (StringUtils.isBlank(raw)) {
            return Collections.emptySet();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }
}
