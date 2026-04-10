package com.test;

import lombok.Data;

@Data
public class KspSmsProperties {
    /**
     * SMS服务URL
     */
    private String url = "https://192.168.0.135";
    
    /**
     * 域ID
     */
    private int domain = 1;
    
    /**
     * 应用Key
     */
    private String appKey = "ad6100957ede7d0c6f090de713da07cbd5";
    
    /**
     * 应用密钥
     */
    private String appSecret = "dc17a0f71b761a126f37f04d945a5f95";
    
    /**
     * API版本
     */
    private String version = "v1.0";
    
    /**
     * 是否启用KSP SMS Starter
     */
    private boolean enabled = true;
    
    /**
     * 密文前缀
     */
    private String cipherPrefix = "SMS{";
    
    /**
     * JSON中要提取的键
     */
    private String jsonKey = "value";
}