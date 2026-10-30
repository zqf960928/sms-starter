package com.example.javasmsadpter;

import com.andang.starter.KspSmsCredentialRefreshManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/demo")
public class SmsDebugController {

    private final Environment environment;
    private final ApplicationContext applicationContext;
    private final DemoBusinessDataSourceManager businessDataSourceManager;

    @Value("${spring.datasource.username}")
    private String dbUsernameSnapshot;

    @Value("${spring.datasource.password}")
    private String dbPasswordSnapshot;

    @Value("${app.demo.dbUserMirror}")
    private String dbUserMirrorSnapshot;

    @Value("${app.demo.dbPassMirror}")
    private String dbPassMirrorSnapshot;

    public SmsDebugController(
            Environment environment,
            ApplicationContext applicationContext,
            DemoBusinessDataSourceManager businessDataSourceManager
    ) {
        this.environment = environment;
        this.applicationContext = applicationContext;
        this.businessDataSourceManager = businessDataSourceManager;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("app", "javasmsadpter");
        return result;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dbUsernameSnapshot", mask(dbUsernameSnapshot, 3));
        result.put("dbPasswordSnapshot", "***");
        result.put("dbUserMirrorSnapshot", mask(dbUserMirrorSnapshot, 3));
        result.put("dbPassMirrorSnapshot", "***");
        result.put("dbUrl", environment.getProperty("spring.datasource.url"));

        result.put("dbUsernameCurrentEnv", mask(environment.getProperty("spring.datasource.username"), 3));
        result.put("dbPasswordCurrentEnv", "***");
        result.put("dbUserMirrorCurrentEnv", mask(environment.getProperty("app.demo.dbUserMirror"), 3));
        result.put("dbPassMirrorCurrentEnv", "***");
        return result;
    }

    @GetMapping("/refresh/status")
    public Map<String, Object> refreshStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            KspSmsCredentialRefreshManager manager = applicationContext.getBean(KspSmsCredentialRefreshManager.class);
            result.put("beanPresent", true);

            Field runningField = KspSmsCredentialRefreshManager.class.getDeclaredField("running");
            runningField.setAccessible(true);
            result.put("running", runningField.getBoolean(manager));

            Field cacheField = KspSmsCredentialRefreshManager.class.getDeclaredField("cache");
            cacheField.setAccessible(true);
            ConcurrentHashMap<?, ?> cache = (ConcurrentHashMap<?, ?>) cacheField.get(manager);
            result.put("cacheSize", cache.size());

            Map<String, Object> cacheView = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : cache.entrySet()) {
                cacheView.put(String.valueOf(entry.getKey()), readCacheObject(entry.getValue()));
            }
            result.put("cache", cacheView);
        } catch (Exception e) {
            result.put("beanPresent", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return result;
    }

    @GetMapping("/refresh/run-once")
    public Map<String, Object> runOnce() {
        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try {
            KspSmsCredentialRefreshManager manager = applicationContext.getBean(KspSmsCredentialRefreshManager.class);
            Method safeTick = KspSmsCredentialRefreshManager.class.getDeclaredMethod("safeTick");
            safeTick.setAccessible(true);
            safeTick.invoke(manager);

            result.put("invoked", true);
            result.put("tookMs", System.currentTimeMillis() - start);
            result.put("next", "/demo/refresh/status");
        } catch (Exception e) {
            result.put("invoked", false);
            result.put("tookMs", System.currentTimeMillis() - start);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            if (e.getCause() != null) {
                result.put("cause", e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage());
            }
        }
        return result;
    }

    @GetMapping("/db/ping")
    public Map<String, Object> dbPing() {
        return businessDataSourceManager.ensureInitialized();
    }

    @GetMapping("/business/status")
    public Map<String, Object> businessStatus() {
        return businessDataSourceManager.getStatus();
    }

    @GetMapping("/business/sync")
    public Map<String, Object> businessSync() {
        return businessDataSourceManager.applyLatestCredentials();
    }

    @GetMapping("/business/check-now")
    public Map<String, Object> businessCheckNow() {
        return businessDataSourceManager.triggerAutoCheckNow();
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("config", config());
        result.put("refresh", refreshStatus());
        result.put("business", businessDataSourceManager.getStatus());
        return result;
    }

    private static Map<String, Object> readCacheObject(Object cacheObject) throws IllegalAccessException {
        Map<String, Object> values = new LinkedHashMap<>();
        if (cacheObject == null) {
            return values;
        }
        for (Field field : cacheObject.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            Object value = field.get(cacheObject);
            if ("content".equals(field.getName())) {
                values.put(field.getName(), value == null ? null : "***");
            } else {
                values.put(field.getName(), value);
            }
        }
        return values;
    }

    private static String mask(String value, int keepHead) {
        if (value == null) {
            return null;
        }
        if (value.length() <= keepHead) {
            return value + "***";
        }
        return value.substring(0, keepHead) + "***(len=" + value.length() + ")";
    }
}
