package com.andang.starter;

import com.andang.entity.CipherLifecycleResult;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.*;

/**
 * 对齐 sms-agent main.go 的 6 个决策辅助函数纯逻辑测试。
 *
 * 输入/输出与 Go 端保持一致，不依赖真实 SMS 服务端、不依赖 Spring 容器。
 *
 * 对应 Go：
 *   resolveCipherAction          main.go#L467-L485
 *   wouldProactiveRenewExceedMaxTTL  L512-L531
 *   resolveUsableDeadline        L533-L559
 *   hasUsableCipher              内联 helper
 */
public class RefreshDecisionLogicTest {

    // ================== 纯静态函数（不依赖 this）直接反射调用 ==================

    private static String invokeResolveCipherAction(CipherLifecycleResult lc) throws Exception {
        Method m = KspSmsCredentialRefreshManager.class.getDeclaredMethod("resolveCipherAction", CipherLifecycleResult.class);
        m.setAccessible(true);
        return (String) m.invoke(null, lc);
    }

    private static boolean invokeWouldExceed(CipherLifecycleResult lc, long renewTtl) throws Exception {
        Method m = KspSmsCredentialRefreshManager.class.getDeclaredMethod("wouldProactiveRenewExceedMaxTTL", CipherLifecycleResult.class, long.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, lc, renewTtl);
    }

    private static long invokeResolveDeadline(CipherLifecycleResult lc) throws Exception {
        Method m = KspSmsCredentialRefreshManager.class.getDeclaredMethod("resolveUsableDeadline", CipherLifecycleResult.class);
        m.setAccessible(true);
        return (Long) m.invoke(null, lc);
    }

    private static boolean invokeHasUsableCipher(CipherLifecycleResult lc) throws Exception {
        Method m = KspSmsCredentialRefreshManager.class.getDeclaredMethod("hasUsableCipher", CipherLifecycleResult.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, lc);
    }

    // ================== 依赖 this 的决策函数：构造 manager + KspSmsProperties ==================

    private static KspSmsCredentialRefreshManager buildManager(long renewTtlSec, long leadTimeSec, boolean lifecycleDriven) {
        KspSmsProperties p = new KspSmsProperties();
        p.setRefresh(new KspSmsProperties.Refresh());
        p.getRefresh().setRenewTtlSeconds(renewTtlSec);
        p.getRefresh().setLeadTimeSeconds(leadTimeSec);
        p.getRefresh().setLifecycleDriven(lifecycleDriven);
        p.getRefresh().setEnabled(true);
        p.getRefresh().setMaxRenewFailures(3);
        KspSmsCredentialRefreshManager m = new KspSmsCredentialRefreshManager(null, p, null);
        return m;
    }

    private static boolean invokeShouldTriggerProactiveRenew(KspSmsCredentialRefreshManager m,
                                                              KspSmsCredentialRefreshManager.CachedLabel cached,
                                                              CipherLifecycleResult lc) throws Exception {
        Method mtd = KspSmsCredentialRefreshManager.class.getDeclaredMethod("shouldTriggerProactiveRenew",
                KspSmsCredentialRefreshManager.CachedLabel.class, CipherLifecycleResult.class);
        mtd.setAccessible(true);
        return (Boolean) mtd.invoke(m, cached, lc);
    }

    private static boolean invokeShouldTriggerProactiveReapply(KspSmsCredentialRefreshManager m,
                                                                KspSmsCredentialRefreshManager.CachedLabel cached,
                                                                CipherLifecycleResult lc) throws Exception {
        Method mtd = KspSmsCredentialRefreshManager.class.getDeclaredMethod("shouldTriggerProactiveReapply",
                KspSmsCredentialRefreshManager.CachedLabel.class, CipherLifecycleResult.class);
        mtd.setAccessible(true);
        return (Boolean) mtd.invoke(m, cached, lc);
    }

    private static KspSmsCredentialRefreshManager.CachedLabel invokeMergeLifecycleIntoCache(KspSmsCredentialRefreshManager m,
                                                                                             String label,
                                                                                             KspSmsCredentialRefreshManager.CachedLabel cached,
                                                                                             CipherLifecycleResult lc) throws Exception {
        Method mtd = KspSmsCredentialRefreshManager.class.getDeclaredMethod("mergeLifecycleIntoCache",
                String.class, KspSmsCredentialRefreshManager.CachedLabel.class, CipherLifecycleResult.class);
        mtd.setAccessible(true);
        return (KspSmsCredentialRefreshManager.CachedLabel) mtd.invoke(m, label, cached, lc);
    }

