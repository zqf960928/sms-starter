package com.test;

import com.andang.starter.KspSmsCredentialRefreshManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行期刷新调试端点（只在本地/测试环境用，生产禁用）。
 */
@RestController
public class TestController {

    private static final Logger log = LoggerFactory.getLogger(TestController.class);

    @Autowired
    private Environment env;

    @Autowired
    private ApplicationContext applicationContext;

    // ---- 启动期快照（@Value 只注入一次，供对比"Environment 动态刷新后是否生效"用） ----
    @org.springframework.beans.factory.annotation.Value("${spring.datasource.username}")
    private String dbUsernameBootSnapshot;

    @org.springframework.beans.factory.annotation.Value("${spring.datasource.password}")
    private String dbPasswordBootSnapshot;

    @org.springframework.beans.factory.annotation.Value("${app.api.key}")
    private String apiKeyBootSnapshot;

    @org.springframework.beans.factory.annotation.Value("${app.api.secret}")
    private String apiSecretBootSnapshot;

    @org.springframework.beans.factory.annotation.Value("${app.api.token}")
    private String apiTokenBootSnapshot;

    @org.springframework.beans.factory.annotation.Value("${app.test.aaa}")
    private String testAaaBootSnapshot;

    @org.springframework.beans.factory.annotation.Value("${app.test.bbb}")
    private String testBbbBootSnapshot;

    @org.springframework.beans.factory.annotation.Value("${app.test.ccc}")
    private String testCccBootSnapshot;

    @GetMapping("/test")
    public Map<String, Object> test() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("snapshotComment", "这是 @Value 的启动期快照。若 Spring Environment 已被刷新但此处不变，属 @Value 正常行为（只注入一次），请访问 /debug/env 看最新值。");
        result.put("dbUsername", mask(dbUsernameBootSnapshot, 3));
        result.put("dbPassword", "***");
        result.put("apiKey", mask(apiKeyBootSnapshot, 3));
        result.put("apiSecret", "***");
        result.put("apiToken", "***");
        result.put("testAaa", mask(testAaaBootSnapshot, 3));
        result.put("testBbb", mask(testBbbBootSnapshot, 3));
        result.put("testCcc", mask(testCccBootSnapshot, 3));
        return result;
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }

    // ================================================================
    // 调试 1：取 Environment 里的真实值
    // ================================================================
    @GetMapping("/debug/env")
    public Map<String, Object> debugEnv() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("spring.datasource.username_currentEnv", mask(env.getProperty("spring.datasource.username"), 3));
        m.put("spring.datasource.password_currentEnv", "***");
        m.put("app.api.key_currentEnv", mask(env.getProperty("app.api.key"), 3));
        m.put("app.api.secret_currentEnv", "***");
        m.put("app.api.token_currentEnv", "***");
        m.put("app.test.aaa_currentEnv", mask(env.getProperty("app.test.aaa"), 3));
        m.put("app.test.bbb_currentEnv", mask(env.getProperty("app.test.bbb"), 3));
        m.put("app.test.ccc_currentEnv", mask(env.getProperty("app.test.ccc"), 3));

        m.put("vsBootSnapshot_diffDetected", !(
                eqMasked(dbUsernameBootSnapshot, env.getProperty("spring.datasource.username"))
                && eqMasked(apiKeyBootSnapshot, env.getProperty("app.api.key"))
                && eqMasked(apiSecretBootSnapshot, env.getProperty("app.api.secret"))
                && eqMasked(apiTokenBootSnapshot, env.getProperty("app.api.token"))
                && eqMasked(testAaaBootSnapshot, env.getProperty("app.test.aaa"))
                && eqMasked(testBbbBootSnapshot, env.getProperty("app.test.bbb"))
                && eqMasked(testCccBootSnapshot, env.getProperty("app.test.ccc"))
        ));
        return m;
    }

    // ================================================================
    // 调试 2：反射拿 RefreshManager 内部 cache + running 状态
    // ================================================================
    @GetMapping("/debug/refresh/manager-status")
    public Map<String, Object> managerStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            KspSmsCredentialRefreshManager mgr = applicationContext.getBean(KspSmsCredentialRefreshManager.class);
            out.put("beanPresent", true);
            Field runningF = KspSmsCredentialRefreshManager.class.getDeclaredField("running");
            runningF.setAccessible(true);
            out.put("running", runningF.getBoolean(mgr));

            Field cacheF = KspSmsCredentialRefreshManager.class.getDeclaredField("cache");
            cacheF.setAccessible(true);
            ConcurrentHashMap<?, ?> cache = (ConcurrentHashMap<?, ?>) cacheF.get(mgr);
            Map<String, Object> cacheView = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : cache.entrySet()) {
                String label = String.valueOf(e.getKey());
                Object cl = e.getValue();
                Map<String, Object> one = new LinkedHashMap<>();
                for (Field f : cl.getClass().getDeclaredFields()) {
                    f.setAccessible(true);
                    String name = f.getName();
                    Object v = f.get(cl);
                    if ("content".equalsIgnoreCase(name) || "dbPassword".equalsIgnoreCase(name)) {
                        one.put(name, "***");
                    } else {
                        one.put(name, v);
                    }
                }
                cacheView.put(label, one);
            }
            out.put("cachedLabels", cacheView);
            out.put("cachedLabelsCount", cache.size());

            Field currentUserF = KspSmsCredentialRefreshManager.class.getDeclaredField("currentDatasourceUsername");
            Field currentPwdF = KspSmsCredentialRefreshManager.class.getDeclaredField("currentDatasourcePassword");
            currentUserF.setAccessible(true);
            currentPwdF.setAccessible(true);
            out.put("currentDatasourceUsername_inManagerCache", mask(String.valueOf(currentUserF.get(mgr)), 3));
            out.put("currentDatasourcePassword_inManagerCache", "***");
        } catch (Exception ex) {
            out.put("beanPresent", false);
            out.put("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
        return out;
    }

    // ================================================================
    // 调试 3：手动执行一次 tick（不等 schedule 调度到点）
    // ================================================================
    @GetMapping("/debug/refresh/run-once")
    public Map<String, Object> runOnce() {
        Map<String, Object> out = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try {
            KspSmsCredentialRefreshManager mgr = applicationContext.getBean(KspSmsCredentialRefreshManager.class);
            Method m = KspSmsCredentialRefreshManager.class.getDeclaredMethod("safeTick");
            m.setAccessible(true);
            m.invoke(mgr);
            out.put("invoked", true);
            out.put("tookMs", System.currentTimeMillis() - start);
            out.put("thenVisit", "/debug/refresh/manager-status  查看最新 cache 变化");
        } catch (Exception ex) {
            out.put("invoked", false);
            out.put("tookMs", System.currentTimeMillis() - start);
            out.put("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
            Throwable cause = ex.getCause();
            if (cause != null) {
                out.put("cause", cause.getClass().getSimpleName() + ": " + cause.getMessage());
            }
        }
        return out;
    }

    private static String mask(String s, int keepHead) {
        if (s == null) return null;
        if (s.length() <= keepHead) return s + "***";
        return s.substring(0, keepHead) + "***(len=" + s.length() + ")";
    }

    private static boolean eqMasked(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}
