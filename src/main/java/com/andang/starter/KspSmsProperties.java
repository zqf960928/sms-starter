package com.andang.starter;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * KSP SMS Starter配置属性类
 * 
 * 该类用于封装KSP SMS Starter的配置参数，
 * 支持通过application.yml或application.properties文件进行配置。
 * 
 * 配置前缀为：ksp.sms
 */
@Data
@ConfigurationProperties(prefix = "ksp.sms")
public class KspSmsProperties {
    /**
     * KSP SMS系统的服务地址
     * 默认值：https://192.168.0.135
     */
    private String url = "https://192.168.0.135";
    
    /**
     * 域ID
     * 用于标识不同的域环境
     * 默认值：1
     */
    private int domain = 1;
    
    /**
     * 应用密钥
     * 用于身份认证的AppKey
     * 默认值：ad6100957ede7d0c6f090de713da07cbd5
     */
    private String appKey = "ad6100957ede7d0c6f090de713da07cbd5";
    
    /**
     * 应用密钥
     * 用于身份认证的AppSecret
     * 默认值：f04e03dba233b36c0eefd68f37de0d60
     */
    private String appSecret = "f04e03dba233b36c0eefd68f37de0d60";
    
    /**
     * 凭据版本
     * 用于指定获取特定版本的凭据
     * 默认值：空字符串（获取最新版本）
     */
    private String version = "";
    
    /**
     * 是否启用KSP SMS Starter
     * true-启用，false-禁用
     * 默认值：true
     */
    private boolean enabled = true;
    
    /**
     * 密文前缀
     * 用于识别配置文件中的密文，格式为：前缀{密钥ID}
     * 默认值："SMS{"
     */
    private String cipherPrefix = "SMS{";
    
    /**
     * 默认从JSON中提取的字段名
     * 当从SMS获取的凭据是JSON格式时，使用此字段名提取对应的值
     * 默认值："value"
     */
    private String jsonKey = "value";
}