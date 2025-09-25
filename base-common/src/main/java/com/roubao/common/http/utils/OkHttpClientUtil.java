package com.roubao.common.http.utils;

import cn.hutool.http.HttpStatus;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import lombok.Data;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.net.*;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Http请求静态工具类 OKHttp
 *
 * @author : SongYanBin
 * @since 2025/9/20
 */
@Slf4j
public class OkHttpClientUtil {
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final OkHttpClient defaultClient;
    private static boolean isMonitoringStarted = false;
    private static final int MAX_CONNECTIONS = 200;

    private static final LoadingCache<String, TimeoutTag> timoutTagCache = Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build((key) -> {
                String[] split = key.split("-");
                return new TimeoutTag(Integer.parseInt(split[0]), Integer.parseInt(split[1]), Integer.parseInt(split[2]), Integer.parseInt(split[3]));
            });

    static {
        defaultClient = new OkHttpClient.Builder()
                .pingInterval(30, TimeUnit.SECONDS)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .connectionPool(new ConnectionPool(MAX_CONNECTIONS, 5, TimeUnit.MINUTES))
                .addInterceptor(new TimeoutInterceptor())
                .addInterceptor(new RetryInterceptor(0, 1000, Arrays.asList(SocketException.class, EOFException.class)))
                .eventListenerFactory(new TimingEventListenerFactory())
                .build();

        // 启动定时任务，监控连接池使用状况
        startConnectionPoolMonitoring(defaultClient, 5, TimeUnit.MINUTES);
    }

    private static void startConnectionPoolMonitoring(OkHttpClient okHttpClient, long period, TimeUnit unit) {
        if (isMonitoringStarted) {
            log.warn("Connection pool monitoring is already started.");
            return;
        }
        isMonitoringStarted = true;
        scheduler.scheduleAtFixedRate(() -> {
            try {
                ConnectionPool connectionPool = okHttpClient.connectionPool();
                log.info("[Pay_Connection_Pool_Status]: connectionCount={}, idleConnectionCount={}, activeConnectionCount={}, maxConnectionCount={}",
                        connectionPool.connectionCount(),
                        connectionPool.idleConnectionCount(),
                        connectionPool.connectionCount() - connectionPool.idleConnectionCount(),
                        MAX_CONNECTIONS);
            } catch (Exception e) {
                log.error("FailedMonitoringPayConnectPool:{}", e.getMessage(), e);
            }
        }, 0, period, unit);
    }

    public static Builder newRequest(String url) {
        return new Builder(url, defaultClient);
    }

    public static Builder newRequest(String url, OkHttpClient client) {
        return new Builder(url, client);
    }

    public static Builder newRequestPOST(String url) {
        return newRequestPOST(url, defaultClient);
    }

    public static Builder newRequestGET(String url) {
        return newRequestGET(url, defaultClient);
    }

    public static Builder newRequestPOST(String url, OkHttpClient client) {
        Builder builder = new Builder(url, client);
        return builder.POST();
    }

    public static Builder newRequestGET(String url, OkHttpClient client) {
        Builder builder = new Builder(url, client);
        return builder.GET();
    }

    public static class Builder {
        private String url;
        private String method = "GET";
        private final Headers.Builder headers = new Headers.Builder();
        private RequestBody body;
        private String bodyStr;
        private boolean async = false;
        private Callback asyncCallback;
        private final OkHttpClient client;
        private boolean enableLogging = false; // 默认不启用日志
        private TimeoutTag timeoutTag;
        private RetryTag retryTag;
        private RequestExtTag requestExtTag;

        public Builder(String url, OkHttpClient client) {
            if (url == null || url.trim().isEmpty()) {
                throw new IllegalArgumentException("URL must not be null or empty");
            }
            this.client = Objects.requireNonNull(client, "OkHttpClient must not be null");
            this.url = url;
        }

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        public Builder enableLogging() {
            this.enableLogging = true;
            return this;
        }

        public Builder disableLogging() {
            this.enableLogging = false;
            return this;
        }

        public Builder timeout(int connectTimeoutMs, int readTimeoutMs, int writeTimeoutMs, int callTimeoutMs) {
            this.timeoutTag = timoutTagCache.get(connectTimeoutMs + "-" + readTimeoutMs + "-" + writeTimeoutMs + "-" + callTimeoutMs);
            return this;
        }

