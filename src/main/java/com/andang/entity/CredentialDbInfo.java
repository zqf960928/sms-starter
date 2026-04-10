package com.andang.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 解密后的数据库凭据信息
 * 
 * 该类用于封装从KSP SMS系统获取并解密后的数据库凭据信息，
 * 包含数据库连接所需的基本参数，如用户名、密码、SSL配置等。
 */
@Data
public class CredentialDbInfo {
    /**
     * 是否启用SSL连接
     * 1-启用SSL，0-不启用SSL
     */
    @JsonProperty("ssl_enable")
    private long sslEnable;
    
    /**
     * 凭据有效期时间戳
     * 表示该凭据的过期时间，单位为秒
     */
    @JsonProperty("expire_time")
    private long expireTime;
    
    /**
     * 数据库用户名
     * 用于连接数据库的用户名
     */
    @JsonProperty("username")
    private String userName;
    
    /**
     * 数据库密码
     * 用于连接数据库的密码
     */
    @JsonProperty("password")
    private String password;
}
