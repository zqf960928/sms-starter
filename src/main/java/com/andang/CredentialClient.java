package com.andang;
 import com.andang.entity.AuthResponse;
 import com.andang.entity.CipherLifecycleResult;
 import com.andang.entity.CredentialDbInfo;
import com.andang.entity.CredentialResult;
 import com.andang.entity.GetCipherResponse;
 import com.andang.entity.ReapplyResult;
 import com.andang.util.HttpUtils;
 import com.andang.util.RsaUtils;
 import com.andang.util.SignUtils;
 import com.fasterxml.jackson.databind.JsonNode;
 import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 凭据管理客户端主类
 * 
 * 该类用于与KSP SMS凭据管理系统交互，实现登录认证和凭据获取功能。
 * 主要功能：
 * 1. 登录认证：使用AppKey和AppSecret获取访问token
 * 2. 获取凭据：根据标签获取加密的凭据并解密
 * 3. 支持静态凭据和动态数据库凭据
 * 
 * 安全机制：
 * - 使用RSA密钥对进行加密传输
 * - 使用AES/CBC/PKCS5Padding算法解密凭据
 * - 支持签名验证，确保请求的完整性和真实性
 */
@Slf4j
public class CredentialClient {
    // 凭据管理系统配置
    private String url;
    private int domain;
    private String appKey;
    private String appSecret;
    private String label;
    private String version = "";

    // 接口路径
    /**
     * 登录认证接口路径
     */
    public static final String LOGIN_PATH = "/v1/ksp/open_api/login";

    /**
     * 获取凭据密文接口路径
     */
    public static final String CIPHER_PATH = "/v1/ksp/open_api/credential/cipher";

    /**
     * 轻量续约接口路径（场景B：更轻量，只需 token + label + renew_ttl）
     */
    public static final String RENEW_PATH = "/v1/ksp/open_api/credential/renew";

    /**
     * 重新申请触发接口路径（场景C：仅触发服务端生成新凭据，不返回内容；成功后需再 cipher 回读）
     */
    public static final String REAPPLY_PATH = "/v1/ksp/open_api/credential/reapply";

    /** 续约失败达到最大有效期限制：客户端应立即转 reapply，不再重试续约 */
    public static final int RENEW_LIMIT_EXCEEDED_CODE = 6130;

    // 加密相关常量
    /**
     * AES加密算法
     * 使用CBC模式，PKCS5Padding填充
     */
    public static final String AES_ALGORITHM = "AES/CBC/PKCS5Padding";
    
    /**
     * API名称
     * 用于签名计算
     */
    public static final String API_NAME = "Encrypt";
    
    /**
     * JSON对象映射器
     * 用于序列化和反序列化JSON数据
     */
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 认证令牌
     * 登录成功后获取的访问令牌
     */
    private String token;
    /** 上次成功登录时间（unix 秒），用于 ensureLogin() 的简单 TTL 兜底 */
    private long lastLoginAtEpochSeconds;
    /** 登录 token 的朴素有效期（秒）。开放平台登录未明确返回 ttl 时用保守值 3600 兜底 */
    private static final long TOKEN_TTL_SECONDS = 3600L;

    /**
     * 长生命周期复用的 RSA 密钥对（PEM 字符串格式）。
     * 之前每次 cipher 都新生成一对，对定时 tick 代价较大；
     * 改为构造时一次性生成，私钥保存在内存里，随实例销毁。安全等价。
     * [0]=公钥PEM [1]=私钥PEM [2]=公钥 Base64(PEM UTF-8 字节) 给 SignUtils/pub 参数用
     */
    private volatile String[] reusedKeyPair;

    /**
     * 带参数的构造方法
     *
     * @param url KSP SMS系统服务地址
     * @param domain 域ID
     * @param appKey 应用密钥
     * @param appSecret 应用密钥
     */
    public CredentialClient(String url, int domain, String appKey, String appSecret) {
        this.url = url;
        this.domain = domain;
        this.appKey = appKey;
        this.appSecret = appSecret;
        ensureReusedKeyPair();
    }

