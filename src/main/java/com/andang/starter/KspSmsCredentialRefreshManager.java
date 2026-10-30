package com.andang.starter;

import com.andang.CredentialClient;
import com.andang.entity.CipherLifecycleResult;
import com.andang.entity.CredentialResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.ConfigurableEnvironment;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * KSP SMS 运行期凭据刷新管理器（运行期 "使用过程中判断续约/重新申请" 的核心位置）。
 *
 * 架构边界（与用户最初判断一致）：
 *   - 启动期 EnvironmentPostProcessor 一次性解密注入完全不动
 *   - 本类 = 运行期状态机，对齐 sms-agent 的 fetchAndDecrypt 逻辑：
 *       fetchCipher → resolveCipherAction → renew/reapply → 再 fetchCipher 回读 → 解密 → 变更才刷 Environment/DataSource
 *
 * 与 sms-agent 行为对齐的关键常量：
 *   PROACTIVE_RENEW_THRESHOLD_PERCENT=30：剩余可用期不足 renewTTL*30% 时进入主动续约窗口。
 *   RENEW_LIMIT_EXCEEDED_CODE=6130：续约时长超过 maxTTL，立即转 reapply
 */
@Slf4j
public class KspSmsCredentialRefreshManager implements SmartLifecycle, DisposableBean {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // -------- 对齐 K8S sms-agent 的常量（与 main.go 保持一致） --------
    private static final int PROACTIVE_RENEW_THRESHOLD_PERCENT = 30;

    private final ConfigurableEnvironment environment;
    private final KspSmsProperties properties;
    private final ObjectProvider<DataSource> dataSourceProvider;

    private final Object lifecycleMonitor = new Object();
    private volatile boolean running;
    private ScheduledExecutorService executor;

    private KspSmsRuntimePropertyUpdater runtimePropertyUpdater;
    private List<KspSmsCipherReference> refs;
    /** label -> 引用列表（每次 tick 前先构建一次，避免重复构建） */
    private volatile Map<String, List<KspSmsCipherReference>> labelToRefs;

    private final Map<String, CachedLabel> cache = new ConcurrentHashMap<>();

    private volatile String currentDatasourceUsername;
    private volatile String currentDatasourcePassword;

    /** 长生命周期复用的 CredentialClient（避免每次 tick 都 new + 重新登录 + 重新生成 RSA 密钥对） */
    private volatile CredentialClient sharedClient;

    public KspSmsCredentialRefreshManager(
            ConfigurableEnvironment environment,
            KspSmsProperties properties,
            ObjectProvider<DataSource> dataSourceProvider
    ) {
        this.environment = environment;
        this.properties = properties;
        this.dataSourceProvider = dataSourceProvider;
    }

    @Override
    public void start() {
        synchronized (lifecycleMonitor) {
            if (running) {
                return;
            }
            if (!properties.isEnabled() || properties.getRefresh() == null || !properties.getRefresh().isEnabled()) {
                return;
            }

            this.runtimePropertyUpdater = new KspSmsRuntimePropertyUpdater(environment);
            this.refs = KspSmsCipherScanner.scan(environment, properties);
            if (refs == null || refs.isEmpty()) {
                return;
            }

            this.labelToRefs = buildLabelToRefs(refs);

            this.currentDatasourceUsername = environment.getProperty("spring.datasource.username");
            this.currentDatasourcePassword = environment.getProperty("spring.datasource.password");

            // 共享 CredentialClient：构造时生成 RSA 密钥对；随后按需 ensureLogin()
            this.sharedClient = new CredentialClient(
                    properties.getUrl(),
                    properties.getDomain(),
                    properties.getAppKey(),
                    properties.getAppSecret(),
                    properties.getVersion()
            );
            // 启动时先登录一次，保证首轮 tick 不用再登录
            try {
                this.sharedClient.ensureLogin();
            } catch (Exception e) {
                log.warn("KSP SMS refresh manager startup login failed, will retry on next tick: {}", e.getMessage());
            }

            this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ksp-sms-refresh");
                t.setDaemon(true);
                return t;
            });

