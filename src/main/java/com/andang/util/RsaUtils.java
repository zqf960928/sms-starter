package com.andang.util;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;


@Slf4j
public class RsaUtils {
    public static final String RSA_ALGORITHM = "RSA";
    public static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    public static final int RSA_KEY_SIZE = 2048;

    // PEM 格式标签
    private static final String PUB_HEADER = "-----BEGIN PUBLIC KEY-----\n";
    private static final String PUB_FOOTER = "\n-----END PUBLIC KEY-----";
    private static final String PRI_HEADER = "-----BEGIN RSA PRIVATE KEY-----\n";
    private static final String PRI_FOOTER = "\n-----END RSA PRIVATE KEY-----";

    /**
     * 生成 RSA 密钥对（返回 PEM 格式字符串）
     * @return 长度为2的数组，[0]公钥，[1]私钥
     */
    public static String[] generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(RSA_ALGORITHM);
            generator.initialize(RSA_KEY_SIZE);
            KeyPair keyPair = generator.generateKeyPair();

            // 公钥转 PEM 格式
            String publicKey = encodePublicKey(keyPair.getPublic());
            // 私钥转 PEM 格式
            String privateKey = encodePrivateKey(keyPair.getPrivate());

            return new String[]{publicKey, privateKey};
        } catch (Exception e) {
            log.error("生成 RSA 密钥对失败", e);
            throw new RuntimeException("生成 RSA 密钥对失败", e);
        }
    }

    /**
     * 公钥编码为 PEM 格式
     */
    private static String encodePublicKey(PublicKey publicKey) {
        byte[] pubBytes = publicKey.getEncoded();
        String base64 = Base64.getEncoder().encodeToString(pubBytes);
        return PUB_HEADER + base64 + PUB_FOOTER;
    }

    /**
     * 私钥编码为 PEM 格式
     */
    private static String encodePrivateKey(PrivateKey privateKey) {
        byte[] priBytes = privateKey.getEncoded();
        String base64 = Base64.getEncoder().encodeToString(priBytes);
        return PRI_HEADER + base64 + PRI_FOOTER;
    }

    /**
     * 从 PEM 格式字符串解析私钥
     */
    public static PrivateKey parsePrivateKey(String privateKeyPem) {
        try {
            // 移除 PEM 头和尾
            String pem = privateKeyPem.replace(PRI_HEADER, "").replace(PRI_FOOTER, "");
            byte[] keyBytes = Base64.getDecoder().decode(pem);

            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory factory = KeyFactory.getInstance(RSA_ALGORITHM);
            return factory.generatePrivate(keySpec);
        } catch (Exception e) {
            log.error("解析 RSA 私钥失败", e);
            throw new RuntimeException("解析 RSA 私钥失败", e);
        }
    }

    /**
     * 使用私钥解密
     */
    public static String decryptByPrivateKey(String privateKeyPem, String ciphertext) {
        try {
            PrivateKey privateKey = parsePrivateKey(privateKeyPem);
            Cipher cipher = Cipher.getInstance(RSA_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, privateKey);

            byte[] encryptedData = Base64.getDecoder().decode(ciphertext);
            byte[] decryptedData = cipher.doFinal(encryptedData);

            return new String(decryptedData, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("RSA 解密失败", e);
            throw new RuntimeException("RSA 解密失败", e);
        }
    }

    /**
     * 使用私钥签名
     */
    public static String sign(String content, String privateKeyPem) {
        try {
            PrivateKey privateKey = parsePrivateKey(privateKeyPem);
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initSign(privateKey);
            signature.update(content.getBytes(StandardCharsets.UTF_8));

            byte[] signBytes = signature.sign();
            return Base64.getEncoder().encodeToString(signBytes);
        } catch (Exception e) {
            log.error("RSA 签名失败", e);
            throw new RuntimeException("RSA 签名失败", e);
        }
    }
}
