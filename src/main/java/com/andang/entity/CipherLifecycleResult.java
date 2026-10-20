package com.andang.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 运行期凭据生命周期结果
 * 对应 cipher 接口返回的全量生命周期字段；对齐 K8S sms-agent 的 cipherRespData。
 * 仅用于 CredentialRefreshManager 的运行期状态机决策，不用于启动期解密注入
 * （启动期仍使用 CredentialResult 保持语义清晰）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CipherLifecycleResult {

    /** 接口响应码：0=成功 */
    private Integer code;
    /** 接口响应描述 */
    private String msg;

    // ===== 密文段（解密凭据用，为空表示本次没有可用密文） =====
    /** RSA 加密后的 AES key（base64）；为空表示本次没有可用密文 */
    private String cipherKey;
    /** AES-CBC(ZeroPadding) 加密后的凭据密文（base64）；为空表示本次没有可用密文 */
    private String ciphertext;

    // ===== 元信息 =====
    /** 0=静态凭据 1=动态DB凭据 对应 CredentialResult#getDbType */
    private Integer dbType;
    /** 服务类型，对应 CredentialResult#getServiceType */
    private Long serviceType;
    /** 当前实例对应的原始标签（服务端回显，可用于校验） */
    private String label;

    // ===== 生命周期主字段 =====
    /** 当前实例过期时间（unix 秒）。-1 表示永久不过期 */
    private Long expireTime;
    /** 当前实例创建时间（unix 秒）。用于 max_ttl 窗口计算 */
    private Long createdTime;
    /** 总 TTL 上限（秒），0 表示无限。超过上限后主动 renew 会被拒绝，应转 reapply */
    private Long maxTtl;

    // ===== 决策/行动字段（优先级从高到低：next_action > need_reapply > can_renew/status） =====
    /**
     * 服务端直接给出的下一步动作建议。
     * 取值：use（直接用）/renew（建议续约）/reapply（建议重新申请）/""（需客户端自行判断）。
     * 优先级最高，非空时直接按其执行。
     */
    private String nextAction;
    /** 实例是否允许手动续约（即使 can_renew=true 也要结合 next_action / 30% 窗口判断） */
    private Boolean canRenew;
    /** 服务端明确要求必须重新申请（无论是否过期都要走 reapply） */
    private Boolean needReapply;

    // ===== 额外状态 =====
    /** 实例自动续约开关（true=服务端会自动续，但客户端仍可主动触发手动续约） */
    private Boolean renew;
    /**
     * 实例状态。取值如：
     * Active / RenewLimitReached / Expired / Revoked / Disabled / PendingRotation / Invalid
     */
    private String status;
    /** 状态详细说明（人话，给日志看） */
    private String statusMsg;
    /** 实例 ID（兼容保留，续约时 label 为主、id 兜底；取不到则 0） */
    private Long id;
}