    private static KspSmsCredentialRefreshManager.CachedLabel buildCached(KspSmsCredentialRefreshManager mgr, long lastActionAtSec, long instanceId, long renewFailures) {
        KspSmsCredentialRefreshManager.CachedLabel c = new KspSmsCredentialRefreshManager.CachedLabel(0, 0L, null);
        c.lastActionAtSeconds = lastActionAtSec;
        c.instanceId = instanceId;
        c.renewConsecutiveFailures = (int) renewFailures;
        return c;
    }

    // ================== 测试用例 ==================

    @Test
    public void testResolveCipherAction_nullAndDisabled() throws Exception {
        assertEquals("use", invokeResolveCipherAction(null));

        CipherLifecycleResult disabled = new CipherLifecycleResult();
        disabled.setStatus("Disabled");
        disabled.setNextAction("renew");
        assertEquals("", invokeResolveCipherAction(disabled));
    }

    @Test
    public void testResolveCipherAction_nextActionPriority() throws Exception {
        for (String v : new String[]{"use", "renew", "reapply", "USE", "RENEW ", "  ReApply "}) {
            CipherLifecycleResult lc = new CipherLifecycleResult();
            lc.setStatus("Active");
            lc.setNextAction(v);
            lc.setNeedReapply(true); // next_action 优先级更高，所以仍是 v
            assertEquals(v.trim().toLowerCase(), invokeResolveCipherAction(lc));
        }
    }

    @Test
    public void testResolveCipherAction_needReapplyWhenNoNext() throws Exception {
        CipherLifecycleResult lc = new CipherLifecycleResult();
        lc.setStatus("Active");
        lc.setNeedReapply(true);
        assertEquals("reapply", invokeResolveCipherAction(lc));
    }

    @Test
    public void testResolveCipherAction_canRenewFalse() throws Exception {
        CipherLifecycleResult lc = new CipherLifecycleResult();
        lc.setStatus("Active");
        lc.setNeedReapply(false);
        lc.setCanRenew(false);
        assertEquals("reapply", invokeResolveCipherAction(lc));
    }

    @Test
    public void testResolveCipherAction_noUsableCipherFallsToRenew() throws Exception {
        CipherLifecycleResult lc = new CipherLifecycleResult();
        lc.setStatus("Active");
        lc.setNeedReapply(false);
        lc.setCanRenew(true);
        lc.setCipherKey(""); // 无密文
        assertEquals("renew", invokeResolveCipherAction(lc));
    }

    @Test
    public void testResolveCipherAction_normalUse() throws Exception {
        CipherLifecycleResult lc = new CipherLifecycleResult();
        lc.setStatus("Active");
        lc.setNeedReapply(false);
        lc.setCanRenew(true);
        lc.setCipherKey("some-key");
        lc.setCiphertext("some-ciphertext");
        assertEquals("use", invokeResolveCipherAction(lc));
    }

    @Test
    public void testWouldExceed_basicCases() throws Exception {
        // 创建时间=1000, expire=1080, maxTTL=100 -> deadline=1100
        CipherLifecycleResult lc = new CipherLifecycleResult();
        lc.setCreatedTime(1000L);
        lc.setExpireTime(1080L);
        lc.setMaxTtl(100L);

        assertFalse("expire+19=1099 在限制内", invokeWouldExceed(lc, 19L));
        assertFalse("expire+20=1100 刚好不超", invokeWouldExceed(lc, 20L));
        assertTrue("expire+21=1101 超过", invokeWouldExceed(lc, 21L));
    }

    @Test
    public void testWouldExceed_noLimitOrNoCreated() throws Exception {
        CipherLifecycleResult noMax = new CipherLifecycleResult();
        noMax.setCreatedTime(1000L);
        noMax.setExpireTime(1080L);
        noMax.setMaxTtl(0L);
        assertFalse(invokeWouldExceed(noMax, 99999999L));

        CipherLifecycleResult noCreated = new CipherLifecycleResult();
        noCreated.setCreatedTime(0L);
        noCreated.setExpireTime(1080L);
        noCreated.setMaxTtl(100L);
        assertFalse(invokeWouldExceed(noCreated, 99999999L));
    }

    @Test
    public void testResolveDeadline_nullAndNoExpire() throws Exception {
        assertEquals(Long.MAX_VALUE, invokeResolveDeadline(null));

        CipherLifecycleResult noExpire = new CipherLifecycleResult();
        noExpire.setExpireTime(0L);
        assertEquals(Long.MAX_VALUE, invokeResolveDeadline(noExpire));

        CipherLifecycleResult negative = new CipherLifecycleResult();
        negative.setExpireTime(-1L);
        assertEquals(Long.MAX_VALUE, invokeResolveDeadline(negative));
    }