        public Builder retry(int maxRetries, long retryDelayMillis, List<Class<? extends IOException>> retryableExceptions) {
            this.retryTag = new RetryTag(maxRetries, retryDelayMillis, retryableExceptions);
            return this;
        }

        public Builder requestExtTag(RequestExtTag requestExtTag) {
            this.requestExtTag = requestExtTag;
            return this;
        }

        public Builder method(String method) {
            this.method = method.toUpperCase();
            return this;
        }

        public Builder GET() {
            return method("GET");
        }

        public Builder POST() {
            return method("POST");
        }

        public Builder PUT() {
            return method("PUT");
        }

        public Builder DELETE() {
            return method("DELETE");
        }

        public Builder PATCH() {
            return method("PATCH");
        }

        public Builder addHeader(String name, String value) {
            headers.add(name, value);
            return this;
        }

        public Builder headers(Map<String, String> headersMap) {
            if (headersMap != null) {
                headersMap.forEach(this.headers::add);
            }
            return this;
        }

        public Builder queryParams(Map<String, String> queryParams) {
            if (url != null && queryParams != null && !queryParams.isEmpty()) {
                HttpUrl.Builder httpUrlBuilder = Objects.requireNonNull(HttpUrl.parse(url)).newBuilder();
                queryParams.forEach(httpUrlBuilder::addQueryParameter);
                this.url = httpUrlBuilder.build().toString();
            }
            return this;
        }

        public Builder json(String json) {
            this.bodyStr = json;
            return body(json, JSON_MEDIA_TYPE);
        }

        public Builder json(Map<String, Object> jsonMap) {
            String jsonString = JSONUtil.toJsonStr(jsonMap);
            this.bodyStr = jsonString;
            return body(jsonString, JSON_MEDIA_TYPE);
        }

        public Builder form(Map<String, String> formParams) {
            this.bodyStr = JSONUtil.toJsonStr(formParams);
            FormBody.Builder formBuilder = new FormBody.Builder();
            if (formParams != null) {
                formParams.forEach(formBuilder::add);
            }
            this.body = formBuilder.build();
            return this;
        }

        public Builder body(String content, MediaType mediaType) {
            this.bodyStr = content;
            this.body = RequestBody.create(content, mediaType);
            return this;
        }

        public Builder async(Callback callback) {
            if (callback == null) {
                throw new IllegalArgumentException("Async callback must not be null");
            }
            this.async = true;
            this.asyncCallback = callback;
            return this;
        }

        public Builder sync() {
            this.async = false;
            this.asyncCallback = null;
            return this;
        }

        public HttpResponse executeSync() {
            this.async = false;
            return execute();
        }

        public void executeAsync() {
            executeAsync(this.asyncCallback);
        }

        public void executeAsync(Callback asyncCallback) {
            if (asyncCallback == null) {
                throw new IllegalArgumentException("asyncCallback must not be null");
            }
            this.async = true;
            this.asyncCallback = new Callback() {
                @Override
                public void onFailure(@NotNull Call call, @NotNull IOException e) {
                    log.error("Async request failed after retries url:{} errorMsg:{}", call.request().url(), e.getMessage(), e);
                    asyncCallback.onFailure(call, e);
                }

                @Override
                public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                    log.info("Async Received response with statusCode:{} url:{}", response.code(), call.request().url());
                    asyncCallback.onResponse(call, response);
                }
            };
            execute();
        }

