package com.andang.starter;

import org.springframework.util.ReflectionUtils;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.Statement;

class KspSmsHikariRefresher {
    private final DataSource dataSource;
    private final boolean validateOnRotate;
    private final String validationQuery;
    private final long evictDelayMs;

    KspSmsHikariRefresher(DataSource dataSource, boolean validateOnRotate, String validationQuery, long evictDelayMs) {
        this.dataSource = dataSource;
        this.validateOnRotate = validateOnRotate;
        this.validationQuery = validationQuery;
        this.evictDelayMs = evictDelayMs;
    }

    boolean supports() {
        return resolveHikariDataSource(dataSource) != null;
    }

    void apply(String username, String password) {
        Object hikari = resolveHikariDataSource(dataSource);
        if (hikari == null) {
            return;
        }

        Method setUsername = ReflectionUtils.findMethod(hikari.getClass(), "setUsername", String.class);
        Method setPassword = ReflectionUtils.findMethod(hikari.getClass(), "setPassword", String.class);
        if (setUsername != null) {
            ReflectionUtils.invokeMethod(setUsername, hikari, username);
        }
        if (setPassword != null) {
            ReflectionUtils.invokeMethod(setPassword, hikari, password);
        }

        if (evictDelayMs > 0) {
            try {
                Thread.sleep(evictDelayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        softEvictConnections(hikari);

        if (validateOnRotate) {
            validateConnection();
        }
    }

    void applyWithRollback(String newUser, String newPass, String oldUser, String oldPass) {
        try {
            apply(newUser, newPass);
        } catch (RuntimeException e) {
            apply(oldUser, oldPass);
            throw e;
        }
    }

    private void validateConnection() {
        try (Connection connection = dataSource.getConnection()) {
            if (validationQuery == null || validationQuery.isEmpty()) {
                return;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute(validationQuery);
            }
        } catch (Exception e) {
            throw new RuntimeException("数据库连接校验失败", e);
        }
    }

    private static void softEvictConnections(Object hikariDataSource) {
        Method getPoolMxBean = ReflectionUtils.findMethod(hikariDataSource.getClass(), "getHikariPoolMXBean");
        if (getPoolMxBean == null) {
            return;
        }
        Object poolMxBean = ReflectionUtils.invokeMethod(getPoolMxBean, hikariDataSource);
        if (poolMxBean == null) {
            return;
        }
        Method softEvict = ReflectionUtils.findMethod(poolMxBean.getClass(), "softEvictConnections");
        if (softEvict != null) {
            ReflectionUtils.invokeMethod(softEvict, poolMxBean);
        }
    }

    private static Object resolveHikariDataSource(DataSource dataSource) {
        if (dataSource == null) {
            return null;
        }
        if ("com.zaxxer.hikari.HikariDataSource".equals(dataSource.getClass().getName())) {
            return dataSource;
        }
        try {
            if (dataSource.isWrapperFor(Class.forName("com.zaxxer.hikari.HikariDataSource"))) {
                return dataSource.unwrap(Class.forName("com.zaxxer.hikari.HikariDataSource"));
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
