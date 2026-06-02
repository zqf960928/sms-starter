package com.andang.starter;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class KspSmsCipherScanner {
    static List<KspSmsCipherReference> scan(ConfigurableEnvironment environment, KspSmsProperties properties) {
        String cipherPrefix = properties.getCipherPrefix();
        List<KspSmsCipherReference> refs = new ArrayList<>();

        for (PropertySource<?> propertySource : environment.getPropertySources()) {
            if (!(propertySource instanceof MapPropertySource)) {
                continue;
            }
            if ("kspSmsPropertySource".equals(propertySource.getName()) || "kspSmsRuntimePropertySource".equals(propertySource.getName())) {
                continue;
            }

            Map<String, Object> source = ((MapPropertySource) propertySource).getSource();
            for (Map.Entry<String, Object> entry : source.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                String stringValue = null;
                if (value instanceof String) {
                    stringValue = (String) value;
                } else if (value instanceof CharSequence) {
                    stringValue = value.toString();
                }

                if (stringValue == null) {
                    continue;
                }
                if (!stringValue.startsWith(cipherPrefix) || !stringValue.endsWith("}")) {
                    continue;
                }

                String content = stringValue.substring(cipherPrefix.length(), stringValue.length() - 1);
                String label;
                String jsonKey = properties.getJsonKey();
                int idx = content.indexOf(':');
                if (idx >= 0) {
                    label = content.substring(0, idx);
                    jsonKey = content.substring(idx + 1);
                } else {
                    label = content;
                }
                if (label == null || label.isEmpty()) {
                    continue;
                }
                refs.add(new KspSmsCipherReference(key, label, jsonKey));
            }
        }
        return refs;
    }
}
