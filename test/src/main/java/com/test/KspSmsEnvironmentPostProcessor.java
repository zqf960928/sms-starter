package com.test;

import com.andang.CredentialClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class KspSmsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "kspSmsPropertySource";
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private KspSmsProperties properties;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // 使用System.out.println替代log.info，因为在环境初始化阶段日志系统可能还未完全就绪
        System.out.println("KSP SMS EnvironmentPostProcessor started");
        
        properties = new KspSmsProperties();
        loadPropertiesFromEnvironment(environment, properties);

        System.out.println("KSP SMS properties loaded: url=" + properties.getUrl() + ", domain=" + properties.getDomain() + ", appKey=" + properties.getAppKey() + ", enabled=" + properties.isEnabled() + ", cipherPrefix=" + properties.getCipherPrefix());

        if (!properties.isEnabled()) {
            System.out.println("KSP SMS Starter is disabled");
            return;
        }

        System.out.println("KSP SMS Starter is enabled, processing encrypted properties");

        // 直接检查几个关键属性
        String dbUsername = environment.getProperty("spring.datasource.username");
        String dbPassword = environment.getProperty("spring.datasource.password");
        String apiKey = environment.getProperty("app.api.key");

        System.out.println("Direct property checks:");
        System.out.println("spring.datasource.username = " + dbUsername);
        System.out.println("spring.datasource.password = " + dbPassword);
        System.out.println("app.api.key = " + apiKey);

        Map<String, Object> processedProperties = new HashMap<>();
        System.out.println("Calling processProperties method");
        processProperties(environment, processedProperties);
        System.out.println("processProperties method returned with " + processedProperties.size() + " processed properties");

        if (!processedProperties.isEmpty()) {
            PropertySource<?> propertySource = new MapPropertySource(PROPERTY_SOURCE_NAME, processedProperties);
            environment.getPropertySources().addFirst(propertySource);
            System.out.println("Added KSP SMS property source with " + processedProperties.size() + " processed properties");
        } else {
            System.out.println("No encrypted properties found");
        }
        System.out.println("KSP SMS EnvironmentPostProcessor finished");
    }

    private void loadPropertiesFromEnvironment(ConfigurableEnvironment environment, KspSmsProperties properties) {
        properties.setUrl(environment.getProperty("ksp.sms.url", properties.getUrl()));
        properties.setDomain(Integer.parseInt(environment.getProperty("ksp.sms.domain", String.valueOf(properties.getDomain()))));
        properties.setAppKey(environment.getProperty("ksp.sms.appKey", properties.getAppKey()));
        properties.setAppSecret(environment.getProperty("ksp.sms.appSecret", properties.getAppSecret()));
        properties.setVersion(environment.getProperty("ksp.sms.version", properties.getVersion()));
        properties.setEnabled(Boolean.parseBoolean(environment.getProperty("ksp.sms.enabled", String.valueOf(properties.isEnabled()))));
        properties.setCipherPrefix(environment.getProperty("ksp.sms.cipherPrefix", properties.getCipherPrefix()));
        properties.setJsonKey(environment.getProperty("ksp.sms.jsonKey", properties.getJsonKey()));
    }

    private void processProperties(ConfigurableEnvironment environment, Map<String, Object> processedProperties) {
        String cipherPrefix = properties.getCipherPrefix();

        // 直接检查所有可能的属性，无论属性源的类型
        String[] propertyKeys = {
            "spring.datasource.username",
            "spring.datasource.password",
            "app.api.key"
        };

        for (String key : propertyKeys) {
            try {
                Object value = environment.getProperty(key);
                if (value instanceof String) {
                    String stringValue = (String) value;
                    System.out.println("Checking property: " + key + " = " + stringValue);
                    
                    if (stringValue.startsWith(cipherPrefix) && stringValue.endsWith("}")) {
                        // 处理密文，格式为 SMS{密钥ID} 或 SMS{密钥ID:jsonKey}
                        String content = stringValue.substring(cipherPrefix.length(), stringValue.length() - 1);
                        String label;
                        String jsonKey = properties.getJsonKey();

                        // 检查是否包含冒号，用于指定JSON中的key
                        if (content.contains(":")) {
                            String[] parts = content.split(":");
                            label = parts[0];
                            jsonKey = parts[1];
                        } else {
                            label = content;
                        }

                        String plainText = decryptProperty(label, jsonKey);
                        processedProperties.put(key, plainText);
                        // 不输出解密后的值，避免敏感信息泄露
                        System.out.println("Decrypted property: " + key + ", value: [REDACTED]");
                    }
                }
            } catch (Exception e) {
                System.out.println("Failed to process property: " + key + ", error: " + e.getMessage());
                e.printStackTrace();
                // 继续处理下一个属性
            }
        }
    }

    private String decryptProperty(String label, String jsonKey) {
        System.out.println("Starting decryption for label: " + label + ", jsonKey: " + jsonKey);
        
        // 创建一个线程来执行解密操作，避免阻塞主线程
        final String[] result = new String[1];
        final Exception[] exception = new Exception[1];
        
        Thread decryptThread = new Thread(() -> {
            try {
                CredentialClient client = new CredentialClient(
                        properties.getUrl(),
                        properties.getDomain(),
                        properties.getAppKey(),
                        properties.getAppSecret(),
                        properties.getVersion()
                );
                System.out.println("Created CredentialClient, starting login");
                client.login();
                System.out.println("Login successful, getting credential");
                String decryptedContent = client.getCredential(label);
                // 不输出解密内容，避免敏感信息泄露
                System.out.println("Got decrypted content: [REDACTED]");

                try {
                    JsonNode jsonNode = objectMapper.readTree(decryptedContent);
                    System.out.println("Parsed JSON successfully");
                    if (jsonNode.has(jsonKey)) {
                        String extractedValue = jsonNode.get(jsonKey).asText();
                        // 不输出提取的值，避免敏感信息泄露
                        System.out.println("Extracted value from JSON: " + jsonKey + " -> [REDACTED]");
                        result[0] = extractedValue;
                    } else {
                        System.out.println("JSON does not contain key: " + jsonKey + ", returning full JSON");
                        result[0] = decryptedContent;
                    }
                } catch (Exception e) {
                    System.out.println("Content is not JSON (static credential), using original content");
                    result[0] = decryptedContent;
                }
            } catch (Exception e) {
                exception[0] = e;
            }
        });
        
        decryptThread.start();
        
        // 等待解密线程完成，最多等待10秒
        try {
            decryptThread.join(10000);
            
            if (decryptThread.isAlive()) {
                // 超时，返回原始密文
                System.out.println("Decryption timeout after 10 seconds");
                return "SMS{" + label + (jsonKey != null && !jsonKey.equals(properties.getJsonKey()) ? ":" + jsonKey : "") + "}";
            } else if (exception[0] != null) {
                // 发生异常，返回原始密文
                System.out.println("Failed to decrypt property: " + exception[0].getMessage());
                exception[0].printStackTrace();
                return "SMS{" + label + (jsonKey != null && !jsonKey.equals(properties.getJsonKey()) ? ":" + jsonKey : "") + "}";
            } else {
                // 解密成功，返回解密结果
                return result[0];
            }
        } catch (InterruptedException e) {
            System.out.println("Decryption thread interrupted: " + e.getMessage());
            return "SMS{" + label + (jsonKey != null && !jsonKey.equals(properties.getJsonKey()) ? ":" + jsonKey : "") + "}";
        }
    }

    @Override
    public int getOrder() {
        // 设置一个较低的优先级，确保在ConfigDataEnvironmentPostProcessor之后执行
        // ConfigDataEnvironmentPostProcessor的优先级是Ordered.HIGHEST_PRECEDENCE + 10
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}