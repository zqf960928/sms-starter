package com.andang.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

/**
 * 登录认证响应
 * 
 * 该类用于封装KSP SMS系统登录认证接口的响应数据，
 * 包含认证状态、token等关键信息。
 */
@Data
public class AuthResponse {
    /**
     * 响应状态码
     * 0-成功，非0-失败
     */
    private int code;
    
    /**
     * 响应数据
     * 包含认证token等信息
     */
    private Map<String, String> data;
    
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
