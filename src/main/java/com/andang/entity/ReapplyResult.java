package com.andang.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * reapply 接口返回结果。
 * 注意：即使服务端返回了 value 字段，客户端也不直接使用它；
 * reapply 成功后必须再调 cipher 接口回读最新密文并解密，以避免内容不一致。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReapplyResult {

    private Integer code;
    private String msg;

    private String label;
    private Long expireTime;
    private Boolean canRenew;
    private Boolean needReapply;
    private String lifecycleStatus;
    private String statusMessage;

    /** 兼容字段：服务端可能回显新 value，但客户端绝不直接使用此字段 */
    private String value;
}
