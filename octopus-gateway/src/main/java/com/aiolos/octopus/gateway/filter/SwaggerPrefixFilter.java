package com.aiolos.octopus.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

@Slf4j
@Component
public class SwaggerPrefixFilter implements GlobalFilter, Ordered {

    private static final Pattern API_DOCS_PATTERN = Pattern.compile("^/([^/]+)/v3/api-docs$");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        java.util.regex.Matcher matcher = API_DOCS_PATTERN.matcher(path);

        if (!matcher.matches()) {
            return chain.filter(exchange);
        }

        final String serviceId = matcher.group(1);
        final String pathPrefix = "/" + serviceId;

        return chain.filter(exchange.mutate().response(new ModifiedResponse(exchange, serviceId, pathPrefix)).build());
    }

    class ModifiedResponse extends ServerHttpResponseDecorator {
        private final String serviceId;
        private final String pathPrefix;

        public ModifiedResponse(ServerWebExchange exchange, String serviceId, String pathPrefix) {
            super(exchange.getResponse());
            this.serviceId = serviceId;
            this.pathPrefix = pathPrefix;
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            return DataBufferUtils.join(body)
                    .flatMap(dataBuffer -> {
                        try {
                            // 读取原始内容
                            byte[] content = readFullContent(dataBuffer);

                            // 处理内容
                            byte[] modifiedContent = processContent(content);

                            // 创建新缓冲区
                            DataBuffer newBuffer = bufferFactory().wrap(modifiedContent);

                            // 设置必要响应头
                            HttpHeaders headers = getDelegate().getHeaders();
                            headers.setContentLength(modifiedContent.length);
                            headers.remove(HttpHeaders.TRANSFER_ENCODING);

                            // 写入修改后的内容
                            return super.writeWith(Flux.just(newBuffer));
                        } catch (Exception e) {
                            handleProcessingError(dataBuffer, e);
                            return Mono.error(e);
                        }
                    })
                    .onErrorResume(this::handleFinalError);
        }

        private byte[] readFullContent(DataBuffer buffer) {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            DataBufferUtils.release(buffer);
            return bytes;
        }

        private void handleProcessingError(DataBuffer buffer, Throwable e) {
            DataBufferUtils.release(buffer);
            log.error("SWAGGER文档处理失败 [{}]", serviceId, e);
        }

        private Mono<Void> handleFinalError(Throwable e) {
            log.error("响应处理链异常", e);
            getDelegate().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return getDelegate().writeWith(Flux.empty());
        }

        private byte[] processContent(byte[] originalBytes) throws IOException {
            String originalJson = new String(originalBytes, StandardCharsets.UTF_8);
            JsonNode rootNode = objectMapper.readTree(originalJson);

            // 处理paths节点
            if (rootNode.has("paths")) {
                ObjectNode modifiedPaths = objectMapper.createObjectNode();
                rootNode.get("paths").fields().forEachRemaining(entry -> {
                    String newPath = pathPrefix + entry.getKey();
                    modifiedPaths.set(newPath, entry.getValue());
                });
                ((ObjectNode) rootNode).set("paths", modifiedPaths);
            }

            return objectMapper.writeValueAsBytes(rootNode);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
