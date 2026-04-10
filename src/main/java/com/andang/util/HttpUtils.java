package com.andang.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLContextBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import javax.net.ssl.SSLContext;
import java.security.cert.X509Certificate;
import java.util.Map;

@Slf4j
public class HttpUtils {
    // 超时配置（毫秒）
    private static final int CONNECT_TIMEOUT = 3000;
    private static final int READ_TIMEOUT = 5000;
    // JSON 解析器（线程安全）
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建信任所有证书的 HttpClient（仅测试用！生产环境需验证证书）
     */
    public static CloseableHttpClient createTrustAllClient() {
        try {
            SSLContext sslContext = new SSLContextBuilder()
                    .loadTrustMaterial(null, (X509Certificate[] chain, String authType) -> true)
                    .build();

            RequestConfig config = RequestConfig.custom()
                    .setConnectTimeout(CONNECT_TIMEOUT)
                    .setSocketTimeout(READ_TIMEOUT)
                    .build();

            return HttpClients.custom()
                    .setSSLContext(sslContext)
                    .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                    .setDefaultRequestConfig(config)
                    .build();
        } catch (Exception e) {
            log.error("创建 HttpClient 失败", e);
            throw new RuntimeException("创建 HttpClient 失败", e);
        }
    }

    /**
     * 发送 JSON 格式的 POST 请求
     * @param url 请求地址
     * @param headers 请求头
     * @param params 请求参数（自动转 JSON）
     * @param clazz 响应类型
     * @return 解析后的响应对象
     */
    public static <T> T postJson(String url, Map<String, String> headers, Object params, Class<T> clazz) {
        if (url == null || clazz == null) throw new IllegalArgumentException("URL 和响应类型不能为空");

        try (CloseableHttpClient client = createTrustAllClient();
             CloseableHttpResponse resp = client.execute(buildPost(url, headers, params))) {

            // 验证状态码
            int status = resp.getStatusLine().getStatusCode();
            if (status < 200 || status >= 300) {
                throw new RuntimeException(String.format("HTTP 请求失败，状态码：%d", status));
            }

            // 解析响应体
            HttpEntity entity = resp.getEntity();
            if (entity == null) throw new RuntimeException("响应体为空");
            String respBody = EntityUtils.toString(entity, "UTF-8");
            log.debug("HTTP 响应：{}", respBody);

            return objectMapper.readValue(respBody, clazz);
        } catch (Exception e) {
            log.error("发送 POST 请求失败，URL：{}", url, e);
            throw new RuntimeException(String.format("发送 POST 请求失败，URL：%s", url), e);
        }
    }

    /**
     * 构建 HttpPost 对象（设置头和请求体）
     */
    private static HttpPost buildPost(String url, Map<String, String> headers, Object params) {
        HttpPost post = new HttpPost(url);

        // 设置请求头
        if (headers != null) headers.forEach(post::setHeader);
        // 默认 Content-Type
        if (!post.containsHeader("Content-Type")) {
            post.setHeader("Content-Type", "application/json;charset=UTF-8");
        }

        // 设置请求体（转 JSON）
        try {
            String json = objectMapper.writeValueAsString(params);
            log.debug("HTTP 请求体：{}", json);
            post.setEntity(new StringEntity(json, "UTF-8"));
        } catch (Exception e) {
            throw new RuntimeException("参数转 JSON 失败", e);
        }

        return post;
    }
}