    /**
     * 带version参数的构造方法
     *
     * @param url KSP SMS系统服务地址
     * @param domain 域ID
     * @param appKey 应用密钥
     * @param appSecret 应用密钥
     * @param version 凭据版本
     */
    public CredentialClient(String url, int domain, String appKey, String appSecret, String version) {
        this.url = url;
        this.domain = domain;
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.version = version;
        ensureReusedKeyPair();
    }

    /** 生成复用的 RSA 密钥对 */
    private void ensureReusedKeyPair() {
        if (reusedKeyPair != null) return;
        synchronized (this) {
            if (reusedKeyPair != null) return;
            String[] kp = RsaUtils.generateKeyPair();
            String pubB64 = Base64.getEncoder().encodeToString(kp[0].getBytes(StandardCharsets.UTF_8));
            reusedKeyPair = new String[]{kp[0], kp[1], pubB64};
        }
    }

    /** 获取复用的公钥 PEM */
    public String getPublicKeyPem() {
        ensureReusedKeyPair();
        return reusedKeyPair[0];
    }

    /** 获取复用的私钥 PEM */
    public String getPrivateKeyPem() {
        ensureReusedKeyPair();
        return reusedKeyPair[1];
    }

    /** 获取复用的公钥 Base64(PEM bytes)，作为请求体里的 pub / x-ksp-acccesskeyid */
    public String getPublicKeyBase64() {
        ensureReusedKeyPair();
        return reusedKeyPair[2];
    }

    /**
     * 懒登录：token 空或朴素过期才真正登录；否则复用。
     */
    public void ensureLogin() {
        long now = System.currentTimeMillis() / 1000L;
        if (token != null && !token.isEmpty()
                && (lastLoginAtEpochSeconds == 0L
                    || (now - lastLoginAtEpochSeconds) < TOKEN_TTL_SECONDS)) {
            return;
        }
        synchronized (this) {
            // double-check
            long n2 = System.currentTimeMillis() / 1000L;
            if (token != null && !token.isEmpty()
                    && (lastLoginAtEpochSeconds == 0L
                        || (n2 - lastLoginAtEpochSeconds) < TOKEN_TTL_SECONDS)) {
                return;
            }
            login();
        }
    }

    /**
     * 测试主方法
     * 
     * 该方法用于演示CredentialClient的基本用法，实际使用时不需要调用此方法。
     * 在Starter中，CredentialClient的实例化和调用由KspSmsEnvironmentPostProcessor自动完成。
     * 
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        // 使用带参数的构造方法
        CredentialClient client = new CredentialClient(
                "https://192.168.0.130:8390",
                1,
                "ad6100957ede7d0c6f090de713da07cbd5",
                "f04e03dba233b36c0eefd68f37de0d60"
        );
        try {
            // 1. 登录认证
            client.login();

            // 2. 获取凭据密文
            client.getCredential("mysql00");
        } catch (Exception e) {
            log.error("操作失败", e);
        }
    }

    /**
     * 登录认证获取token
     * 
     * 该方法使用AppKey和AppSecret向KSP SMS系统发送登录请求，
     * 成功后获取访问token，后续的凭据获取操作需要使用该token。
     * 
     * @throws RuntimeException 登录失败时抛出异常
     */
    public void login() {
        log.debug("开始执行 KSP SMS 登录认证");

        // 构建请求参数
        Map<String, Object> params = new HashMap<>();
        params.put("appid", appKey);
        params.put("appsecret", appSecret);
        params.put("domain", domain);

        // 发送请求
        String requestUrl = url + LOGIN_PATH;
        AuthResponse response = HttpUtils.postJson(requestUrl, null, params, AuthResponse.class);

        // 处理响应
        if (response.getCode() != 0) {
            throw new RuntimeException(String.format("登录失败，错误码：%d", response.getCode()));
        }

        this.token = response.getData().get("token");
        if (this.token == null || this.token.isEmpty()) {
            throw new RuntimeException("登录成功但未获取到token");
        }

        this.lastLoginAtEpochSeconds = System.currentTimeMillis() / 1000L;
        log.debug("KSP SMS 登录认证成功");
    }