    @Test
    public void testResolveDeadline_picksMinExpireAndMaxTTL() throws Exception {
        long now = System.currentTimeMillis() / 1000L;

        CipherLifecycleResult lc = new CipherLifecycleResult();
        // expire = now+1000
        lc.setExpireTime(now + 1000L);
        // maxTTL 导致更早：created=now-100, maxTTL=200 -> created+maxTTL=now+100
        lc.setCreatedTime(now - 100L);
        lc.setMaxTtl(200L);
        long d = invokeResolveDeadline(lc);
        assertEquals("取 expire 与 created+maxTTL 的较小者 now+100", now + 100L, d);
    }

    @Test
    public void testResolveDeadline_noLessThanNow() throws Exception {
        long now = System.currentTimeMillis() / 1000L;

        CipherLifecycleResult lc = new CipherLifecycleResult();
        lc.setExpireTime(now - 100L); // 已过期
        // resolveUsableDeadline 会兜底 Math.max(deadline, now)
        long d = invokeResolveDeadline(lc);
        assertTrue("已过期的 deadline 会被兜底为 >= now", d >= now);
    }

    @Test
    public void testHasUsableCipher() throws Exception {
        CipherLifecycleResult empty = new CipherLifecycleResult();
        assertFalse(invokeHasUsableCipher(empty));

        CipherLifecycleResult both = new CipherLifecycleResult();
        both.setCipherKey("k");
        both.setCiphertext("c");
        assertTrue(invokeHasUsableCipher(both));

        CipherLifecycleResult onlyKey = new CipherLifecycleResult();
        onlyKey.setCipherKey("k");
        onlyKey.setCiphertext("");
        assertFalse(invokeHasUsableCipher(onlyKey));
    }

    // ========== shouldTriggerProactiveRenew / Reapply 与冷却防抖 ==========

    private CipherLifecycleResult buildActiveLifecycle(long created, long expire, long maxTtl, boolean canRenew) {
        CipherLifecycleResult lc = new CipherLifecycleResult();
        lc.setStatus("Active");
        lc.setNextAction("use");
        lc.setNeedReapply(false);
        lc.setCreatedTime(created);
        lc.setExpireTime(expire);
        lc.setMaxTtl(maxTtl);
        lc.setCanRenew(canRenew);
        lc.setCipherKey("ck");
        lc.setCiphertext("ct");
        return lc;
    }

