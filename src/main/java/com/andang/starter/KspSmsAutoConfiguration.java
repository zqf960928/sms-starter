package com.andang.starter;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

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
}