            long intervalSeconds = Math.max(5L, properties.getRefresh().getIntervalSeconds());
            this.executor.scheduleWithFixedDelay(this::safeTick, 0L, intervalSeconds, TimeUnit.SECONDS);
            this.running = true;
        }
    }

    private static Map<String, List<KspSmsCipherReference>> buildLabelToRefs(List<KspSmsCipherReference> refs) {
        Map<String, List<KspSmsCipherReference>> m = new HashMap<>();
        for (KspSmsCipherReference ref : refs) {
            m.computeIfAbsent(ref.getLabel(), k -> new ArrayList<>()).add(ref);
        }
        return m;
    }

    private void safeTick() {
        try {
            if (properties.getRefresh() != null
                    && properties.getRefresh().isLifecycleDriven()) {
                tickLifecycle();
            } else {
                tickLegacy();
            }
        } catch (Exception e) {
            log.warn("KSP SMS refresh tick failed: {}", e.getMessage());
        }
    }

    // ======================================================================
    //  旧逻辑保留作降级路径：lifecycleDriven=false 或静态凭据 + refreshStatic=false 时使用
    // ======================================================================
    private void tickLegacy() {
        long nowEpochSeconds = System.currentTimeMillis() / 1000;
        long leadTimeSeconds = Math.max(0L, properties.getRefresh().getLeadTimeSeconds());

        Map<String, Object> updatedProperties = new HashMap<>();

        for (Map.Entry<String, List<KspSmsCipherReference>> entry : labelToRefs.entrySet()) {
            String label = entry.getKey();
            List<KspSmsCipherReference> references = entry.getValue();

            CachedLabel cached = cache.get(label);
            if (cached != null && cached.dbType == 0 && !properties.getRefresh().isRefreshStatic()) {
                continue;
            }

            boolean shouldRefresh = cached == null
                    || cached.expireTime <= 0L
                    || (cached.expireTime - nowEpochSeconds) <= leadTimeSeconds;
            if (!shouldRefresh) continue;

            try {
                sharedClient.ensureLogin();
                CredentialResult result = sharedClient.getCredentialResult(label);
                if (result == null || result.getContent() == null) continue;

                CachedLabel next = new CachedLabel(result.getDbType(), result.getExpireTime(), result.getContent());
                next.createdTime = cached != null ? cached.createdTime : 0L;
                next.maxTtl = cached != null ? cached.maxTtl : 0L;
                cache.put(label, next);

                applyDecryptedContentToUpdatedProperties(label, references, result.getContent(), updatedProperties);
            } catch (Exception e) {
                log.warn("legacy refresh failed label={}, keep cached: {}", label, e.getMessage());
            }
        }

        if (!updatedProperties.isEmpty()) {
            runtimePropertyUpdater.putAll(updatedProperties);
            maybeRefreshDatasource(updatedProperties);
        }
    }

    // ======================================================================
    //  新逻辑：完全对齐 sms-agent fetchAndDecrypt 状态机
    // ======================================================================
    private void tickLifecycle() {
        Map<String, Object> updatedProperties = new HashMap<>();

        for (Map.Entry<String, List<KspSmsCipherReference>> entry : labelToRefs.entrySet()) {
            String label = entry.getKey();
            List<KspSmsCipherReference> references = entry.getValue();

            CachedLabel cached = cache.get(label);

            // 步骤 1：先 fetchCipher 拿生命周期 + 密文
            CipherLifecycleResult lifecycle;
            try {
                lifecycle = sharedClient.fetchCipher(label);
            } catch (Exception e) {
                log.warn("fetchCipher failed label={}, keep cached: {}", label, e.getMessage());
                continue;
            }
            if (lifecycle == null) {
                log.warn("fetchCipher returned null label={}", label);
                continue;
            }
            Integer code = lifecycle.getCode();
            if (code != null && code != 0) {
                log.warn("fetchCipher code!=0 label={} code={} msg={}", label, code, lifecycle.getMsg());
                continue;
            }

            // 静态凭据（DbType==0）且 refreshStatic=false → 即使生命周期模式也跳过检查
            Integer dbType = lifecycle.getDbType();
            if (dbType != null && dbType == 0 && !properties.getRefresh().isRefreshStatic()) {
                // 静态凭据不刷新；但如果首次还是得刷一次（cache 为空）
                if (cached != null) continue;
            }

            boolean hasUsable = hasUsableCipher(lifecycle);

            // 步骤 2：有可用密文 → 进入主动判断：
            // - 30% 窗口内若服务端已明确给出 renew / reapply 提示，则应提前执行
            // - 否则再按本地 maxTTL 与主动续约规则兜底
            if (hasUsable) {
                String windowedAction = resolveWindowedUsableCipherAction(lifecycle);
                if ("reapply".equals(windowedAction) || shouldTriggerProactiveReapply(cached, lifecycle)) {
                    if ("reapply".equals(windowedAction)) {
                        log.info("label {} proactive reapply by lifecycle hint within {}% window (nextAction={} canRenew={} needReapply={} status={} msg={})",
                                label, PROACTIVE_RENEW_THRESHOLD_PERCENT,
                                lifecycle.getNextAction(), lifecycle.getCanRenew(), lifecycle.getNeedReapply(),
                                lifecycle.getStatus(), lifecycle.getStatusMsg());
                    } else {
                        // 续约会超过 maxTTL → 主动提前 reapply（对齐 sms-agent 的 shouldTriggerProactiveReapply）
                        log.info("label {} proactive reapply: expire+renewTTL would exceed created+maxTTL (status={} msg={})",
                                label, lifecycle.getStatus(), lifecycle.getStatusMsg());
                    }
                    try {
                        sharedClient.reapplyCredential(label);
                        lifecycle = refetchCipherAfterAction(label, "proactive reapply");
                        hasUsable = hasUsableCipher(lifecycle);
                        if (!hasUsable) {
                            throw new IllegalStateException("after proactive reapply cipher still unusable label=" + label);
                        }
                        cached = mergeLifecycleIntoCache(label, cached, lifecycle);
                        if (cached != null) cached.lastActionAtSeconds = System.currentTimeMillis() / 1000L;
                    } catch (Exception e) {
                        log.warn("proactive reapply failed label={}, keep cached: {}", label, e.getMessage());
                        continue;
                    }
                } else if ("renew".equals(windowedAction) || shouldTriggerProactiveRenew(cached, lifecycle)) {
                    if ("renew".equals(windowedAction)) {
                        log.info("label {} proactive renew by lifecycle hint within {}% window (nextAction={} status={} msg={})",
                                label, PROACTIVE_RENEW_THRESHOLD_PERCENT,
                                lifecycle.getNextAction(), lifecycle.getStatus(), lifecycle.getStatusMsg());
                    } else {
                        // 进入主动续约窗口（剩余 < renewTTL*30%）+ canRenew=true
                        log.info("label {} proactive renew (remaining within {}% of renewTTL; status={} msg={})",
                                label, PROACTIVE_RENEW_THRESHOLD_PERCENT,
                                lifecycle.getStatus(), lifecycle.getStatusMsg());
                    }
                    long id = cached != null && cached.instanceId > 0L ? cached.instanceId :
                              (lifecycle.getId() != null ? lifecycle.getId() : 0L);
                    long nowSec = System.currentTimeMillis() / 1000L;
                    try {
                        sharedClient.renewCredential(id, label, properties.getRefresh().getRenewTtlSeconds());
                        lifecycle = refetchCipherAfterAction(label, "proactive renew");
                        hasUsable = hasUsableCipher(lifecycle);
                        if (cached != null) cached.renewConsecutiveFailures = 0;
                        if (!hasUsable) {
                            // renew 之后回读仍无密文：按 next_action 再决策
                            String action = resolveCipherAction(lifecycle);
                            if ("reapply".equals(action)) {
                                log.warn("label {} after renew back to cipher action=reapply, fallthrough", label);
                                sharedClient.reapplyCredential(label);
                                lifecycle = refetchCipherAfterAction(label, "renew-fallback reapply");
                                hasUsable = hasUsableCipher(lifecycle);
                                if (!hasUsable) throw new IllegalStateException("after reapply cipher still unusable label=" + label);
                                cached = mergeLifecycleIntoCache(label, cached, lifecycle);
                                if (cached != null) cached.lastActionAtSeconds = nowSec;
                            } else {
                                continue; // 无可做动作，等下个 tick
                            }
                        } else {
                            cached = mergeLifecycleIntoCache(label, cached, lifecycle);
                            if (cached != null) cached.lastActionAtSeconds = nowSec;
                        }
                    } catch (Exception e) {
                        if (CredentialClient.isRenewLimitExceeded(e)) {
                            log.warn("label {} renew hit renew-limit code=6130, fallthrough reapply: {}", label, e.getMessage());
                            try {
                                sharedClient.reapplyCredential(label);
                                lifecycle = refetchCipherAfterAction(label, "renew-6130 reapply");
                                hasUsable = hasUsableCipher(lifecycle);
                                if (!hasUsable) throw new IllegalStateException("after renew-6130 reapply cipher still unusable label=" + label);
                                cached = mergeLifecycleIntoCache(label, cached, lifecycle);
                                if (cached != null) {
                                    cached.renewConsecutiveFailures = 0;
                                    cached.lastActionAtSeconds = nowSec;
                                }
                            } catch (Exception re) {
                                incrementRenewFailures(cached);
                                log.warn("reapply after renew-limit failed label={}, keep cached: {}", label, re.getMessage());
                                continue;
                            }
                        } else {
                            incrementRenewFailures(cached);
                            if (cached != null && cached.renewConsecutiveFailures >= properties.getRefresh().getMaxRenewFailures()) {
                                log.warn("label {} renew consecutive failures={} >= maxRenewFailures, fallthrough reapply",
                                        label, cached.renewConsecutiveFailures);
                                try {
                                    sharedClient.reapplyCredential(label);
                                    lifecycle = refetchCipherAfterAction(label, "renew-failures reapply");
                                    hasUsable = hasUsableCipher(lifecycle);
                                    if (!hasUsable) throw new IllegalStateException("after renew-failures reapply cipher still unusable label=" + label);
                                    cached = mergeLifecycleIntoCache(label, cached, lifecycle);
                                    if (cached != null) {
                                        cached.renewConsecutiveFailures = 0;
                                        cached.lastActionAtSeconds = nowSec;
                                    }
                                } catch (Exception re) {
                                    log.warn("reapply after renew-failures failed label={}, keep cached: {}", label, re.getMessage());
                                    continue;
                                }
                            } else {
                                log.warn("proactive renew failed label={}, keep cached: {}", label, e.getMessage());
                                continue;
                            }
                        }
                    }
                }
                // else：use 路径（动作就是"继续用"，无需改生命周期）
            }

            // 步骤 3：有可用密文 → 解密；与 cached 内容比对；内容变了才刷 Environment
            if (hasUsable) {
                String decrypted;
                try {
                    decrypted = sharedClient.decryptCipherLifecycle(lifecycle);
                } catch (Exception e) {
                    log.warn("decryptCipherLifecycle failed label={}, keep cached: {}", label, e.getMessage());
                    continue;
                }
                if (decrypted == null) {
                    log.warn("decryptCipherLifecycle returned null label={}", label);
                    continue;
                }
                cached = mergeLifecycleIntoCache(label, cached, lifecycle);
                boolean contentChanged = (cached.content == null) || !cached.content.equals(decrypted);
                if (contentChanged) {
                    cached.content = decrypted;
                    cached.contentHash = decrypted.hashCode();
                    applyDecryptedContentToUpdatedProperties(label, references, decrypted, updatedProperties);
                }
                continue;
            }

            // 步骤 4：没有可用密文 → 按 resolveCipherAction 走 renew 或 reapply
            String action = resolveCipherAction(lifecycle);
            long nowSec = System.currentTimeMillis() / 1000L;
            // no usable cipher 也需要冷却：即使一直无密文也不能每轮 tick 都打服务端 renew/reapply
            long renewTTL4 = properties.getRefresh() != null ? properties.getRefresh().getRenewTtlSeconds() : 0L;
            long coolAction = Math.max(60L, renewTTL4 > 0L ? (long) ((double) renewTTL4 * 0.05d) : 60L);
            boolean inCool = cached != null && cached.lastActionAtSeconds > 0L && (nowSec - cached.lastActionAtSeconds) < coolAction;
            if (inCool) {
                log.debug("label {} no usable cipher, action={} but still in cool {}s (last={}s ago), skip tick",
                        label, action, coolAction, (nowSec - (cached != null ? cached.lastActionAtSeconds : 0L)));
                continue;
            }
            if ("reapply".equals(action)) {
                log.info("label {} no usable cipher, action=reapply (status={} msg={})",
                        label, lifecycle.getStatus(), lifecycle.getStatusMsg());
                try {
                    sharedClient.reapplyCredential(label);
                    lifecycle = refetchCipherAfterAction(label, "action=reapply");
                    if (!hasUsableCipher(lifecycle)) {
                        throw new IllegalStateException("after reapply cipher still unusable label=" + label);
                    }
                    String decrypted = sharedClient.decryptCipherLifecycle(lifecycle);
                    if (decrypted == null) throw new IllegalStateException("after reapply decrypt null label=" + label);
                    cached = mergeLifecycleIntoCache(label, cached, lifecycle);
                    if (cached != null) cached.lastActionAtSeconds = nowSec;
                    boolean changed = (cached.content == null) || !cached.content.equals(decrypted);
                    cached.content = decrypted;
                    cached.contentHash = decrypted.hashCode();
                    cached.renewConsecutiveFailures = 0;
                    if (changed) {
                        applyDecryptedContentToUpdatedProperties(label, references, decrypted, updatedProperties);
                    }
                } catch (Exception e) {
                    log.warn("action=reapply flow failed label={}, keep cached: {}", label, e.getMessage());
                    continue;
                }
            } else if ("renew".equals(action)) {
                log.info("label {} no usable cipher, action=renew (status={} msg={})",
                        label, lifecycle.getStatus(), lifecycle.getStatusMsg());
                long id = cached != null && cached.instanceId > 0L ? cached.instanceId :
                          (lifecycle.getId() != null ? lifecycle.getId() : 0L);
                try {
                    sharedClient.renewCredential(id, label, properties.getRefresh().getRenewTtlSeconds());
                    lifecycle = refetchCipherAfterAction(label, "action=renew");
                    boolean wroteAction = false;
                    if (hasUsableCipher(lifecycle)) {
                        String decrypted = sharedClient.decryptCipherLifecycle(lifecycle);
                        if (decrypted == null) throw new IllegalStateException("after renew decrypt null label=" + label);
                        cached = mergeLifecycleIntoCache(label, cached, lifecycle);
                        if (cached != null) {
                            cached.lastActionAtSeconds = nowSec;
                            wroteAction = true;
                        }
                        boolean changed = (cached.content == null) || !cached.content.equals(decrypted);
                        cached.content = decrypted;
                        cached.contentHash = decrypted.hashCode();
                        cached.renewConsecutiveFailures = 0;
                        if (changed) {
                            applyDecryptedContentToUpdatedProperties(label, references, decrypted, updatedProperties);
                        }
                    } else {
                        String fallAction = resolveCipherAction(lifecycle);
                        if ("reapply".equals(fallAction)) {
                            log.warn("label {} after renew back to cipher action=reapply, fallthrough", label);
                            sharedClient.reapplyCredential(label);
                            lifecycle = refetchCipherAfterAction(label, "renew-fallback reapply (no cipher)");
                            if (!hasUsableCipher(lifecycle)) throw new IllegalStateException("after reapply cipher still unusable label=" + label);
                            String decrypted = sharedClient.decryptCipherLifecycle(lifecycle);
                            if (decrypted == null) throw new IllegalStateException("after reapply decrypt null label=" + label);
                            cached = mergeLifecycleIntoCache(label, cached, lifecycle);
                            if (cached != null) {
                                cached.lastActionAtSeconds = nowSec;
                                wroteAction = true;
                            }
                            boolean changed = (cached.content == null) || !cached.content.equals(decrypted);
                            cached.content = decrypted;
                            cached.contentHash = decrypted.hashCode();
                            cached.renewConsecutiveFailures = 0;
                            if (changed) {
                                applyDecryptedContentToUpdatedProperties(label, references, decrypted, updatedProperties);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("action=renew flow failed label={}, fallthrough reapply: {}", label, e.getMessage());
                    try {
                        sharedClient.reapplyCredential(label);
                        lifecycle = refetchCipherAfterAction(label, "renew-failed fallthrough reapply");
                        if (!hasUsableCipher(lifecycle)) throw new IllegalStateException("after reapply cipher still unusable label=" + label);
                        String decrypted = sharedClient.decryptCipherLifecycle(lifecycle);
                        if (decrypted == null) throw new IllegalStateException("after reapply decrypt null label=" + label);
                        cached = mergeLifecycleIntoCache(label, cached, lifecycle);
                        if (cached != null) {
                            cached.lastActionAtSeconds = nowSec;
                        }
                        boolean changed = (cached.content == null) || !cached.content.equals(decrypted);
                        cached.content = decrypted;
                        cached.contentHash = decrypted.hashCode();
                        cached.renewConsecutiveFailures = 0;
                        if (changed) {
                            applyDecryptedContentToUpdatedProperties(label, references, decrypted, updatedProperties);
                        }
                    } catch (Exception re) {
                        incrementRenewFailures(cached);
                        log.warn("renew-failed fallthrough reapply failed label={}, keep cached: {}", label, re.getMessage());
                        continue;
                    }
                }
            }
            // else: action == "use" 或空，但 no usable cipher，这种无动作场景保持 cached 不变
        }

        if (!updatedProperties.isEmpty()) {
            runtimePropertyUpdater.putAll(updatedProperties);
            maybeRefreshDatasource(updatedProperties);
        }
    }

    // ================================================================
    //  对齐 sms-agent 的决策辅助函数（直接从 main.go L467-L568 公式对译）
    // ================================================================

    /** 对齐 main.go#resolveCipherAction (L467-L485) */
    private static String resolveCipherAction(CipherLifecycleResult lc) {
        if (lc == null) return "use";
        String status = lc.getStatus() != null ? lc.getStatus() : "";
        if ("Disabled".equals(status)) return "";

        String next = lc.getNextAction();
        if (next != null && !next.trim().isEmpty()) {
            String n = next.trim().toLowerCase();
            if ("use".equals(n) || "renew".equals(n) || "reapply".equals(n)) return n;
        }

        Boolean needReapply = lc.getNeedReapply();
        if (needReapply != null && needReapply) return "reapply";

        Boolean canRenew = lc.getCanRenew();
        if (canRenew != null && !canRenew) return "reapply";

        // 没有可用密文但又不是上述情形时，优先走 renew
        if (!hasUsableCipher(lc)) return "renew";

        return "use";
    }

    /** 对齐 main.go#shouldTriggerProactiveRenew (L487-L492)：进入主动窗口且本次续约不超 maxTTL 才主动续约 + 冷却防抖 */
    private boolean shouldTriggerProactiveRenew(CachedLabel cached, CipherLifecycleResult lc) {
        Boolean canRenew = lc.getCanRenew();
        if (canRenew == null || !canRenew) return false;
        long renewTTL = properties.getRefresh() != null ? properties.getRefresh().getRenewTtlSeconds() : 0L;
        if (renewTTL <= 0L) return false;
        if (!hasUsableCipher(lc)) return false; // 无密文直接走 action 路径
        if (!isInProactiveRefreshWindow(lc)) return false;
        // 冷却防抖：上次动作后 N 秒内不允许再次触发，防止服务端 expire 短导致每轮 tick 都续约
        long now = System.currentTimeMillis() / 1000L;
        long coolSec = Math.max(60L, (long) ((double) renewTTL * 0.05d));
        if (cached != null && cached.lastActionAtSeconds > 0L && (now - cached.lastActionAtSeconds) < coolSec) {
            return false;
        }
        return !wouldProactiveRenewExceedMaxTTL(lc, renewTTL);
    }

    /** 对齐 main.go#shouldTriggerProactiveReapply (L494-L499)：
     *  只有【快到期（剩余 < renewTTL*30%）】并且【续约会超 maxTTL】两个条件同时满足，
     *  才主动提前 reapply。新实例刚 reapply 完（剩余还大）绝不每轮都刷。 + 冷却防抖 */
    private boolean shouldTriggerProactiveReapply(CachedLabel cached, CipherLifecycleResult lc) {
        Boolean canRenew = lc.getCanRenew();
        if (canRenew == null || !canRenew) return false;
        if (!hasUsableCipher(lc)) return false;
        long renewTTL = properties.getRefresh() != null ? properties.getRefresh().getRenewTtlSeconds() : 0L;
        if (renewTTL <= 0L) return false;
        if (!isInProactiveRefreshWindow(lc)) return false;
        long now = System.currentTimeMillis() / 1000L;
        // 冷却防抖
        long coolSec = Math.max(60L, (long) ((double) renewTTL * 0.05d));
        if (cached != null && cached.lastActionAtSeconds > 0L && (now - cached.lastActionAtSeconds) < coolSec) {
            return false;
        }
        return wouldProactiveRenewExceedMaxTTL(lc, renewTTL);
    }

    /**
     * 对仍有可用密文的实例，30% 窗口内应尊重服务端 action hint。
     * 否则会出现“密文还能用，就一直拖到过期后才 reapply”的现象。
     */
    private String resolveWindowedUsableCipherAction(CipherLifecycleResult lc) {
        if (lc == null || !hasUsableCipher(lc)) return "use";
        if (!isInProactiveRefreshWindow(lc)) return "use";
        String action = resolveCipherAction(lc);
        if ("renew".equals(action) || "reapply".equals(action)) {
            return action;
        }
        return "use";
    }

    /** 对齐 main.go#isInProactiveRefreshWindow (L501-L510)：
     *  窗口内才允许触发主动 renew / proactive reapply。
     *  判定基线对齐 sms-agent：
     *  - 仅对有可用密文、可续约、未禁用、生命周期合法的实例生效
     *  - 窗口基数 = min(renewTTL, 当前实例初始可用时长)
     *  - 剩余可用期 <= 窗口基数 * 30% 时进入主动窗口
     *  - 不再叠加 leadTimeSeconds，避免新实例刚创建就因 leadTime=300s 被误判进入主动窗口 */
    private boolean isInProactiveRefreshWindow(CipherLifecycleResult lc) {
        if (lc == null) return false;
        long renewTTL = properties.getRefresh() != null ? properties.getRefresh().getRenewTtlSeconds() : 0L;
        if (renewTTL <= 0L) return false;
        if (!hasUsableCipher(lc)) return false;
        if ("Disabled".equalsIgnoreCase(lc.getStatus() != null ? lc.getStatus().trim() : "")) return false;
        Long expireTime = lc.getExpireTime();
        Long createdTime = lc.getCreatedTime();
        if (expireTime == null || expireTime <= 0L) return false;
        if (createdTime == null || createdTime <= 0L || expireTime <= createdTime) return false;
        long usableDeadline = resolveUsableDeadline(lc);
        long now = System.currentTimeMillis() / 1000L;
        if (usableDeadline <= now) return false;
        long renewWindowBase = renewTTL;
        if (usableDeadline > createdTime) {
            long initialUsable = usableDeadline - createdTime;
            if (initialUsable > 0L && initialUsable < renewWindowBase) {
                renewWindowBase = initialUsable;
            }
        }
        if (renewWindowBase <= 0L) return false;
        long remaining = usableDeadline - now;
        return remaining * 100L <= renewWindowBase * (long) PROACTIVE_RENEW_THRESHOLD_PERCENT;
    }

    /** 对齐 main.go#wouldProactiveRenewExceedMaxTTL (L512-L531) */
    private static boolean wouldProactiveRenewExceedMaxTTL(CipherLifecycleResult lc, long renewTTL) {
        long maxTTL = lc.getMaxTtl() != null ? lc.getMaxTtl() : 0L;
        long created = lc.getCreatedTime() != null ? lc.getCreatedTime() : 0L;
        long expire = lc.getExpireTime() != null ? lc.getExpireTime() : 0L;
        if (renewTTL <= 0L || maxTTL <= 0L || created <= 0L || expire <= 0L) return false; // 无限制或信息不完整
        long maxDeadline = created + maxTTL;
        if (maxDeadline <= created) return false;
        return expire + renewTTL > maxDeadline;
    }

    /** 对齐 main.go#resolveUsableDeadline (L533-L559) */
    private static long resolveUsableDeadline(CipherLifecycleResult lc) {
        if (lc == null) return Long.MAX_VALUE;
        long now = System.currentTimeMillis() / 1000L;
        // expireTime 为 null 或 0 或 -1 → 永不过期
        Long e = lc.getExpireTime();
        long expire = (e == null || e <= 0L) ? Long.MAX_VALUE : e;
        long maxTTLDeadline = Long.MAX_VALUE;
        Long m = lc.getMaxTtl();
        Long c = lc.getCreatedTime();
        if (m != null && m > 0L && c != null && c > 0L) {
            maxTTLDeadline = c + m;
        }
        long deadline = Math.min(expire, maxTTLDeadline);
        // 再兜底一次：如果 deadline 小于 now，修正为 now（避免 negative remaining 带来的误判）
        return Math.max(deadline, now);
    }

    private static void incrementRenewFailures(CachedLabel cached) {
        if (cached == null) return;
        cached.renewConsecutiveFailures++;
    }

    /** 是否有可解密的密文（cipher_key + ciphertext 都非空） */
    private static boolean hasUsableCipher(CipherLifecycleResult lc) {
        if (lc == null) return false;
        String ck = lc.getCipherKey();
        String ct = lc.getCiphertext();
        return ck != null && !ck.isEmpty() && ct != null && !ct.isEmpty();
    }

    /** renew / reapply 动作之后都必须再 fetchCipher 回读最新状态 + 密文 */
    private CipherLifecycleResult refetchCipherAfterAction(String label, String actionDesc) {
        CipherLifecycleResult lc = sharedClient.fetchCipher(label);
        if (lc == null) {
            throw new RuntimeException(actionDesc + " refetch cipher null label=" + label);
        }
        Integer c = lc.getCode();
        if (c != null && c != 0) {
            throw new RuntimeException(actionDesc + " refetch cipher code=" + c + " msg=" + lc.getMsg() + " label=" + label);
        }
        return lc;
    }

    /** 把 cipher 生命周期字段合并到缓存（不覆盖 content，由调用方决定） */
    private CachedLabel mergeLifecycleIntoCache(String label, CachedLabel cached, CipherLifecycleResult lc) {
        CachedLabel c = cached != null ? cached : new CachedLabel(0, 0L, null);
        if (lc.getDbType() != null) c.dbType = lc.getDbType();
        if (lc.getExpireTime() != null) c.expireTime = lc.getExpireTime();
        if (lc.getCreatedTime() != null) c.createdTime = lc.getCreatedTime();
        if (lc.getMaxTtl() != null) c.maxTtl = lc.getMaxTtl();
        if (lc.getCanRenew() != null) c.canRenew = lc.getCanRenew();
        if (lc.getNeedReapply() != null) c.needReapply = lc.getNeedReapply();
        if (lc.getId() != null) c.instanceId = lc.getId();
        cache.put(label, c);
        return c;
    }

    /** 解密后的明文 JSON/非 JSON → 按每个 ref 的 jsonKey 取对应值，塞进 updatedProperties */
    private static void applyDecryptedContentToUpdatedProperties(
            String label,
            List<KspSmsCipherReference> references,
            String decryptedContent,
            Map<String, Object> updatedProperties
    ) {
        if (decryptedContent == null || references == null || references.isEmpty()) return;
        JsonNode json = null;
        try {
            json = objectMapper.readTree(decryptedContent);
        } catch (Exception ignored) {
        }
        for (KspSmsCipherReference ref : references) {
            String value = decryptedContent;
            if (json != null && ref.getJsonKey() != null && json.has(ref.getJsonKey())) {
                value = json.get(ref.getJsonKey()).asText();
            }
            updatedProperties.put(ref.getPropertyKey(), value);
        }
    }

    @Deprecated
    private CredentialResult fetchCredential(String label) {
        sharedClient.ensureLogin();
        return sharedClient.getCredentialResult(label);
    }

    private void maybeRefreshDatasource(Map<String, Object> updatedProperties) {
        if (!properties.getRefresh().isDatasourceEnabled()) {
            return;
        }

        String newUser = updatedProperties.containsKey("spring.datasource.username")
                ? String.valueOf(updatedProperties.get("spring.datasource.username"))
                : currentDatasourceUsername;
        String newPass = updatedProperties.containsKey("spring.datasource.password")
                ? String.valueOf(updatedProperties.get("spring.datasource.password"))
                : currentDatasourcePassword;

        if (newUser == null || newPass == null) {
            return;
        }
        if (newUser.equals(currentDatasourceUsername) && newPass.equals(currentDatasourcePassword)) {
            return;
        }

        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            currentDatasourceUsername = newUser;
            currentDatasourcePassword = newPass;
            return;
        }

        if (!properties.getRefresh().isHikariEnabled()) {
            currentDatasourceUsername = newUser;
            currentDatasourcePassword = newPass;
            return;
        }

        KspSmsHikariRefresher refresher = new KspSmsHikariRefresher(
                dataSource,
                properties.getRefresh().isValidateOnRotate(),
                properties.getRefresh().getValidationQuery(),
                properties.getRefresh().getEvictDelayMs()
        );
        if (!refresher.supports()) {
            currentDatasourceUsername = newUser;
            currentDatasourcePassword = newPass;
            return;
        }

        String oldUser = currentDatasourceUsername;
        String oldPass = currentDatasourcePassword;
        refresher.applyWithRollback(newUser, newPass, oldUser, oldPass);
        currentDatasourceUsername = newUser;
        currentDatasourcePassword = newPass;
        log.info("KSP SMS datasource credentials updated");
    }

    @Override
    public void stop() {
        synchronized (lifecycleMonitor) {
            running = false;
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void destroy() {
        stop();
    }

    /**
     * 缓存的 label 信息（对齐 sms-agent 每个 secret 的运行期状态）。
     * 除了过期/明文，还额外保留：创建时间、maxTTL、canRenew/needReapply、实例id、续约连续失败次数等，
     * 用于下一轮 tick 无需重新登录/读取就能判断续约窗口。
     */
    static final class CachedLabel {
        int dbType;
        long expireTime;
        String content;
        int contentHash;

        // 新增：生命周期缓存字段（对齐 sms-agent 的状态）
        long createdTime;
        long maxTtl;
        boolean canRenew;
        boolean needReapply;
        long instanceId;
        int renewConsecutiveFailures;
        /** 上次主动执行 renew / reapply 的时间（epoch seconds），用于冷却防抖：
         *  动作刚做完的 N 秒内不允许再次主动触发，防止服务端 expire 短导致每轮 tick 都在刷。 */
        long lastActionAtSeconds;

        CachedLabel(int dbType, long expireTime, String content) {
            this.dbType = dbType;
            this.expireTime = expireTime;
            this.content = content;
        }

        long getExpireTime() {
            return expireTime;
        }
    }
}