        private HttpResponse execute() {
            Request.Builder requestBuilder = new Request.Builder()
                    .url(this.url)
                    .headers(this.headers.build())
                    .tag(Boolean.class, this.enableLogging);
            // 设置动态超时配置的Tag
            if (this.timeoutTag != null) {
                requestBuilder.tag(TimeoutTag.class, this.timeoutTag);
            }
            // 设置动态重试配置的Tag
            if (this.retryTag != null) {
                requestBuilder.tag(RetryTag.class, this.retryTag);
            }
            // 设置扩展参数的Tag
            if (this.requestExtTag != null) {
                requestBuilder.tag(RequestExtTag.class, this.requestExtTag);
            }

            switch (method) {
                case "POST":
                case "PUT":
                case "PATCH":
                case "DELETE":
                    if (body == null) {
                        body = RequestBody.create(new byte[0], JSON_MEDIA_TYPE);
                    }
                    requestBuilder.method(method, body);
                    break;
                default:
                    requestBuilder.method(method, null);
                    break;
            }

            Request request = requestBuilder.build();
            Response response = null;
            long startMillis = System.currentTimeMillis();
            String responseBodyStr = null;
            Long userId = this.requestExtTag != null ? this.requestExtTag.getUserId() : 0;
            String orderId = this.requestExtTag != null ? this.requestExtTag.getRequestUniqueKey() : "none";
            String headerStr = JSONUtil.toJsonStr(headers.getNamesAndValues$okhttp());
            try {
                log.info("""
                                == [{}][{}]Pay_execute_request_start =>
                                == time => startMillis:{}
                                == method => {}
                                == url => {}
                                == async => {}
                                == header => {}
                                == requestBody => {}
                                """,
                        orderId, userId, startMillis, method, url, async, headerStr, bodyStr);
                Call call = client.newCall(request);
                // 设置整个请求的超时时间
                if (this.timeoutTag != null) {
                    log.info("[{}][{}]Pay_execute_resetTimeout callTimeoutMs:{}",
                            orderId, userId, timeoutTag.getCallTimeoutMs());
                    call.timeout().timeout(this.timeoutTag.getCallTimeoutMs(), TimeUnit.MILLISECONDS);
                }
                if (async && asyncCallback != null) {
                    call.enqueue(asyncCallback);
                } else {
                    try (Response executeResult = call.execute()) {
                        // 在此就将响应体数据取出来，该方法不可二次调用
                        responseBodyStr = executeResult.body().string();
                        response = executeResult;
                    }
                }
                long endMillis = System.currentTimeMillis();
                log.info("""
                                 == [{}][{}]Pay_execute_request_end =>
                                 == time => startMillis:{} | endMillis:{} | durationMillis:{}
                                 == method => {}
                                 == url => {}
                                 == async => {}
                                 == header => {}
                                 == requestBody => {}
                                 == responseBody => {}
                                """,
                        orderId, userId, startMillis, endMillis, (endMillis - startMillis), method, url, async, headerStr, bodyStr, responseBodyStr);
                return HttpResponse.buildSuccess(response, responseBodyStr, startMillis, endMillis);
            } catch (SocketTimeoutException ex2) {
                long endMillis = System.currentTimeMillis();
                log.error("""
                                = [{}][{}]Pay_execute_request_SocketTimeoutException =>
                                = time => startMillis:{} | endMillis:{} | durationMillis:{}
                                = method => {}
                                = url => {}
                                = async => {}
                                = header => {}
                                = requestBody => {}
                                = errorMsg => {}
                                """,
                        orderId, userId, startMillis, endMillis, (endMillis - startMillis), this.method, this.url, async, headerStr, bodyStr, ex2.getMessage(), ex2);
                return HttpResponse.buildException(9000, startMillis, endMillis, ex2.getMessage());
            } catch (IOException ex3) {
                long endMillis = System.currentTimeMillis();
                log.error("""
                                = [{}][{}]Pay_execute_request_IOException =>
                                = time => startMillis:{} | endMillis:{} | durationMillis:{}
                                = method => {}
                                = url => {}
                                = async => {}
                                = header => {}
                                = requestBody => {}
                                = errorMsg => {}
                                """,
                        orderId, userId, startMillis, endMillis, (endMillis - startMillis), this.method, this.url, async, headerStr, bodyStr, ex3.getMessage(), ex3);
                return HttpResponse.buildException(9001, startMillis, endMillis, ex3.getMessage());
            } catch (Exception ex) {
                long endMillis = System.currentTimeMillis();
                log.error("""
                                = [{}][{}]Pay_execute_request_exception =>
                                = time => startMillis:{} | endMillis:{} | durationMillis:{}
                                = method => {}
                                = url => {}
                                = async => {}
                                = header => {}
                                = requestBody => {}
                                = errorMsg => {}
                                """,
                        orderId, userId, startMillis, endMillis, (endMillis - startMillis), this.method, this.url, async, headerStr, bodyStr, ex.getMessage(), ex);
                return HttpResponse.buildException(10000, startMillis, endMillis, ex.getMessage());
            }
        }
    }

    @Data
    @ToString
    public static class HttpResponse implements Serializable {
        @Serial
        private static final long serialVersionUID = -3914070292690635740L;

        private boolean ok;
        private boolean successful;
        private int httpCode;
        private String bodyStr;
        private long durationMillis;
        private long startMillis;
        private long endMillis;
        private String message;
        private Response response;

        public static HttpResponse buildSuccess(Response response, String responseBodyStr, long startMillis, long endMillis) {
            HttpResponse httpResponse = new HttpResponse();
            httpResponse.setOk(true);
            if (response == null) {
                httpResponse.setSuccessful(true);
                httpResponse.setHttpCode(HttpStatus.HTTP_OK);
            } else {
                httpResponse.setSuccessful(response.isSuccessful());
                httpResponse.setHttpCode(response.code());
            }
            httpResponse.setBodyStr(responseBodyStr);
            httpResponse.setDurationMillis((endMillis - startMillis));
            httpResponse.setStartMillis(startMillis);
            httpResponse.setEndMillis(endMillis);
            httpResponse.setMessage("success");
            httpResponse.setResponse(response);
            return httpResponse;
        }

        public static HttpResponse buildException(int code, long startMillis, long endMillis, String message) {
            HttpResponse httpResponse = new HttpResponse();
            httpResponse.setOk(false);
            httpResponse.setSuccessful(false);
            httpResponse.setHttpCode(code);
            httpResponse.setDurationMillis((endMillis - startMillis));
            httpResponse.setStartMillis(startMillis);
            httpResponse.setEndMillis(endMillis);
            httpResponse.setMessage(message);
            return httpResponse;
        }

        @SneakyThrows
        public void assertSuccess(String message) {
            if (!successful || !ok) {
                if (httpCode == 9000) {
                    throw new SocketTimeoutException(message);
                } else if (httpCode == 9001) {
                    throw new IOException(message);
                } else {
                    throw new RuntimeException(message);
                }
            }
        }

        @SneakyThrows
        public void assertPaySuccess(String message) {
            if (!successful || !ok) {
                if (httpCode == 9000) {
                    throw new RuntimeException(message);
                } else if (httpCode == 9001) {
                    throw new RuntimeException(message);
                } else {
                    throw new RuntimeException(message);
                }
            }
        }

        @SneakyThrows
        public void assertOk(String message) {
            if (!ok) {
                if (httpCode == 9000) {
                    throw new SocketTimeoutException(message);
                } else if (httpCode == 9001) {
                    throw new IOException(message);
                } else {
                    throw new RuntimeException(message);
                }
            }
        }
    }


    /**
     * 重试拦截器
     */
    private static class RetryInterceptor implements Interceptor {
        private int maxRetries;
        private long retryDelayMillis;
        private List<Class<? extends IOException>> retryableExceptions;

        public RetryInterceptor(int maxRetries, long retryDelayMillis, List<Class<? extends IOException>> retryableExceptions) {
            this.maxRetries = maxRetries;
            this.retryDelayMillis = retryDelayMillis;
            this.retryableExceptions = retryableExceptions != null ? retryableExceptions : Arrays.asList(SocketException.class, EOFException.class);
        }

        @NotNull
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            RequestExtTag requestExtTag = request.tag(RequestExtTag.class);
            String orderId = requestExtTag != null ? requestExtTag.getRequestUniqueKey() : "none";
            Long userId = requestExtTag != null ? requestExtTag.getUserId() : 0;

            // 若动态设置了重试配置，则重新赋值
            RetryTag tag = request.tag(RetryTag.class);
            if (tag != null) {
                log.info("[{}][{}]Pay_RetryInterceptor_getRetryTag:{}", orderId, userId, JSONUtil.toJsonStr(tag));
                this.maxRetries = tag.getMaxRetries();
                this.retryDelayMillis = tag.getRetryDelayMillis();
                this.retryableExceptions = tag.getRetryableExceptions();
            }
            Response response = null;
            IOException lastException = null;
            int retryCount = 0;
            while (retryCount <= maxRetries) {
                // 第一次在正常执行不记录日志
                if (retryCount > 0) {
                    log.info("[{}][{}]Pay_RetryInterceptor_process retryCount:{} ,maxRetries:{} ,retryDelayMillis:{} ,exceptionMsg:{}",
                            orderId, userId, retryCount, maxRetries, retryDelayMillis, lastException.getMessage(), lastException);
                }
                try {
                    return chain.proceed(request);
                } catch (IOException e) {
                    // 检查是否是可重试的异常
                    if (!isRetryableException(e) || retryCount >= maxRetries) {
                        throw e;
                    }
                    lastException = e;
                }

                retryCount++;
                if (retryCount <= maxRetries) {
                    // 延迟重试
                    if (retryDelayMillis > 0) {
                        try {
                            Thread.sleep(retryDelayMillis);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted during retry delay", ie);
                        }
                    }
                }
            }
            throw lastException != null ? lastException : new IOException("Request failed after " + maxRetries + " retries");
        }

        private boolean isRetryableException(IOException e) {
            return retryableExceptions.stream().anyMatch(ex -> ex.isInstance(e));
        }
    }

    public static class TimeoutInterceptor implements Interceptor {
        @NotNull
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            TimeoutTag timeoutTag = request.tag(TimeoutTag.class);
            if (timeoutTag != null) {
                RequestExtTag requestExtTag = request.tag(RequestExtTag.class);
                String orderId = requestExtTag != null ? requestExtTag.getRequestUniqueKey() : "none";
                Long userId = requestExtTag != null ? requestExtTag.getUserId() : 0;
                log.info("[{}][{}]Pay_TimeoutInterceptor_reset connectTimeoutMs:{} ,readTimeoutMs:{} ,writeTimeoutMs:{}",
                        orderId, userId, timeoutTag.getConnectTimeoutMs(), timeoutTag.getReadTimeoutMs(), timeoutTag.getWriteTimeoutMs());
                // 修改当前请求的超时时间
                return chain.withConnectTimeout(timeoutTag.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                        .withReadTimeout(timeoutTag.getReadTimeoutMs(), TimeUnit.MILLISECONDS)
                        .withWriteTimeout(timeoutTag.getWriteTimeoutMs(), TimeUnit.MILLISECONDS)
                        .proceed(request);
            }

            // 默认使用 OkHttpClient 的全局超时
            return chain.proceed(request);
        }
    }


    /**
     * 自定义 EventListener 工厂，用于记录每个请求阶段的耗时
     */
    private static class TimingEventListenerFactory implements EventListener.Factory {
        @NotNull
        @Override
        public EventListener create(@NotNull Call call) {
            Boolean loggingEnabled = call.request().tag(Boolean.class);
            RequestExtTag requestExtTag = call.request().tag(RequestExtTag.class);
            return new TimingEventListener(Boolean.TRUE.equals(loggingEnabled), requestExtTag);
        }
    }

    /**
     * 自定义 EventListener，完整监控所有请求阶段的耗时
     */
    private static class TimingEventListener extends EventListener {
        private final ConcurrentHashMap<String, Long> timings = new ConcurrentHashMap<>();
        private final StringBuilder logBuilder = new StringBuilder();
        private final String LINE_SEPARATOR = System.lineSeparator();
        private final boolean enableLogging;
        private final RequestExtTag requestExtTag;

        public TimingEventListener(boolean enableLogging, @Nullable RequestExtTag requestExtTag) {
            this.enableLogging = enableLogging;
            this.requestExtTag = requestExtTag;
        }

        @Override
        public void callStart(@NotNull Call call) {
            if (!enableLogging) {
                return;
            }
            Instant startTime = Instant.now();
            long startMilli = startTime.toEpochMilli();
            timings.put("callStart", startMilli);
            logBuilder.append("== CallStart => ");
            if (requestExtTag != null) {
                logBuilder.append("requestId: ").append(requestExtTag.getRequestUniqueKey());
                logBuilder.append(" |userId: ").append(requestExtTag.getUserId());
            }
            logBuilder.append(" |url: ").append(call.request().url())
                    .append(" |callStartTime: ").append(startMilli)
                    .append(LINE_SEPARATOR);
        }

        @Override
        public void callEnd(@NotNull Call call) {
            if (!enableLogging) {
                return;
            }
            long endTime = Instant.now().toEpochMilli();
            timings.put("callEnd", endTime);
            Long callStartMilli = timings.get("callStart");
            long totalDuration = (endTime - callStartMilli);
            logBuilder.append("== CallEnd => ")
                    .append("callStartTime: ").append(callStartMilli)
                    .append(" |callEndTime: ").append(endTime)
                    .append(" |durationTime: ").append(totalDuration)
                    .append(LINE_SEPARATOR);
            log.info("== CallListenerDetail =>" + LINE_SEPARATOR + logBuilder);
        }

        @Override
        public void callFailed(@NotNull Call call, IOException ioe) {
            if (!enableLogging) {
                return;
            }
            long endTime = Instant.now().toEpochMilli();
            timings.put("callEnd", endTime);
            Long callStartMilli = timings.get("callStart");
            long totalDuration = (endTime - callStartMilli);
            logBuilder.append("== CallFailed => ")
                    .append("callStartTime: ").append(callStartMilli)
                    .append(" |callEndTime: ").append(endTime)
                    .append(" |durationTime: ").append(totalDuration)
                    .append(" |errorMsg: ").append(ioe.getMessage())
                    .append(LINE_SEPARATOR);
            log.error("== CallListenerDetail =>" + LINE_SEPARATOR + logBuilder);
        }

        @Override
        public void dnsStart(@NotNull Call call, @NotNull String domainName) {
            if (!enableLogging) {
                return;
            }
            long epochMilli = Instant.now().toEpochMilli();
            timings.put("dnsStart", epochMilli);
            logBuilder.append("== DnsStart => ")
                    .append("domainName: ").append(domainName)
                    .append(" |dnsStartTime: ").append(epochMilli)
                    .append(LINE_SEPARATOR);
        }

        @Override
        public void dnsEnd(@NotNull Call call, @NotNull String domainName, @NotNull List<InetAddress> inetAddressList) {
            if (!enableLogging) {
                return;
            }
            long dnsEnd = Instant.now().toEpochMilli();
            timings.put("dnsEnd", dnsEnd);
            Long dnsStartMilli = timings.get("dnsStart");
            long dnsDuration = (dnsEnd - dnsStartMilli);
            logBuilder.append("== DnsEnd => ")
                    .append("domainName: ").append(domainName)
                    .append(" |dnsStartTime: ").append(dnsStartMilli)
                    .append(" |dnsEndTime: ").append(dnsEnd)
                    .append(" |durationTime: ").append(dnsDuration)
                    .append(LINE_SEPARATOR);
        }

        @Override
        public void connectStart(@NotNull Call call, @NotNull InetSocketAddress inetSocketAddress, @NotNull Proxy proxy) {
            if (!enableLogging) {
                return;
            }
            long epochMilli = Instant.now().toEpochMilli();
            timings.put("connectStart", epochMilli);
            boolean afterAcquired = timings.containsKey("connectionAcquired");
            logBuilder.append("== ConnectStart => ")
                    .append("inetSocketAddress: ").append(inetSocketAddress)
                    .append(" |connectStartTime: ").append(epochMilli)
                    .append(" |afterConnectionAcquired: ").append(afterAcquired)
                    .append(LINE_SEPARATOR);
        }

        @Override
        public void connectEnd(@NotNull Call call, @NotNull InetSocketAddress inetSocketAddress, @NotNull Proxy proxy, Protocol protocol) {
            if (!enableLogging) {
                return;
            }
            long connectEnd = Instant.now().toEpochMilli();
            Long connectStartMilli = timings.get("connectStart");
            timings.put("connectEnd", connectEnd);
            long connectDuration = (connectEnd - connectStartMilli);
            logBuilder.append("== ConnectEnd => ")
                    .append("connectStartTime: ").append(connectStartMilli)
                    .append(" |connectEndTime: ").append(connectEnd)
                    .append(" |durationTime: ").append(connectDuration)
                    .append(LINE_SEPARATOR);
        }

        @Override
        public void connectFailed(@NotNull Call call, @NotNull InetSocketAddress inetSocketAddress, @NotNull Proxy proxy, Protocol protocol, IOException ioe) {
            if (!enableLogging) {
                return;
            }
            long connectEnd = Instant.now().toEpochMilli();
            timings.put("connectEnd", connectEnd);
            Long connectStartMilli = timings.get("connectStart");
            long connectDuration = (connectEnd - connectStartMilli);
            logBuilder.append("== ConnectFailed => ")
                    .append("connectStartTime: ").append(connectStartMilli)
                    .append(" |connectEndTime: ").append(connectEnd)
                    .append(" |durationTime: ").append(connectDuration)
                    .append(" |errorMsg: ").append(ioe.getMessage())
                    .append(LINE_SEPARATOR);
        }

        @Override
        public void requestHeadersStart(@NotNull Call call) {
            if (!enableLogging) {
                return;
            }
            long epochMilli = Instant.now().toEpochMilli();
            timings.put("requestHeadersStart", epochMilli);
            logBuilder.append("== RequestHeadersStart => ")
                    .append("requestHeadersStartTime: ").append(epochMilli)
                    .append(LINE_SEPARATOR);
        }

        @Override
        public void requestHeadersEnd(@NotNull Call call, @NotNull Request request) {
            if (!enableLogging) {
                return;
            }
            long requestHeadersEnd = Instant.now().toEpochMilli();
            timings.put("requestHeadersEnd", requestHeadersEnd);
            Long requestHeadersStartMilli = timings.get("requestHeadersStart");
            long requestHeadersDuration = (requestHeadersEnd - requestHeadersStartMilli);
            logBuilder.append("== RequestHeadersEnd => ")
                    .append("requestHeadersStartTime: ").append(requestHeadersStartMilli)
                    .append(" |requestHeadersEndTime: ").append(requestHeadersEnd)
                    .append(" |durationTime: ").append(requestHeadersDuration)
                    .append(LINE_SEPARATOR);
        }

        @Override
        public void requestBodyStart(@NotNull Call call) {
            if (!enableLogging) {
                return;
            }
            long epochMilli = Instant.now().toEpochMilli();
            timings.put("requestBodyStart", epochMilli);
            logBuilder.append("== RequestBodyStart => ")
                    .append("requestBodyStartTime: ").append(epochMilli)
                    .append(LINE_SEPARATOR);
        }

        @Override
        public void requestBodyEnd(@NotNull Call call, long byteCount) {
            if (!enableLogging) {
                return;
            }
            long requestBodyEnd = Instant.now().toEpochMilli();
            timings.put("requestBodyEnd", requestBodyEnd);
            Long requestBodyStartMilli = timings.get("requestBodyStart");
            long requestBodyDuration = (requestBodyEnd - requestBodyStartMilli);
            logBuilder.append("== RequestBodyEnd => ")
                    .append("requestBodyStartTime: ").append(requestBodyStartMilli)
                    .append(" |requestBodyEndTime: ").append(requestBodyEnd)
                    .append(" |durationTime: ").append(requestBodyDuration)
                    .append(" |byteCount: ").append(byteCount)
                    .append(LINE_SEPARATOR);
        }

        @Override
        public void responseHeadersStart(@NotNull Call call) {
            if (!enableLogging) {
                return;
            }
            long epochMilli = Instant.now().toEpochMilli();
            timings.put("responseHeadersStart", epochMilli);
            logBuilder.append("== ResponseHeadersStart => ")
                    .append("responseHeadersStartTime: ").append(epochMilli)
                    .append(LINE_SEPARATOR);
        }

        @Override
        public void responseHeadersEnd(@NotNull Call call, @NotNull Response response) {
            if (!enableLogging) {
                return;
            }
            long responseHeadersEnd = Instant.now().toEpochMilli();
            timings.put("responseHeadersEnd", responseHeadersEnd);
            Long responseHeadersStartMilli = timings.get("responseHeadersStart");
            long responseHeadersDuration = (responseHeadersEnd - responseHeadersStartMilli);
            logBuilder.append("== ResponseHeadersEnd => ")
                    .append("responseHeadersStartTime: ").append(responseHeadersStartMilli)
                    .append(" |responseHeadersEndTime: ").append(responseHeadersEnd)
                    .append(" |durationTime: ").append(responseHeadersDuration)
                    .append(LINE_SEPARATOR);
        }

        @Override
        public void responseBodyStart(@NotNull Call call) {
            if (!enableLogging) {
                return;
            }
            long epochMilli = Instant.now().toEpochMilli();
            timings.put("responseBodyStart", epochMilli);
            logBuilder.append("== ResponseBodyStart => ")
                    .append("responseBodyStartTime: ").append(epochMilli)
                    .append(LINE_SEPARATOR);
        }

        @Override
        public void responseBodyEnd(@NotNull Call call, long byteCount) {
            if (!enableLogging) {
                return;
            }
            long responseBodyEnd = Instant.now().toEpochMilli();
            timings.put("responseBodyEnd", responseBodyEnd);
            Long responseBodyStartMilli = timings.get("responseBodyStart");
            long responseBodyDuration = (responseBodyEnd - responseBodyStartMilli);
            logBuilder.append("== ResponseBodyEnd => ")
                    .append("responseBodyStartTime: ").append(responseBodyStartMilli)
                    .append(" |responseBodyEndTime: ").append(responseBodyEnd)
                    .append(" |durationTime: ").append(responseBodyDuration)
                    .append(" |byteCount: ").append(byteCount)
                    .append(LINE_SEPARATOR);
        }

        @Override
        public void connectionAcquired(@NotNull Call call, @NotNull Connection connection) {
            if (!enableLogging) {
                return;
            }
            long epochMilli = Instant.now().toEpochMilli();
            timings.put("connectionAcquired", epochMilli);
            boolean isNewConnection = timings.containsKey("connectStart");
            logBuilder.append("== ConnectionAcquired => ")
                    .append("connectionAcquiredTime: ").append(epochMilli)
                    .append(" |isNewConnection: ").append(isNewConnection)
                    .append(" |route: ").append(connection.route().address().url())
                    .append(LINE_SEPARATOR);
        }

        @Override
        public void connectionReleased(@NotNull Call call, @NotNull Connection connection) {
            if (!enableLogging) {
                return;
            }
            long epochMilli = Instant.now().toEpochMilli();
            timings.put("connectionReleased", epochMilli);
            logBuilder.append("== ConnectionReleased => ")
                    .append("connectionReleasedTime: ").append(epochMilli)
                    .append(LINE_SEPARATOR);
        }

        @Override
        public void secureConnectStart(@NotNull Call call) {
            if (!enableLogging) {
                return;
            }
            long epochMilli = Instant.now().toEpochMilli();
            timings.put("secureConnectStart", epochMilli);
            logBuilder.append("== SecureConnectStart => ")
                    .append("secureConnectStartTime: ").append(epochMilli)
                    .append(LINE_SEPARATOR);
        }

        @Override
        public void secureConnectEnd(@NotNull Call call, @Nullable Handshake handshake) {
            if (!enableLogging) {
                return;
            }
            long secureConnectEnd = Instant.now().toEpochMilli();
            timings.put("secureConnectEnd", secureConnectEnd);
            Long secureConnectStartMilli = timings.getOrDefault("secureConnectStart", 0L);
            long secureConnectDuration = secureConnectEnd - secureConnectStartMilli;
            String protocol = handshake != null ? handshake.tlsVersion().toString() : "unknown";
            logBuilder.append("== SecureConnectEnd => ")
                    .append("secureConnectStartTime: ").append(secureConnectStartMilli)
                    .append(" |secureConnectEndTime: ").append(secureConnectEnd)
                    .append(" |durationTime: ").append(secureConnectDuration)
                    .append(" |tlsVersion: ").append(protocol)
                    .append(LINE_SEPARATOR);
        }
    }

    @Getter
    @ToString
    public static class TimeoutTag implements Serializable {
        @Serial
        private static final long serialVersionUID = 7095021872582086515L;

        private final int connectTimeoutMs;
        private final int readTimeoutMs;
        private final int writeTimeoutMs;
        private final int callTimeoutMs;

        public TimeoutTag(int connectTimeoutMs, int readTimeoutMs, int writeTimeoutMs, int callTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
            this.readTimeoutMs = readTimeoutMs;
            this.writeTimeoutMs = writeTimeoutMs;
            this.callTimeoutMs = callTimeoutMs;
        }
    }

    @Getter
    @ToString
    public static class RetryTag implements Serializable {
        @Serial
        private static final long serialVersionUID = 7095021872582086515L;

        private final int maxRetries;
        private final long retryDelayMillis;
        private final List<Class<? extends IOException>> retryableExceptions;

        public RetryTag(int maxRetries, long retryDelayMillis, List<Class<? extends IOException>> retryableExceptions) {
            this.maxRetries = maxRetries;
            this.retryDelayMillis = retryDelayMillis;
            this.retryableExceptions = retryableExceptions;
        }
    }

    @Getter
    @ToString
    @lombok.Builder
    public static class RequestExtTag implements Serializable {
        @Serial
        private static final long serialVersionUID = 7095021872582086515L;

        private String requestUniqueKey;

        private Long userId;
    }
}