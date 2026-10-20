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
    <groupId>com.github.zqf960928</groupId>
    <artifactId>sms-starter</artifactId>
    <version>v1.0.0</version> <!-- 替换为你创建的Tag版本 -->
</dependency>
```

### 配置

在`application.yml`或`application.properties`中添加以下配置：

```yaml
ksp:
  sms:
    url: https://192.168.0.130:8390
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

### 静态凭据

当从SMS获取到的凭据是静态值（非JSON格式）时，如：
```
andangSafe123456
```

Starter会直接使用该静态值作为解密结果。

#### 静态凭据示例

```yaml
# 配置静态凭据
app:
  api:
    key: SMS{static00}
```

当SMS系统返回的`static00`标签对应的值是静态字符串（如"andangSafe123456"）时，Starter会直接将该值注入到`app.api.key`配置中。

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

## 许可证

本项目采用 [MIT 许可证](LICENSE) 开源。

## 项目结构

当前仓库里同时放了 starter 源码、联调测试工程、交付包和调试记录，第一次进入时容易看乱。可以先按下面这几个目录理解：

### 1. Starter 主代码目录

真正的 starter 主代码都在 `src/main` 下：

```text
ksp-sms/
├── src/
│   ├── main/
│   │   ├── java/com/andang/
│   │   │   ├── CredentialClient.java              # 调用 SMS 开放接口的客户端
│   │   │   ├── entity/                            # cipher / renew / reapply 响应实体
│   │   │   └── util/                              # HTTP、RSA、签名等工具类
│   │   ├── java/com/andang/starter/
│   │   │   ├── KspSmsEnvironmentPostProcessor.java # 启动期解密注入
│   │   │   ├── KspSmsCredentialRefreshManager.java # 运行期 renew / reapply / 回读 cipher
│   │   │   ├── KspSmsAutoConfiguration.java        # 自动装配
│   │   │   ├── KspSmsProperties.java               # starter 配置项
│   │   │   ├── KspSmsCipherScanner.java            # 扫描 SMS{label[:jsonKey]} 引用
│   │   │   ├── KspSmsRuntimePropertyUpdater.java   # 运行期更新 Environment
│   │   │   └── KspSmsHikariRefresher.java          # 数据源刷新
│   │   └── resources/META-INF/                     # Spring Boot 自动装配配置
│   └── test/java/com/andang/starter/
│       └── RefreshDecisionLogicTest.java           # 刷新判定逻辑测试
├── pom.xml                                         # starter 主工程
└── README.md
```

### 2. 你最常看的几个入口文件

- `src/main/java/com/andang/starter/KspSmsEnvironmentPostProcessor.java`
  负责应用启动时扫描 `SMS{...}` 并解密注入。
- `src/main/java/com/andang/starter/KspSmsCredentialRefreshManager.java`
  负责运行期状态机，执行 `fetchCipher -> renew/reapply -> 再 fetchCipher -> 解密 -> 刷新配置/数据源`。
- `src/main/java/com/andang/CredentialClient.java`
  负责真正调用 SMS 开放接口，包括 `cipher`、`renew`、`reapply`。

### 3. 容易混淆但不是主代码的目录

- `test/`
  独立的联调测试工程，用来跑本地示例和调试 starter，不是 starter 主源码目录。
- `delivery/`
  交付包目录，包含对外说明文档、示例配置和已打出的 jar。
- `docs/`
  项目说明和联调文档。
- `target/`
  Maven 构建产物目录，一般不需要手动查看或修改。
- `.dbg/`、`debug-*.md`
  调试记录和临时排障材料。

如果只是看 starter 逻辑，建议优先只看 `src/main/java/com/andang/starter` 和
`src/main/java/com/andang/CredentialClient.java`。

## 示例代码

### 在业务代码中使用解密后的凭据

```java
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestController {
    
    // 自动注入解密后的数据库用户名
    @Value("${spring.datasource.username}")
    private String dbUsername;
    
    // 自动注入解密后的数据库密码
    @Value("${spring.datasource.password}")
    private String dbPassword;
    
    // 自动注入解密后的API密钥
    @Value("${app.api.key}")
    private String apiKey;
    
    @GetMapping("/test")
    public String test() {
        // 这里可以使用解密后的凭据进行业务操作
        return "Database Username: " + dbUsername + "\n" +
               "Database Password: [REDACTED]\n" + // 注意：实际使用中不要输出密码
               "API Key: [REDACTED]";              // 注意：实际使用中不要输出密钥
    }
}
```

## 故障排查

### 常见问题及解决方案

1. **构建失败**
   - 检查 `pom.xml` 配置是否正确
   - 确保 Maven 依赖能够正常下载

2. **解密失败**
   - 检查网络连接是否正常，能够访问 KSP SMS 系统
   - 确保 `appKey` 和 `appSecret` 配置正确
   - 确保配置的标签（如 `mysql00`）在 KSP SMS 系统中存在

3. **敏感信息泄露**
   - 检查日志配置，确保敏感信息不会被输出
   - 实际使用中不要在代码中打印或记录敏感信息

4. **JitPack 构建失败**
   - 检查 GitHub 仓库是否公开
   - 确保创建了有效的 Tag 或 Release
   - 查看 JitPack 构建日志，了解具体失败原因

## 贡献指南

欢迎贡献代码、报告问题或提出建议！

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'Add some amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 打开 Pull Request

## 联系方式

如有问题或建议，请通过 GitHub Issues 与我们联系。
