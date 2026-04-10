package com.andang;
 import com.andang.entity.AuthResponse;
 import com.andang.entity.CredentialDbInfo;
 import com.andang.entity.GetCipherResponse;
 import com.andang.util.HttpUtils;
 import com.andang.util.RsaUtils;
 import com.andang.util.SignUtils;
 import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
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
                "https://192.168.0.135",
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
        log.info("开始登录认证...");

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
            throw new RuntimeException(String.format("登录失败，错误码：%d，信息：%s",
                    response.getCode(), response.getMsg()));
        }

        this.token = response.getData().get("token");
        if (this.token == null || this.token.isEmpty()) {
            throw new RuntimeException("登录成功但未获取到token");
        }

        log.info("登录认证成功");
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
        log.info("开始获取凭据，标签：{}", label);
        if (token == null || token.isEmpty()) {
            throw new RuntimeException("请先登录认证");
        }

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
            log.info("待签名字符串：{}", stringToSign);
            String signature = RsaUtils.sign(stringToSign, privateKey);
            headers.put("Sign-Header", signature);
            log.info("签名：{}", signature);
            // 2.5 发送请求获取密文
            String requestUrl = url + CIPHER_PATH;
            GetCipherResponse response = HttpUtils.postJson(requestUrl, headers, params, GetCipherResponse.class);

            // 2.6 处理响应
            if (response.getCode() != 0) {
                throw new RuntimeException(String.format("获取凭据失败，错误码：%d，信息：%s",
                        response.getCode(), response.getMsg()));
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

            return decryptedContent;
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
                log.info("静态凭据类型，值为：[REDACTED]");
                return;
            }

            // 解析数据库凭据信息
            CredentialDbInfo dbInfo = objectMapper.readValue(content, CredentialDbInfo.class);
            // 设置过期时间
            dbInfo.setExpireTime((int) ( response.getData().get("expire_time")));

            log.info("数据库信息：[REDACTED]");
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
}