    /**
     * 获取并解密凭据
     * 
     * 使用实例变量中的label获取凭据。
     * 
     * @return 解密后的凭据内容
     * @throws RuntimeException 获取凭据失败时抛出异常
     */
    public void getCredential() {
        getCredential(label);
    }

    /**
     * 获取并解密指定标签的凭据
     * 
     * 该方法执行以下步骤：
     * 1. 生成RSA密钥对
     * 2. 使用公钥加密请求参数
     * 3. 生成签名并发送请求
     * 4. 接收加密的凭据和加密密钥
     * 5. 使用私钥解密加密密钥
     * 6. 使用解密后的密钥解密凭据
     * 7. 根据凭据类型处理解密结果
     * 
     * @param label 凭据标签
     * @return 解密后的凭据内容
     * @throws RuntimeException 获取凭据失败时抛出异常
     */
    public String getCredential(String label) {
        log.debug("开始获取凭据");
        if (token == null || token.isEmpty()) {
            throw new RuntimeException("请先登录认证");
        }

        CredentialResult result = getCredentialResult(label);
        return result.getContent();
    }

    public CredentialResult getCredentialResult(String label) {
        try {
            // 2.1 生成RSA密钥对
            String[] keyPair = RsaUtils.generateKeyPair();
            String publicKey = keyPair[0];
            String privateKey = keyPair[1];

            // 2.2 构建请求参数
            Map<String, Object> params = new HashMap<>();
            params.put("label", label);
            params.put("version", version);
            params.put("pub", Base64.getEncoder().encodeToString(publicKey.getBytes()));

            // 2.3 准备签名
            String sortedParams = SignUtils.sortJsonKeysByASCII(params);
            String contentSHA256 = SignUtils.calculateSHA256(sortedParams);
            Map<String, String> headers = SignUtils.createHeaders("POST",
                    Base64.getEncoder().encodeToString(publicKey.getBytes()), contentSHA256);

            // 添加token到请求头
            headers.put("token", token);

            // 2.4 生成签名
            String stringToSign = SignUtils.getStringToSign("POST", headers);
            String signature = RsaUtils.sign(stringToSign, privateKey);
            headers.put("Sign-Header", signature);
            // 2.5 发送请求获取密文
            String requestUrl = url + CIPHER_PATH;
            GetCipherResponse response = HttpUtils.postJson(requestUrl, headers, params, GetCipherResponse.class);

            // 2.6 处理响应
            if (response.getCode() != 0) {
                throw new RuntimeException(String.format("获取凭据失败，错误码：%d", response.getCode()));
            }

            // 2.7 解析密文
            String cipherText = (String) response.getData().get("ciphertext");
            String cipherKey = (String) response.getData().get("cipher_key");

            // 2.8 私钥解密凭据加密密钥
            String plainKey = RsaUtils.decryptByPrivateKey(privateKey, cipherKey);

            // 2.9 凭据加密密钥解密凭据
            byte[] iv = plainKey.getBytes(); // 使用密钥作为IV（与原Go代码保持一致）
            String decryptedContent = decrypt(cipherText,
                    Base64.getEncoder().encodeToString(plainKey.getBytes()), iv);

            // 2.10 处理解密结果
            handleDecryptedContent(decryptedContent, response);

            int dbType = 0;
            Object dbTypeObj = response.getData().get("db_type");
            if (dbTypeObj instanceof Number) {
                dbType = ((Number) dbTypeObj).intValue();
            }

            long expireTime = 0L;
            Object expireObj = response.getData().get("expire_time");
            if (expireObj instanceof Number) {
                expireTime = ((Number) expireObj).longValue();
            }

            return new CredentialResult(decryptedContent, dbType, expireTime);
        } catch (Exception e) {
            log.error("获取凭据失败", e);
            throw new RuntimeException("获取凭据失败", e);
        }
    }

