package com.andang.starter;

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
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class KspSmsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "kspSmsPropertySource";
    private static final int DECRYPT_TIMEOUT_MILLIS = 10000;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private KspSmsProperties properties;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        properties = new KspSmsProperties();
        loadPropertiesFromEnvironment(environment, properties);

        if (!properties.isEnabled()) {
            log.info("KSP SMS starter is disabled");
            return;
        }

        Map<String, Object> processedProperties = new HashMap<>();
        processProperties(environment, processedProperties);

        if (!processedProperties.isEmpty()) {
            PropertySource<?> propertySource = new MapPropertySource(PROPERTY_SOURCE_NAME, processedProperties);
            environment.getPropertySources().addFirst(propertySource);
        }
        log.info("KSP SMS startup decryption completed, resolved {} encrypted properties", processedProperties.size());
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
        if (properties.getRefresh() == null) {
            properties.setRefresh(new KspSmsProperties.Refresh());
        }
        KspSmsProperties.Refresh refresh = properties.getRefresh();
        refresh.setEnabled(Boolean.parseBoolean(environment.getProperty("ksp.sms.refresh.enabled", String.valueOf(refresh.isEnabled()))));
        refresh.setIntervalSeconds(Long.parseLong(environment.getProperty("ksp.sms.refresh.intervalSeconds", String.valueOf(refresh.getIntervalSeconds()))));
        refresh.setLeadTimeSeconds(Long.parseLong(environment.getProperty("ksp.sms.refresh.leadTimeSeconds", String.valueOf(refresh.getLeadTimeSeconds()))));
        refresh.setRefreshStatic(Boolean.parseBoolean(environment.getProperty("ksp.sms.refresh.refreshStatic", String.valueOf(refresh.isRefreshStatic()))));
        refresh.setDatasourceEnabled(Boolean.parseBoolean(environment.getProperty("ksp.sms.refresh.datasourceEnabled", String.valueOf(refresh.isDatasourceEnabled()))));
        refresh.setHikariEnabled(Boolean.parseBoolean(environment.getProperty("ksp.sms.refresh.hikariEnabled", String.valueOf(refresh.isHikariEnabled()))));
        refresh.setValidateOnRotate(Boolean.parseBoolean(environment.getProperty("ksp.sms.refresh.validateOnRotate", String.valueOf(refresh.isValidateOnRotate()))));
        refresh.setValidationQuery(environment.getProperty("ksp.sms.refresh.validationQuery", refresh.getValidationQuery()));
        refresh.setEvictDelayMs(Long.parseLong(environment.getProperty("ksp.sms.refresh.evictDelayMs", String.valueOf(refresh.getEvictDelayMs()))));
        refresh.setRenewTtlSeconds(Long.parseLong(environment.getProperty("ksp.sms.refresh.renewTtlSeconds", String.valueOf(refresh.getRenewTtlSeconds()))));
        refresh.setMaxRenewFailures(Integer.parseInt(environment.getProperty("ksp.sms.refresh.maxRenewFailures", String.valueOf(refresh.getMaxRenewFailures()))));
        refresh.setLifecycleDriven(Boolean.parseBoolean(environment.getProperty("ksp.sms.refresh.lifecycleDriven", String.valueOf(refresh.isLifecycleDriven()))));
    }

    private void processProperties(ConfigurableEnvironment environment, Map<String, Object> processedProperties) {
        String cipherPrefix = properties.getCipherPrefix();
        for (PropertySource<?> propertySource : environment.getPropertySources()) {
            try {
                if (propertySource instanceof MapPropertySource) {
                    MapPropertySource mapPropertySource = (MapPropertySource) propertySource;
                    Map<String, Object> sourceMap = mapPropertySource.getSource();
                    for (Map.Entry<String, Object> entry : sourceMap.entrySet()) {
                        String key = entry.getKey();
                        Object value = entry.getValue();
                        
                        String stringValue = null;
                        if (value instanceof String) {
                            stringValue = (String) value;
                        } else if (value instanceof CharSequence) {
                            // 处理OriginTrackedValue等CharSequence类型
                            stringValue = value.toString();
                        }
                        
                        if (stringValue != null) {
                            if (looksLikeCipher(stringValue, cipherPrefix)) {
                                String content = stringValue.substring(cipherPrefix.length(), stringValue.length() - 1);
                                String label;
                                String jsonKey = properties.getJsonKey();

                                if (content.contains(":")) {
                                    String[] parts = content.split(":", 2);
                                    label = parts[0];
                                    jsonKey = parts[1];
                                } else {
                                    label = content;
                                }

                                String plainText = decryptProperty(label, jsonKey);
                                processedProperties.put(key, plainText);
                            }
                        } else if (value instanceof Map) {
                            processNestedMap(key, (Map<?, ?>) value, cipherPrefix, processedProperties);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("KSP SMS skipped one property source during startup decryption");
                log.debug("KSP SMS property source processing failure", e);
            }
        }
    }

    /**
     * 处理嵌套的Map属性，递归检查所有属性值
     */
    private void processNestedMap(String parentKey, Map<?, ?> map, String cipherPrefix, Map<String, Object> processedProperties) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = parentKey + "." + entry.getKey().toString();
            Object value = entry.getValue();
            
            String stringValue = null;
            if (value instanceof String) {
                stringValue = (String) value;
            } else if (value instanceof CharSequence) {
                // 处理OriginTrackedValue等CharSequence类型
                stringValue = value.toString();
            }
            
            if (stringValue != null) {
                if (looksLikeCipher(stringValue, cipherPrefix)) {
                    String content = stringValue.substring(cipherPrefix.length(), stringValue.length() - 1);
                    String label;
                    String jsonKey = properties.getJsonKey();

                    if (content.contains(":")) {
                        String[] parts = content.split(":", 2);
                        label = parts[0];
                        jsonKey = parts[1];
                    } else {
                        label = content;
                    }

                    String plainText = decryptProperty(label, jsonKey);
                    processedProperties.put(key, plainText);
                }
            } else if (value instanceof Map) {
                processNestedMap(key, (Map<?, ?>) value, cipherPrefix, processedProperties);
            }
        }
    }

    private String decryptProperty(String label, String jsonKey) {
        final AtomicReference<String> result = new AtomicReference<>();
        final AtomicReference<Exception> exception = new AtomicReference<>();
        
        Thread decryptThread = new Thread(() -> {
            try {
                CredentialClient client = new CredentialClient(
                        properties.getUrl(),
                        properties.getDomain(),
                        properties.getAppKey(),
                        properties.getAppSecret(),
                        properties.getVersion()
                );
                KspSmsStartupCredentialResolver resolver = KspSmsStartupCredentialResolver.fromClient(client, properties);
                String decryptedContent = resolver.resolveDecryptedContent(label);

                try {
                    JsonNode jsonNode = objectMapper.readTree(decryptedContent);
                    if (jsonNode.has(jsonKey)) {
                        result.set(jsonNode.get(jsonKey).asText());
                    } else {
                        result.set(decryptedContent);
                    }
                } catch (Exception e) {
                    result.set(decryptedContent);
                }
            } catch (Exception e) {
                exception.set(e);
            }
        }, "ksp-sms-startup-decrypt");
        decryptThread.setDaemon(true);
        
        decryptThread.start();
        
        try {
            decryptThread.join(DECRYPT_TIMEOUT_MILLIS);
            
            if (decryptThread.isAlive()) {
                log.warn("KSP SMS startup decryption timed out; keeping original placeholder");
                return toCipherPlaceholder(label, jsonKey);
            } else if (exception.get() != null) {
                log.warn("KSP SMS startup decryption failed; keeping original placeholder");
                log.debug("KSP SMS startup decryption failure", exception.get());
                return toCipherPlaceholder(label, jsonKey);
            } else {
                return result.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("KSP SMS startup decryption interrupted; keeping original placeholder");
            return toCipherPlaceholder(label, jsonKey);
        }
    }

    private boolean looksLikeCipher(String value, String cipherPrefix) {
        return value.startsWith(cipherPrefix) && value.endsWith("}");
    }

    private String toCipherPlaceholder(String label, String jsonKey) {
        return "SMS{" + label + (jsonKey != null && !jsonKey.equals(properties.getJsonKey()) ? ":" + jsonKey : "") + "}";
    }

    @Override
    public int getOrder() {
        // 设置一个较低的优先级，确保在ConfigDataEnvironmentPostProcessor之后执行
        // ConfigDataEnvironmentPostProcessor的优先级是Ordered.HIGHEST_PRECEDENCE + 10
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
