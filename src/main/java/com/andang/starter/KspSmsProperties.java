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
     * 默认值：https://192.168.0.130:8390
     */
    private String url = "https://192.168.0.130:8390";
    
    /**
     * 域ID
     * 用于标识不同的域环境
     * 默认值：1
     */
    private int domain = 1;
    
    /**
     * 应用密钥
     * 用于身份认证的AppKey
     * 默认值：空字符串（需由接入方显式提供）
     */
    private String appKey = "";
    
    /**
     * 应用密钥
     * 用于身份认证的AppSecret
     * 默认值：空字符串（需由接入方显式提供）
     */
    private String appSecret = "";
    
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

    private Refresh refresh = new Refresh();

    @Data
    public static class Refresh {
        // ===== 原有保留：基础刷新开关 =====
        private boolean enabled = false;
        /** 运行期 tick 间隔（秒），对齐 sms-agent 的 RefreshInterval */
        private long intervalSeconds = 60;
        /**
         * 距离过期多久开始触发刷新（秒）。
         * 当 lifecycleDriven=false 或 DbType==0(静态凭据) 时沿用此绝对值逻辑；
         * 动态凭据（lifecycleDriven=true + DbType>0）不再使用它，
         * 改用 renewTtlSeconds × PROACTIVE_RENEW_THRESHOLD_PERCENT 计算窗口。
         */
        private long leadTimeSeconds = 300;
        /** 是否对 DbType==0 的静态凭据也执行定时 cipher 检查并按 need_reapply 触发 reapply */
        private boolean refreshStatic = false;
        private boolean datasourceEnabled = true;
        private boolean hikariEnabled = true;
        private boolean validateOnRotate = false;
        private String validationQuery = "SELECT 1";
        private long evictDelayMs = 0;

        // ===== 新增：对齐 K8S sms-agent 动态凭据生命周期策略 =====
        /**
         * 每次手动续约希望延长的秒数（renew_ttl）。
         * 默认 300 秒，建议与服务端该凭据的单轮 ttl 保持一致；
         * 若 <= 0 则 lifecycle 模式下不会主动 renew，只在 cipher 明确 need_reapply 时才 reapply。
         */
        private long renewTtlSeconds = 300L;
        /** 连续主动续约失败多少次后放弃 renew 直接走 reapply，默认 2 */
        private int maxRenewFailures = 2;
        /**
         * 是否启用"按 cipher 返回的生命周期字段驱动状态机"的新逻辑。
         * true  = 对齐 sms-agent：cipher → next_action/30%窗口 → renew/reapply → 再 cipher
         * false = 降级沿用旧逻辑：只按 expireTime - now < leadTimeSeconds 判断刷新
         * 默认 true；如服务端暂时没部署 renew/reapply 接口可临时关掉走兜底路径。
         */
        private boolean lifecycleDriven = true;
    }
}
