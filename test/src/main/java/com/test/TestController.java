package com.test;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class TestController {

    @Value("${spring.datasource.username}")
    private String dbUsername;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    @Value("${app.api.key}")
    private String apiKey;

    // 自动注入解密后的API密钥（新添加的属性，用于测试自动扫描功能）
    @Value("${app.api.secret}")
    private String apiSecret;
    
    // 自动注入解密后的API令牌（新添加的属性，用于测试自动扫描功能）
    @Value("${app.api.token}")
    private String apiToken;
    
    // 自动注入解密后的测试属性（新添加的属性，用于测试自动扫描功能）
    @Value("${app.test.aaa}")
    private String testAaa;
    
    @Value("${app.test.bbb}")
    private String testBbb;
    
    @Value("${app.test.ccc}")
    private String testCcc;

    @GetMapping("/test")
    public Map<String, String> test() {
        Map<String, String> result = new HashMap<>();
        result.put("dbUsername", dbUsername);
        result.put("dbPassword", dbPassword);
        result.put("apiKey", apiKey);
        result.put("apiSecret", apiSecret);
        result.put("apiToken", apiToken);
        result.put("testAaa", testAaa);
        result.put("testBbb", testBbb);
        result.put("testCcc", testCcc);
        return result;
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}