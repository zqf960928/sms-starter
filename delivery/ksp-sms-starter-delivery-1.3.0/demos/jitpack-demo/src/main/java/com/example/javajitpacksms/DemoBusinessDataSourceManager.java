package com.example.javajitpacksms;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DemoBusinessDataSourceManager {

    private static final String UNRESOLVED_PREFIX = "SMS{";

    private final Environment environment;
    private final List<RetiredDataSource> retiredDataSources = new ArrayList<>();

    private volatile HikariDataSource activeDataSource;
    private volatile String activeUsername;
    private volatile String activePassword;
    private volatile String activeUrl;
    private volatile String activeFingerprint;
    private volatile String activePoolName;
    private volatile Instant lastAppliedAt;
    private volatile Instant lastCheckAt;
    private volatile Instant lastSuccessAt;
    private volatile Instant lastFailureAt;
    private volatile String lastSwitchTrigger;
    private volatile String lastFailure;
    private volatile long switchCount;

    public DemoBusinessDataSourceManager(Environment environment) {
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeOnReady() {
        reconcileLatestCredentials("startup", false);
    }

    @Scheduled(
            fixedDelayString = "${app.demo.business.monitorIntervalMs:5000}",
            initialDelayString = "${app.demo.business.initialDelayMs:3000}"
    )
    public void scheduledRefresh() {
        reconcileLatestCredentials("scheduled", false);
    }

    public synchronized Map<String, Object> ensureInitialized() {
        if (activeDataSource == null) {
            reconcileLatestCredentials("initialize", true);
        } else {
            cleanupRetiredDataSources();
        }
        return getStatus();
    }

    public synchronized Map<String, Object> applyLatestCredentials() {
        reconcileLatestCredentials("manual", true);
        return getStatus();
    }

    public synchronized Map<String, Object> getStatus() {
        cleanupRetiredDataSources();

        Map<String, Object> result = new LinkedHashMap<>();
        String latestUrl = environment.getProperty("spring.datasource.url");
        String latestUsername = environment.getProperty("spring.datasource.username");
        String latestPassword = environment.getProperty("spring.datasource.password");
        String latestFingerprint = fingerprint(latestUrl, latestUsername, latestPassword);

        result.put("monitorEnabled", true);
        result.put("monitorIntervalMs", getMonitorIntervalMs());
        result.put("retireDelayMs", getRetireDelayMs());
        result.put("businessPoolPresent", activeDataSource != null);
        result.put("businessPoolName", activePoolName);
        result.put("businessAppliedUsername", mask(activeUsername, 3));
        result.put("businessAppliedAt", formatInstant(lastAppliedAt));
        result.put("businessFingerprint", shorten(activeFingerprint));
        result.put("latestEnvUsername", mask(latestUsername, 3));
        result.put("latestEnvPassword", "***");
        result.put("latestEnvUrl", latestUrl);
        result.put("latestEnvFingerprint", shorten(latestFingerprint));
        result.put("credentialsResolved", isResolved(latestUsername) && isResolved(latestPassword));
        result.put("credentialsDrift", !same(activeFingerprint, latestFingerprint));
        result.put("lastCheckAt", formatInstant(lastCheckAt));
        result.put("lastSuccessAt", formatInstant(lastSuccessAt));
        result.put("lastFailureAt", formatInstant(lastFailureAt));
        result.put("lastSwitchTrigger", lastSwitchTrigger);
        result.put("lastFailure", lastFailure);
        result.put("switchCount", switchCount);
        result.put("retiredPoolCount", retiredDataSources.size());
        result.put("retiredPools", buildRetiredPoolView());

        if (activeDataSource == null) {
            return result;
        }

        try (Connection connection = activeDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            DatabaseMetaData metaData = connection.getMetaData();
            result.put("businessConnected", true);
            result.put("businessJdbcUrl", safeGetUrl(metaData));
            result.put("businessDbUser", mask(metaData.getUserName(), 3));
            result.put("businessDatabase", queryString(statement, "SELECT DATABASE()"));
            result.put("businessCurrentUser", mask(queryString(statement, "SELECT CURRENT_USER()"), 3));
            result.put("kspConfigCount", queryLong(statement,
                    "SELECT COUNT(*) FROM " + environment.getProperty("app.demo.tableName", "ksp_config")));
        } catch (Exception e) {
            result.put("businessConnected", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }

    @PreDestroy
    public synchronized void destroy() {
        if (activeDataSource != null) {
            activeDataSource.close();
            activeDataSource = null;
        }
        for (RetiredDataSource retired : retiredDataSources) {
            retired.dataSource.close();
        }
        retiredDataSources.clear();
    }

    private synchronized void reconcileLatestCredentials(String trigger, boolean failWhenNoActivePool) {
        lastCheckAt = Instant.now();
        cleanupRetiredDataSources();

        String url = environment.getProperty("spring.datasource.url");
        String driver = environment.getProperty("spring.datasource.driver-class-name");
        String username = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password");

        if (!isResolved(url) || !isResolved(driver) || !isResolved(username) || !isResolved(password)) {
            RuntimeException error = new RuntimeException("starter 尚未提供可用凭据，业务连接池保持当前状态");
            recordFailure(error);
            if (activeDataSource == null && failWhenNoActivePool) {
                throw error;
            }
            return;
        }

        String nextFingerprint = fingerprint(url, username, password);
        if (activeDataSource != null && same(activeFingerprint, nextFingerprint)) {
            lastSuccessAt = Instant.now();
            lastFailure = null;
            return;
        }

        String nextPoolName = "jitpack-demo-hikari-" + System.currentTimeMillis();
        HikariDataSource next = buildDataSource(nextPoolName, url, driver, username, password);

        try {
            testConnection(next);

            HikariDataSource previous = activeDataSource;
            String previousPoolName = activePoolName;
            String previousFingerprint = activeFingerprint;

            activeDataSource = next;
            activeUsername = username;
            activePassword = password;
            activeUrl = url;
            activeFingerprint = nextFingerprint;
            activePoolName = nextPoolName;
            lastAppliedAt = Instant.now();
            lastSuccessAt = lastAppliedAt;
            lastSwitchTrigger = trigger;
            lastFailure = null;
            switchCount++;

            if (previous != null) {
                retiredDataSources.add(new RetiredDataSource(
                        previous,
                        previousPoolName,
                        previousFingerprint,
                        Instant.now().plusMillis(getRetireDelayMs())
                ));
            }
        } catch (RuntimeException e) {
            next.close();
            recordFailure(e);
            if (activeDataSource == null && failWhenNoActivePool) {
                throw e;
            }
        }
    }

    private void recordFailure(Exception e) {
        lastFailureAt = Instant.now();
        lastFailure = rootMessage(e);
    }

    private void cleanupRetiredDataSources() {
        Instant now = Instant.now();
        Iterator<RetiredDataSource> iterator = retiredDataSources.iterator();
        while (iterator.hasNext()) {
            RetiredDataSource retired = iterator.next();
            if (!retired.retireAt.isAfter(now)) {
                retired.dataSource.close();
                iterator.remove();
            }
        }
    }

    private List<Map<String, Object>> buildRetiredPoolView() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (RetiredDataSource retired : retiredDataSources) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("poolName", retired.poolName);
            item.put("fingerprint", shorten(retired.fingerprint));
            item.put("retireAt", formatInstant(retired.retireAt));
            result.add(item);
        }
        return result;
    }

    private long getMonitorIntervalMs() {
        return environment.getProperty("app.demo.business.monitorIntervalMs", Long.class, 5000L);
    }

    private long getRetireDelayMs() {
        return environment.getProperty("app.demo.business.retireDelayMs", Long.class, 30000L);
    }

    private static HikariDataSource buildDataSource(
            String poolName,
            String url,
            String driver,
            String username,
            String password
    ) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName(driver);
        config.setPoolName(poolName);
        config.setMinimumIdle(1);
        config.setMaximumPoolSize(4);
        config.setConnectionTimeout(10000);
        config.setValidationTimeout(5000);
        config.setInitializationFailTimeout(10000);
        return new HikariDataSource(config);
    }

    private static void testConnection(HikariDataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
        } catch (Exception e) {
            throw new RuntimeException("业务连接池初始化失败", e);
        }
    }

    private static String queryString(Statement statement, String sql) throws Exception {
        try (ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static Long queryLong(Statement statement, String sql) throws Exception {
        try (ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : null;
        }
    }

    private static String safeGetUrl(DatabaseMetaData metaData) {
        try {
            return metaData.getURL();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String fingerprint(String url, String username, String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(String.valueOf(url).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(String.valueOf(username).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(String.valueOf(password).getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName() + ": " + current.getMessage();
    }

    private static boolean isResolved(String value) {
        return value != null && !value.trim().isEmpty() && !value.startsWith(UNRESOLVED_PREFIX);
    }

    private static String formatInstant(Instant value) {
        return value == null ? null : value.toString();
    }

    private static String shorten(String value) {
        if (value == null || value.length() <= 12) {
            return value;
        }
        return value.substring(0, 12);
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

    private static boolean same(String left, String right) {
        if (left == null && right == null) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.equals(right);
    }

    private static final class RetiredDataSource {
        private final HikariDataSource dataSource;
        private final String poolName;
        private final String fingerprint;
        private final Instant retireAt;

        private RetiredDataSource(
                HikariDataSource dataSource,
                String poolName,
                String fingerprint,
                Instant retireAt
        ) {
            this.dataSource = dataSource;
            this.poolName = poolName;
            this.fingerprint = fingerprint;
            this.retireAt = retireAt;
        }
    }
}
