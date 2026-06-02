package com.andang.starter;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class KspSmsRuntimePropertyUpdater {
    static final String PROPERTY_SOURCE_NAME = "kspSmsRuntimePropertySource";

    private final Map<String, Object> backingMap = new ConcurrentHashMap<>();
    private final MapPropertySource propertySource = new MapPropertySource(PROPERTY_SOURCE_NAME, backingMap);

    KspSmsRuntimePropertyUpdater(ConfigurableEnvironment environment) {
        if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            environment.getPropertySources().remove(PROPERTY_SOURCE_NAME);
        }
        environment.getPropertySources().addFirst(propertySource);
    }

    void putAll(Map<String, ?> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        backingMap.putAll(values);
    }
}