    /**
     * 处理解密后的内容
     * 
     * 根据凭据类型进行不同的处理：
     * - 静态凭据（db_type=0）：直接记录日志
     * - 动态凭据（db_type=1）：解析为CredentialDbInfo对象并记录
     * 
     * @param content 解密后的凭据内容
     * @param response 获取凭据的响应对象
     * @throws RuntimeException 处理解密内容失败时抛出异常
     */
    private void handleDecryptedContent(String content, GetCipherResponse response) {
        try {
            // 判断凭据类型
            int  dbType = (int) response.getData().get("db_type");
            if (dbType == 0) {
                return;
            }

            // 解析数据库凭据信息
            CredentialDbInfo dbInfo = objectMapper.readValue(content, CredentialDbInfo.class);
            // 设置过期时间
            dbInfo.setExpireTime((int) ( response.getData().get("expire_time")));

        } catch (Exception e) {
            log.error("处理解密内容失败", e);
            throw new RuntimeException("处理解密内容失败", e);
        }
    }

    /**
     * AES解密方法
     *
     * 使用AES/CBC/PKCS5Padding算法解密密文。
     *
     * @param ciphertext Base64编码的密文
     * @param key Base64编码的密钥
     * @param iv 初始化向量
     * @return 解密后的明文
     * @throws RuntimeException 解密失败时抛出异常
     */
    public static String decrypt(String ciphertext, String key, byte[] iv) {
        try {
            // 解码
            byte[] cipherData = Base64.getDecoder().decode(ciphertext);
            byte[] keyData = Base64.getDecoder().decode(key);

            // 初始化
            SecretKeySpec keySpec = new SecretKeySpec(keyData, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

            // 解密
            byte[] decryptedData = cipher.doFinal(cipherData);
            return new String(decryptedData, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("AES 解密失败", e);
            throw new RuntimeException("AES 解密失败", e);
        }
    }

    // ======================== 新增：生命周期驱动状态机相关接口 ========================

    /**
     * 运行期 fetchCipher：获取 cipher 接口的全量生命周期字段。
     * 与 getCredentialResult() 的区别：
     *   1. 不做解密，只把 cipher_key/ciphertext/生命周期字段 全量塞进 CipherLifecycleResult
     *   2. 复用 RSA 密钥对
     *   3. 签名逻辑与 cipher 接口完全一致
     *   4. 先 ensureLogin()
     */
    public CipherLifecycleResult fetchCipher(String label) {
        ensureLogin();
        if (token == null || token.isEmpty()) {
            throw new RuntimeException("登录失败，token 为空");
        }
        try {
            ensureReusedKeyPair();
            String pubKeyB64 = reusedKeyPair[2];
            String privateKeyPem = reusedKeyPair[1];

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", label);
            params.put("version", version);
            params.put("pub", pubKeyB64);

            String sortedParams = SignUtils.sortJsonKeysByASCII(params);
            String contentSHA256 = SignUtils.calculateSHA256(sortedParams);
            Map<String, String> headers = SignUtils.createHeaders("POST", pubKeyB64, contentSHA256);
            headers.put("token", token);

            String stringToSign = SignUtils.getStringToSign("POST", headers);
            String signature = RsaUtils.sign(stringToSign, privateKeyPem);
            headers.put("Sign-Header", signature);

            String requestUrl = url + CIPHER_PATH;
            JsonNode root = HttpUtils.postJson(requestUrl, headers, params, JsonNode.class);
            CipherLifecycleResult result = new CipherLifecycleResult();
            if (root.hasNonNull("code")) result.setCode(root.get("code").asInt());
            if (root.hasNonNull("msg")) result.setMsg(root.get("msg").asText());
            if (root.hasNonNull("data") && !root.get("data").isNull()) {
                JsonNode d = root.get("data");
                result.setLabel(readText(d, "label"));
                result.setCipherKey(readText(d, "cipher_key"));
                result.setCiphertext(readText(d, "ciphertext"));
                result.setDbType(readInt(d, "db_type"));
                result.setServiceType(readLong(d, "service_type"));
                result.setExpireTime(readLong(d, "expire_time"));
                result.setCreatedTime(readLong(d, "created_time"));
                result.setMaxTtl(readLong(d, "max_ttl"));
                result.setCanRenew(readBool(d, "can_renew"));
                result.setNeedReapply(readBool(d, "need_reapply"));
                result.setNextAction(readText(d, "next_action"));
                result.setRenew(readBool(d, "renew"));
                result.setStatus(readText(d, "status"));
                result.setStatusMsg(readText(d, "status_msg"));
                result.setId(readLong(d, "id"));
            }
            return result;
        } catch (Exception e) {
            log.error("fetchCipher 失败", e);
            throw new RuntimeException("fetchCipher 失败", e);
        }
    }

    /**
     * 轻量续约（场景B）。
     * 成功仅代表服务端接受续约动作（expire_time 被延长）；
     * 不返回密文，续约完成后必须再 fetchCipher() 回读最新密文。
     *
     * 失败 code=6130 代表续约时长超过最大有效期限制，应立即转 reapply。
     *
     * @param id           兼容保留字段（实例ID），取不到时传 0
     * @param label        标签，必传
     * @param renewTtlSec  续约时长（秒），> 0 才有效
     */
    public void renewCredential(long id, String label, long renewTtlSec) {
        ensureLogin();
        ensureReusedKeyPair();
        try {
            String pubKeyB64 = reusedKeyPair[2];
            String privateKeyPem = reusedKeyPair[1];

            Map<String, Object> params = new LinkedHashMap<>();
            if (id > 0L) {
                params.put("id", id);
            }
            params.put("label", label);
            params.put("renew_ttl", renewTtlSec);

            String sortedParams = SignUtils.sortJsonKeysByASCII(params);
            String contentSHA256 = SignUtils.calculateSHA256(sortedParams);
            Map<String, String> headers = SignUtils.createHeaders("POST", pubKeyB64, contentSHA256);
            headers.put("token", token);

            String stringToSign = SignUtils.getStringToSign("POST", headers);
            String signature = RsaUtils.sign(stringToSign, privateKeyPem);
            headers.put("Sign-Header", signature);

            String requestUrl = url + RENEW_PATH;
            JsonNode root = HttpUtils.postJson(requestUrl, headers, params, JsonNode.class);
            int code = root.hasNonNull("code") ? root.get("code").asInt() : -1;
            if (code != 0) {
                throw new RuntimeException(String.format("code=%d", code));
            }
        } catch (RuntimeException r) {
            throw r;
        } catch (Exception e) {
            log.error("renewCredential 失败", e);
            throw new RuntimeException("renewCredential 失败", e);
        }
    }

    /**
     * 重新申请触发（场景C）。
     * 成功仅代表服务端已为该 label 生成新的凭据实例；
     * 绝不直接使用返回的 value，必须再 fetchCipher() 回读最新密文并解密。
     */
    public ReapplyResult reapplyCredential(String label) {
        ensureLogin();
        ensureReusedKeyPair();
        try {
            String pubKeyB64 = reusedKeyPair[2];
            String privateKeyPem = reusedKeyPair[1];

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", label);

            String sortedParams = SignUtils.sortJsonKeysByASCII(params);
            String contentSHA256 = SignUtils.calculateSHA256(sortedParams);
            Map<String, String> headers = SignUtils.createHeaders("POST", pubKeyB64, contentSHA256);
            headers.put("token", token);

            String stringToSign = SignUtils.getStringToSign("POST", headers);
            String signature = RsaUtils.sign(stringToSign, privateKeyPem);
            headers.put("Sign-Header", signature);

            String requestUrl = url + REAPPLY_PATH;
            JsonNode root = HttpUtils.postJson(requestUrl, headers, params, JsonNode.class);
            ReapplyResult result = new ReapplyResult();
            if (root.hasNonNull("code")) result.setCode(root.get("code").asInt());
            if (root.hasNonNull("msg")) result.setMsg(root.get("msg").asText());
            if (root.hasNonNull("data") && !root.get("data").isNull()) {
                JsonNode d = root.get("data");
                result.setLabel(readText(d, "label"));
                result.setExpireTime(readLong(d, "expire_time"));
                result.setCanRenew(readBool(d, "can_renew"));
                result.setNeedReapply(readBool(d, "need_reapply"));
                result.setLifecycleStatus(readText(d, "lifecycle_status"));
                result.setStatusMessage(readText(d, "status_message"));
                result.setValue(readText(d, "value"));
            }
            if (result.getCode() != null && result.getCode() != 0) {
                throw new RuntimeException(String.format("reapply 失败 code=%d", result.getCode()));
            }
            return result;
        } catch (RuntimeException r) {
            throw r;
        } catch (Exception e) {
            log.error("reapplyCredential 失败", e);
            throw new RuntimeException("reapplyCredential 失败", e);
        }
    }

    /**
     * 解密 CipherLifecycleResult 的密文段得到明文字符串。
     * 使用复用的 RSA 私钥 + AES(CBC/PKCS5Padding)，逻辑与 getCredentialResult 保持一致。
     *
     * @return 解密后的明文（可能是 JSON 串），无可用密文则返回 null
     */
    public String decryptCipherLifecycle(CipherLifecycleResult lc) {
        if (lc == null) return null;
        String ciphertext = emptyToNull(lc.getCiphertext());
        String cipherKey = emptyToNull(lc.getCipherKey());
        if (ciphertext == null || cipherKey == null) return null;
        try {
            ensureReusedKeyPair();
            String privateKeyPem = reusedKeyPair[1];
            String plainKey = RsaUtils.decryptByPrivateKey(privateKeyPem, cipherKey);
            byte[] iv = plainKey.getBytes(StandardCharsets.UTF_8);
            String plainKeyB64 = Base64.getEncoder().encodeToString(plainKey.getBytes(StandardCharsets.UTF_8));
            return decrypt(ciphertext, plainKeyB64, iv);
        } catch (Exception e) {
            log.error("decryptCipherLifecycle 失败", e);
            throw new RuntimeException("decryptCipherLifecycle 失败", e);
        }
    }

    /** 从异常 message 判断是否为 renew 6130 错误（续约时长超过最大有效期限制），此时应立即转 reapply。 */
    public static boolean isRenewLimitExceeded(Throwable t) {
        if (t == null) return false;
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        while (cur != null) {
            if (cur.getMessage() != null) sb.append(cur.getMessage()).append('\n');
            cur = cur.getCause();
        }
        String s = sb.toString();
        return s.contains("code=" + RENEW_LIMIT_EXCEEDED_CODE)
                || s.contains("续约时长超过最大有效期限制");
    }

    // -------- JsonNode 辅助读取（null -> 默认值） --------
    private static String readText(JsonNode d, String k) {
        if (d == null) return null;
        JsonNode v = d.get(k);
        if (v == null || v.isNull()) return null;
        return v.asText();
    }
    private static Integer readInt(JsonNode d, String k) {
        if (d == null) return null;
        JsonNode v = d.get(k);
        if (v == null || v.isNull()) return null;
        try { return v.asInt(); } catch (Exception e) { return null; }
    }
    private static Long readLong(JsonNode d, String k) {
        if (d == null) return null;
        JsonNode v = d.get(k);
        if (v == null || v.isNull()) return null;
        try { return v.asLong(); } catch (Exception e) { return null; }
    }
    private static Boolean readBool(JsonNode d, String k) {
        if (d == null) return null;
        JsonNode v = d.get(k);
        if (v == null || v.isNull()) return null;
        try { return v.asBoolean(); } catch (Exception e) { return null; }
    }
    private static String emptyToNull(String s) {
        if (s == null) return null;
        return s.isEmpty() ? null : s;
    }

}