    @Test
    public void testProactiveRenew_inWindowAndNoCool_triggers() throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        long expire = now + 80L; // 剩余 80 < renewTTL 300 * 30% = 90
        long created = now - 900L;
        CipherLifecycleResult lc = buildActiveLifecycle(created, expire, 2000L, true);
        KspSmsCredentialRefreshManager mgr = buildManager(300L, 0L, true);
        KspSmsCredentialRefreshManager.CachedLabel cached = buildCached(mgr, now - 120L, 1L, 0);
        boolean r = invokeShouldTriggerProactiveRenew(mgr, cached, lc);
        assertTrue("remaining=80<90、冷却已过且本次续约不会超 maxTTL，应触发 renew", r);
    }

    @Test
    public void testProactiveRenew_outsideRemainingWindow_doesNotTrigger() throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        long expire = now + 100L; // 剩余 100 > 90
        long created = now - 900L;
        CipherLifecycleResult lc = buildActiveLifecycle(created, expire, 1200L, true);
        KspSmsCredentialRefreshManager mgr = buildManager(300L, 0L, true);
        KspSmsCredentialRefreshManager.CachedLabel cached = buildCached(mgr, now - 120L, 1L, 0);
        boolean r = invokeShouldTriggerProactiveRenew(mgr, cached, lc);
        assertFalse("剩余 100 > renewTTL*30%=90，不在窗口内", r);
    }

    @Test
    public void testProactiveRenew_usesMinInitialUsableAsWindowBase() throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        long created = now - 10L;
        long expire = now + 50L; // 当前实例总可用时长=60s，窗口基数应取 min(300,60)=60，窗口=18s
        CipherLifecycleResult lc = buildActiveLifecycle(created, expire, 0L, true);
        KspSmsCredentialRefreshManager mgr = buildManager(300L, 0L, true);
        KspSmsCredentialRefreshManager.CachedLabel cached = buildCached(mgr, now - 120L, 1L, 0);
        boolean r = invokeShouldTriggerProactiveRenew(mgr, cached, lc);
        assertFalse("对齐 K8S 时窗口基数应取 min(initialUsable, renewTTL)，remaining=50 > 18，不应过早 renew", r);
    }

    @Test
    public void testProactiveRenew_doesNotUseLeadTimeFallbackForDynamicLifecycle() throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        long created = now - 10L;
        long expire = now + 290L; // 新实例刚创建，距离 5m TTL 仍有 290s
        CipherLifecycleResult lc = buildActiveLifecycle(created, expire, 1200L, true);
        KspSmsCredentialRefreshManager mgr = buildManager(300L, 300L, true);
        KspSmsCredentialRefreshManager.CachedLabel cached = buildCached(mgr, now - 120L, 1L, 0);
        boolean r = invokeShouldTriggerProactiveRenew(mgr, cached, lc);
        assertFalse("动态凭据生命周期驱动不应再叠加 leadTimeSeconds=300；remaining=290 > renewTTL*30%=90，不应过早 renew", r);
    }

    @Test
    public void testProactiveRenew_stillInCool_doesNotTrigger() throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        long expire = now + 80L;
        long created = now - 900L;
        CipherLifecycleResult lc = buildActiveLifecycle(created, expire, 1200L, true);
        KspSmsCredentialRefreshManager mgr = buildManager(300L, 0L, true);
        KspSmsCredentialRefreshManager.CachedLabel cached = buildCached(mgr, now - 10L, 1L, 0); // 10s ago，still in 60s cool
        boolean r = invokeShouldTriggerProactiveRenew(mgr, cached, lc);
        assertFalse("上次动作只在 10s 前，仍在冷却，不应触发 renew", r);
    }

    @Test
    public void testProactiveReapply_renewWouldExceedMaxTTL_doesTrigger() throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        long renewTTL = 300L;
        long maxTTL = 1000L;
        long created = now - 800L; // created+maxTTL = now+200
        long expire = now + 80L;
        CipherLifecycleResult lc = buildActiveLifecycle(created, expire, maxTTL, true);
        KspSmsCredentialRefreshManager mgr = buildManager(renewTTL, 0L, true);
        KspSmsCredentialRefreshManager.CachedLabel cached = buildCached(mgr, now - 120L, 1L, 0);
        boolean r = invokeShouldTriggerProactiveReapply(mgr, cached, lc);
        assertTrue("按 K8S 口径使用 expire+renewTTL 判断；expire+300 > created+maxTTL，应主动 reapply", r);
    }

    @Test
    public void testProactiveReapply_noRemainingWindow_doesNotTriggerEvenIfExceedMaxTTL() throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        long renewTTL = 300L;
        long maxTTL = 1000L;
        long created = now - 400L; // created+maxTTL = now+600 < now+renewTTL=now+300? No. created+600=now+200, now+300>now+200
        long expire = now + 1000L; // 剩余 >> renewTTL*30%，没在窗口内
        CipherLifecycleResult lc = buildActiveLifecycle(created, expire, maxTTL, true);
        KspSmsCredentialRefreshManager mgr = buildManager(renewTTL, 0L, true);
        KspSmsCredentialRefreshManager.CachedLabel cached = buildCached(mgr, now - 120L, 1L, 0);
        boolean r = invokeShouldTriggerProactiveReapply(mgr, cached, lc);
        assertFalse("剩余=1000s 还大，不在窗口内，不应主动提前 reapply（关键频率防刷）", r);
    }

    @Test
    public void testMergeLifecycleIntoCache_updatesExpireCreatedAndMaxTtl() throws Exception {
        KspSmsCredentialRefreshManager mgr = buildManager(300L, 0L, true);
        KspSmsCredentialRefreshManager.CachedLabel cached = new KspSmsCredentialRefreshManager.CachedLabel(1, 1000L, "old");
        cached.createdTime = 700L;
        cached.maxTtl = 300L;
        cached.canRenew = true;
        cached.needReapply = false;

        CipherLifecycleResult lc = new CipherLifecycleResult();
        lc.setDbType(1);
        lc.setExpireTime(1300L);
        lc.setCreatedTime(900L);
        lc.setMaxTtl(600L);
        lc.setCanRenew(true);
        lc.setNeedReapply(false);

        KspSmsCredentialRefreshManager.CachedLabel merged = invokeMergeLifecycleIntoCache(mgr, "mysql00", cached, lc);
        assertEquals("续约/重申请后要刷新新的过期时间", 1300L, merged.expireTime);
        assertEquals("生命周期变化后要刷新创建时间，用于重新计算真正有效期", 900L, merged.createdTime);
        assertEquals("maxTTL 也要跟随服务端最新生命周期更新", 600L, merged.maxTtl);
    }
}
