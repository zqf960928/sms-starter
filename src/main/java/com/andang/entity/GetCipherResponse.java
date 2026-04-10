package com.andang.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;


/**
 * 获取凭据密文响应
 * 
 * 该类用于封装KSP SMS系统获取凭据密文接口的响应数据，
 * 包含加密后的凭据信息、加密密钥等关键数据。
 */
@Data
public class GetCipherResponse {
    /**
     * 响应状态码
     * 0-成功，非0-失败
     */
    private int code;
    
    /**
     * 响应数据
     * 包含以下字段：
     * - ciphertext: 加密后的凭据密文
     * - cipher_key: 加密密钥（使用RSA加密）
     * - db_type: 凭据类型（0-静态凭据，1-动态凭据）
     * - expire_time: 凭据过期时间戳
     */
    private Map<String, Object> data;
    
    /**
     * 响应消息
     * 成功时为空，失败时包含错误信息
     */
    private String msg;
    
    /**
     * 请求ID
     * 用于追踪请求的唯一标识
     */
    @JsonProperty("request_id")
    private String requestId;
}
