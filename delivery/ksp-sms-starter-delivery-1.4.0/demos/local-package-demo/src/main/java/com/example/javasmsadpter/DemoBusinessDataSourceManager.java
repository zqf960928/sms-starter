package com.example.javasmsadpter;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class DemoBusinessDataSourceManager {

    private final Environment environment;
    private volatile HikariDataSource dataSource;
    private volatile String appliedUsername;
    private volatile String appliedPassword;
    private volatile Instant lastAppliedAt;
    private volatile Instant lastObservedAt;
    private volatile Instant lastAutoSyncAt;
    private volatile String lastAutoSyncResult;

    public DemoBusinessDataSourceManager(Environment environment) {
        this.environment = environment;
    }

    public synchronized Map<String, Object> ensureInitialized() {
        if (dataSource == null) {
            applyLatestCredentials();
        }
        return getStatus();
    }

    public synchronized Map<String, Object> applyLatestCredentials() {
        String url = environment.getProperty("spring.datasource.url");
        String driver = environment.getProperty("spring.datasource.driver-class-name");
        String username = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password");

        HikariDataSource next = buildDataSource(url, driver, username, password);
        testConnection(next);

        HikariDataSource previous = this.dataSource;
        this.dataSource = next;
        this.appliedUsername = username;
        this.appliedPassword = password;
        this.lastAppliedAt = Instant.now();
        this.lastAutoSyncResult = "APPLIED";

        if (previous != null) {
            previous.close();
        }
        return getStatus();
    }

    @Scheduled(fixedDelayString = "${app.demo.businessRefresh.intervalMs:5000}")
    public void autoRefreshIfNeeded() {
        lastObservedAt = Instant.now();
        if (!isAutoRefreshEnabled()) {
            lastAutoSyncResult = "DISABLED";
            return;
        }

        synchronized (this) {
            if (dataSource == null) {
                try {
                    applyLatestCredentials();
                    lastAutoSyncAt = Instant.now();
                    lastAutoSyncResult = "INITIALIZED";
                } catch (RuntimeException e) {
                    lastAutoSyncResult = "INIT_FAILED: " + e.getMessage();
                }
                return;
            }

            if (!hasCredentialDrift()) {
                lastAutoSyncResult = "NO_CHANGE";
                return;
            }

            try {
                applyLatestCredentials();
                lastAutoSyncAt = Instant.now();
                lastAutoSyncResult = "UPDATED";
            } catch (RuntimeException e) {
                lastAutoSyncResult = "UPDATE_FAILED: " + e.getMessage();
            }
        }
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("businessPoolPresent", dataSource != null);
        result.put("businessAppliedUsername", mask(appliedUsername, 3));
        result.put("businessAppliedAt", lastAppliedAt == null ? null : lastAppliedAt.toString());
        result.put("latestEnvUsername", mask(environment.getProperty("spring.datasource.username"), 3));
        result.put("latestEnvPassword", "***");
        result.put("latestEnvUrl", environment.getProperty("spring.datasource.url"));
        result.put("credentialsDrift", hasCredentialDrift());
        result.put("autoRefreshEnabled", isAutoRefreshEnabled());
        result.put("autoRefreshIntervalMs", environment.getProperty("app.demo.businessRefresh.intervalMs", "5000"));
        result.put("lastObservedAt", lastObservedAt == null ? null : lastObservedAt.toString());
        result.put("lastAutoSyncAt", lastAutoSyncAt == null ? null : lastAutoSyncAt.toString());
        result.put("lastAutoSyncResult", lastAutoSyncResult);

        if (dataSource == null) {
            return result;
        }

        try (Connection connection = dataSource.getConnection();
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

    public Map<String, Object> triggerAutoCheckNow() {
        autoRefreshIfNeeded();
        return getStatus();
    }

    @PreDestroy
    public synchronized void destroy() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }

    private static HikariDataSource buildDataSource(String url, String driver, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName(driver);
        config.setPoolName("demo-business-hikari");
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
            dataSource.close();
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

    private boolean hasCredentialDrift() {
        return !same(appliedUsername, environment.getProperty("spring.datasource.username"))
                || !same(appliedPassword, environment.getProperty("spring.datasource.password"));
    }

    private boolean isAutoRefreshEnabled() {
        return Boolean.parseBoolean(environment.getProperty("app.demo.businessRefresh.enabled", "true"));
    }
}
