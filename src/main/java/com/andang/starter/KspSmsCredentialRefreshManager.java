package com.andang.starter;

import com.andang.CredentialClient;
import com.andang.entity.CredentialResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.ConfigurableEnvironment;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class KspSmsCredentialRefreshManager implements SmartLifecycle, DisposableBean {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ConfigurableEnvironment environment;
    private final KspSmsProperties properties;
    private final ObjectProvider<DataSource> dataSourceProvider;

    private final Object lifecycleMonitor = new Object();
    private volatile boolean running;
    private ScheduledExecutorService executor;

    private KspSmsRuntimePropertyUpdater runtimePropertyUpdater;
    private List<KspSmsCipherReference> refs;

    private final Map<String, CachedLabel> cache = new ConcurrentHashMap<>();

    private volatile String currentDatasourceUsername;
    private volatile String currentDatasourcePassword;

    public KspSmsCredentialRefreshManager(
            ConfigurableEnvironment environment,
            KspSmsProperties properties,
            ObjectProvider<DataSource> dataSourceProvider
    ) {
        this.environment = environment;
        this.properties = properties;
        this.dataSourceProvider = dataSourceProvider;
    }

    @Override
    public void start() {
        synchronized (lifecycleMonitor) {
            if (running) {
                return;
            }
            if (!properties.isEnabled() || properties.getRefresh() == null || !properties.getRefresh().isEnabled()) {
                return;
            }

            this.runtimePropertyUpdater = new KspSmsRuntimePropertyUpdater(environment);
            this.refs = KspSmsCipherScanner.scan(environment, properties);
            if (refs == null || refs.isEmpty()) {
                return;
            }

            this.currentDatasourceUsername = environment.getProperty("spring.datasource.username");
            this.currentDatasourcePassword = environment.getProperty("spring.datasource.password");

            this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ksp-sms-refresh");
                t.setDaemon(true);
                return t;
            });

            long intervalSeconds = Math.max(5L, properties.getRefresh().getIntervalSeconds());
            this.executor.scheduleWithFixedDelay(this::safeTick, 0L, intervalSeconds, TimeUnit.SECONDS);
            this.running = true;
        }
    }

    private void safeTick() {
        try {
            tick();
        } catch (Exception e) {
            log.warn("KSP SMS refresh tick failed: {}", e.getMessage());
        }
    }

    private void tick() {
        long nowEpochSeconds = System.currentTimeMillis() / 1000;
        long leadTimeSeconds = Math.max(0L, properties.getRefresh().getLeadTimeSeconds());

        Map<String, List<KspSmsCipherReference>> labelToRefs = new HashMap<>();
        for (KspSmsCipherReference ref : refs) {
            labelToRefs.computeIfAbsent(ref.getLabel(), k -> new java.util.ArrayList<>()).add(ref);
        }

        Map<String, Object> updatedProperties = new HashMap<>();

        for (Map.Entry<String, List<KspSmsCipherReference>> entry : labelToRefs.entrySet()) {
            String label = entry.getKey();
            List<KspSmsCipherReference> references = entry.getValue();

            CachedLabel cached = cache.get(label);
            if (cached != null && cached.getExpireTime() <= 0L && !properties.getRefresh().isRefreshStatic()) {
                continue;
            }

            boolean shouldRefresh = cached == null
                    || cached.getExpireTime() <= 0L
                    || (cached.getExpireTime() - nowEpochSeconds) <= leadTimeSeconds;
            if (!shouldRefresh) {
                continue;
            }

            CredentialResult result = fetchCredential(label);
            if (result == null || result.getContent() == null) {
                continue;
            }

            cache.put(label, new CachedLabel(result.getDbType(), result.getExpireTime(), result.getContent()));

            JsonNode json = null;
            try {
                json = objectMapper.readTree(result.getContent());
            } catch (Exception ignored) {
            }

            for (KspSmsCipherReference ref : references) {
                String value = result.getContent();
                if (json != null && ref.getJsonKey() != null && json.has(ref.getJsonKey())) {
                    value = json.get(ref.getJsonKey()).asText();
                }
                updatedProperties.put(ref.getPropertyKey(), value);
            }
        }

        if (!updatedProperties.isEmpty()) {
            runtimePropertyUpdater.putAll(updatedProperties);
            maybeRefreshDatasource(updatedProperties);
        }
    }

    private CredentialResult fetchCredential(String label) {
        CredentialClient client = new CredentialClient(
                properties.getUrl(),
                properties.getDomain(),
                properties.getAppKey(),
                properties.getAppSecret(),
                properties.getVersion()
        );
        client.login();
        return client.getCredentialResult(label);
    }

    private void maybeRefreshDatasource(Map<String, Object> updatedProperties) {
        if (!properties.getRefresh().isDatasourceEnabled()) {
            return;
        }

        String newUser = updatedProperties.containsKey("spring.datasource.username")
                ? String.valueOf(updatedProperties.get("spring.datasource.username"))
                : currentDatasourceUsername;
        String newPass = updatedProperties.containsKey("spring.datasource.password")
                ? String.valueOf(updatedProperties.get("spring.datasource.password"))
                : currentDatasourcePassword;

        if (newUser == null || newPass == null) {
            return;
        }
        if (newUser.equals(currentDatasourceUsername) && newPass.equals(currentDatasourcePassword)) {
            return;
        }

        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            currentDatasourceUsername = newUser;
            currentDatasourcePassword = newPass;
            return;
        }

        if (!properties.getRefresh().isHikariEnabled()) {
            currentDatasourceUsername = newUser;
            currentDatasourcePassword = newPass;
            return;
        }

        KspSmsHikariRefresher refresher = new KspSmsHikariRefresher(
                dataSource,
                properties.getRefresh().isValidateOnRotate(),
                properties.getRefresh().getValidationQuery(),
                properties.getRefresh().getEvictDelayMs()
        );
        if (!refresher.supports()) {
            currentDatasourceUsername = newUser;
            currentDatasourcePassword = newPass;
            return;
        }

        String oldUser = currentDatasourceUsername;
        String oldPass = currentDatasourcePassword;
        refresher.applyWithRollback(newUser, newPass, oldUser, oldPass);
        currentDatasourceUsername = newUser;
        currentDatasourcePassword = newPass;
        log.info("KSP SMS datasource credentials updated");
    }

    @Override
    public void stop() {
        synchronized (lifecycleMonitor) {
            running = false;
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void destroy() {
        stop();
    }

    static class CachedLabel {
        private final int dbType;
        private final long expireTime;
        private final String content;

        CachedLabel(int dbType, long expireTime, String content) {
            this.dbType = dbType;
            this.expireTime = expireTime;
            this.content = content;
        }

        long getExpireTime() {
            return expireTime;
        }
    }
}
