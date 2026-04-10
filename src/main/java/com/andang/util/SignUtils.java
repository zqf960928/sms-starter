package com.andang.util;

 import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
 import java.text.SimpleDateFormat;
 import java.util.*;

@Slf4j
public class SignUtils {
    public static final String API_NAME = "Encrypt";

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat(
            "EEE, dd MMM yyyy HH:mm:ss z", Locale.US);

    static {
        DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("GMT+8"));
    }

    /**
     * 按ASCII顺序排序JSON键并拼接
     */
    public static String sortJsonKeysByASCII(Object params) {
        try {
            // 转换为Map
            String json = objectMapper.writeValueAsString(params);
            Map<String, Object> dataMap = objectMapper.readValue(json, Map.class);

            // 提取并排序键
            Set<String> keySet = dataMap.keySet();
            List<String> keys = new ArrayList<>(keySet);
            Collections.sort(keys);

            // 拼接键值对
            StringBuilder sb = new StringBuilder();
            for (String key : keys) {
                if ("token".equals(key)){
                    continue; // 跳过token
                }
                sb.append(key).append("=").append(dataMap.get(key)).append("&");
            }

            // 移除最后一个&
            if (sb.length() > 0) {
                sb.setLength(sb.length() - 1);
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("JSON键排序失败", e);
            throw new RuntimeException("JSON键排序失败", e);
        }
    }

    /**
     * 计算内容的SHA256值（大写十六进制）
     */
    public static String calculateSHA256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));

            // 转换为大写十六进制
            StringBuilder hexStr = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1){
                    hexStr.append('0');
                }
                hexStr.append(hex);
            }
            return hexStr.toString().toUpperCase();
        } catch (Exception e) {
            log.error("计算SHA256失败", e);
            throw new RuntimeException("计算SHA256失败", e);
        }
    }

    /**
     * 获取待签名的字符串
     */
//    public static String getStringToSign(String method, Map<String, String> headers) {
//        String contentSHA256 = headers.get("content-sha256");
//        String contentType = headers.get("content-type");
//        String date = headers.get("date");
//
//        // 构建基础部分
//        StringBuilder sb = new StringBuilder();
//        sb.append(method.toUpperCase()).append("\n")
//                .append(contentSHA256).append("\n")
//                .append(contentType).append("\n")
//                .append(date).append("\n");
//
//        // 添加自定义头部
//        sb.append("x-ksp-acccesskeyid:").append(headers.get("x-ksp-acccesskeyid")).append("\n")
//                .append("x-ksp-apiname:").append(headers.get("x-ksp-apiname")).append("\n");
//
//        // 添加资源路径
//        sb.append("/");
//
//        return sb.toString();
//    }
    /**
     * 完全对齐Go的待签名字符串构造逻辑
     * 格式：小写method\ncontentSHA256\ncontentType\ndate\nx-ksp-acccesskeyid:xxx\nx-ksp-apiname:xxx\n/
     */
    public static String getStringToSign(String method, Map<String, String> headers) {
        // 1. 提取必要字段（确保字段名与Go完全一致，尤其是x-ksp-acccesskeyid多1个c）
        String contentSHA256 = headers.get("content-sha256");
        String contentType = headers.get("content-type");
        String date = headers.get("date");
        String accessKeyId = headers.get("x-ksp-acccesskeyid"); // 关键：acccesskeyid（多1个c）
        String apiName = headers.get("x-ksp-apiname");

        // 2. 拼接格式（强制用\n，不能用\r\n）
        StringBuilder sb = new StringBuilder();
        sb.append(method.toLowerCase()).append("\n") // method转小写，与Go一致
                .append(contentSHA256).append("\n")
                .append(contentType).append("\n")
                .append(date).append("\n")
                .append("x-ksp-acccesskeyid:").append(accessKeyId).append("\n") // 字段名完全匹配
                .append("x-ksp-apiname:").append(apiName).append("\n")
                .append("/"); // canonicalizedResource固定为“/”，与Go一致

        return sb.toString();
    }
    /**
     * 创建请求头（包含签名所需信息）
     */
    public static Map<String, String> createHeaders(String method, String publicKey, String contentSHA256) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Method", method);
        headers.put("content-sha256", contentSHA256);
        headers.put("content-type", "application/json");
        // 1. 定义格式（固定英文Locale）

//        Date d=new Date();
//        DateFormat format=new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z");
////        format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
//        format.setTimeZone(TimeZone.getTimeZone("GMT+8"));
//
//        String formattedTime = format.format(d);
        String result =  DATE_FORMAT.format(new Date()).replace("GMT+08:00", "GMT");

        headers.put("date", result);
//        headers.put("date", result);
        headers.put("x-ksp-acccesskeyid", publicKey);
        headers.put("x-ksp-apiname", API_NAME);
        return headers;
    }
}
