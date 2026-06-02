package com.andang.starter;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

import javax.sql.DataSource;

/**
 * KSP SMS自动配置类
 * 
 * 该类是Spring Boot的自动配置类，用于启用KSP SMS Starter的配置属性。
 * 通过@EnableConfigurationProperties注解，将KspSmsProperties注册为配置属性类，
 * 使得Spring Boot能够自动从配置文件中读取ksp.sms前缀的配置项。
 */
@Configuration
@EnableConfigurationProperties(KspSmsProperties.class)
public class KspSmsAutoConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "ksp.sms.refresh", name = "enabled", havingValue = "true")
    public KspSmsCredentialRefreshManager kspSmsCredentialRefreshManager(
            ConfigurableEnvironment environment,
            KspSmsProperties properties,
            ObjectProvider<DataSource> dataSourceProvider
    ) {
        return new KspSmsCredentialRefreshManager(environment, properties, dataSourceProvider);
    }
}
