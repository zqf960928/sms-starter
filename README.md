# KSP SMS Spring Boot Starter

这是一个Spring Boot Starter，用于集成KSP SMS凭据管理系统，实现配置文件中的密文自动解密功能。

## 功能特性

- 支持在Spring Boot启动时自动解密配置文件中的密文
- 提供简洁的配置方式
- 实现业务代码"零改造"

## 快速开始

### 添加依赖

在你的Spring Boot项目中添加以下依赖：

```xml
<repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
</repository>

<dependency>
    <groupId>com.github.username</groupId>
    <artifactId>ksp-sms-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 配置

在`application.yml`或`application.properties`中添加以下配置：

```yaml
ksp:
  sms:
    url: https://192.168.0.135
    domain: 1
    appKey: ad6100957ede7d0c6f090de713da07cbd5
    appSecret: f04e03dba233b36c0eefd68f37de0d60
    version: ""
    enabled: true
    cipherPrefix: "SMS{"
    jsonKey: "value" # 默认从JSON中提取的字段名
```

### 使用密文

在配置文件中使用`SMS{密钥ID}`或`SMS{密钥ID:jsonKey}`格式标识密文：

```yaml
spring:
  datasource:
    # 方式1：使用默认的jsonKey配置
    username: SMS{mysql00:username}
    password: SMS{mysql00:password}
    
    # 方式2：使用配置文件中设置的默认jsonKey
    # username: SMS{mysql00}
    # password: SMS{mysql00}
```

当从SMS获取到的凭据是JSON格式时，如：
```json
{
  "ssl_enable": 0,
  "expire_time": 1841972985,
  "username": "vbslklittgwotzdy",
  "password": "zOvhQoCWmOwCKWkk"
}
```

Starter会自动根据指定的jsonKey（如username、password）从JSON中提取对应的值。

## 发布到JitPack.io

1. 在GitHub上创建一个仓库
2. 推送代码到GitHub
3. 创建一个release或tag
4. 在JitPack.io上搜索你的仓库
5. 点击"Get it"获取依赖代码

## 工作原理

1. 利用Spring Boot的`EnvironmentPostProcessor`接口，在应用启动的极早期介入
2. 读取配置文件中的密文（以`SMS{密钥ID}`格式标识）
3. 调用KSP SMS凭据管理系统的API获取解密后的明文
4. 将明文添加到环境变量中，供应用使用

## 注意事项

- 确保网络能够访问KSP SMS凭据管理系统
- 确保配置的`appKey`和`appSecret`正确
- 确保配置的标签（如`mysql00`）在KSP SMS系统中存在
