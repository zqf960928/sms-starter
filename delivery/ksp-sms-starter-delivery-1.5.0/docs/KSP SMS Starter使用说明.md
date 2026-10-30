# KSP SMS Starter使用说明

## 1. 组件定位

`ksp-sms-spring-boot-starter` 用于在 Spring Boot 项目中对接 SMS 凭据管理系统，覆盖两类能力：

- 启动期解密注入：把 `SMS{...}` 引用转换为实际明文
- 运行期动态轮转：对动态凭据执行 `renew / reapply / refetch`

其中职责边界如下：

- `EnvironmentPostProcessor`：只负责启动期一次性解密注入
- `KspSmsCredentialRefreshManager`：只负责运行期定时检测、续约、重申和回读
- starter 默认不负责客户业务连接池的新老凭据切换控制

推荐理解方式：

- starter = 最新凭据提供者
- 业务系统 = 连接池切换控制者

`1.5.0` 的默认推荐口径是：由 starter 负责持续提供最新凭据，由业务系统自己决定连接池切换时机与平滑策略。starter 自带的数据源热更新能力保留为可选项，不作为默认推荐边界。

## 2. Maven 依赖

```xml
<dependency>
    <groupId>com.ad</groupId>
    <artifactId>ksp-sms-spring-boot-starter</artifactId>
    <version>1.5.0</version>
</dependency>
```

## 3. 基础配置

```yaml
ksp:
  sms:
    enabled: true
    url: ${KSP_SMS_URL}
    domain: ${KSP_SMS_DOMAIN:1}
    appKey: ${KSP_SMS_APP_KEY}
    appSecret: ${KSP_SMS_APP_SECRET}
    version: ""
    cipherPrefix: "SMS{"
    jsonKey: "value"
```

说明：

- `url`：SMS 服务地址
- `domain`：所属域 ID
- `appKey/appSecret`：外部登录凭据
- `cipherPrefix`：默认 `SMS{`
- `jsonKey`：当解密值为 JSON 时默认提取的字段名

## 4. 配置文件引用方式

支持两种写法：

```yaml
spring:
  datasource:
    username: "SMS{mysql00.username}"
    password: "SMS{mysql00.password}"
```

或：

```yaml
custom:
  secret: "SMS{mysql00}"
```

规则说明：

- `SMS{label.key}`：显式指定标签和 JSON 字段
- `SMS{label}`：仅指定标签，字段名由 `ksp.sms.jsonKey` 决定

## 5. 运行期刷新配置

```yaml
ksp:
  sms:
    refresh:
      enabled: true
      intervalSeconds: 10
      lifecycleDriven: true
      renewTtlSeconds: 300
      maxRenewFailures: 2
      leadTimeSeconds: 300
      refreshStatic: false
      datasourceEnabled: false
      hikariEnabled: false
      validateOnRotate: false
      validationQuery: "SELECT 1"
      evictDelayMs: 0
```

重点说明：

- `lifecycleDriven=true`：启用标准动态凭据状态机
- `renewTtlSeconds=300`：每次主动续约请求的 `renew_ttl`
- `leadTimeSeconds`：只在 `lifecycleDriven=false` 或静态凭据路径中作为旧逻辑兜底
- `maxRenewFailures=2`：连续主动续约失败达到阈值后直接转 `reapply`
- 如果只希望 starter 提供最新凭据，由业务系统自己更新连接池，建议配置：
  - `datasourceEnabled=false`
  - `hikariEnabled=false`

### 5.1 常用字段含义

下面这组配置是客户最常见、也最容易问到的一组参数：

```yaml
ksp:
  sms:
    url: ${KSP_SMS_URL:https://192.168.0.130:8390}
    domain: ${KSP_SMS_DOMAIN:1}
    appKey: ${KSP_SMS_APP_KEY:admin}
    appSecret: ${KSP_SMS_APP_SECRET:******}
    version: ${KSP_SMS_VERSION:2}
    enabled: true
    cipherPrefix: SMS{
    jsonKey: value
    refresh:
      enabled: true
      intervalSeconds: 10
      leadTimeSeconds: 300
      refreshStatic: true
      datasourceEnabled: false
      hikariEnabled: false
      validateOnRotate: false
      validationQuery: "SELECT 1"
      evictDelayMs: 0
      lifecycleDriven: true
      renewTtlSeconds: ${KSP_SMS_RENEW_TTL:300}
      maxRenewFailures: 2
```

字段说明如下：

- `ksp.sms.url`：SMS 服务地址，starter 启动登录、取密、续约、重申都走这个地址
- `ksp.sms.domain`：SMS 域 ID，需与平台中的目标域保持一致
- `ksp.sms.appKey`：SMS 登录账号
- `ksp.sms.appSecret`：SMS 登录密钥
- `ksp.sms.version`：短信平台接口版本，当前示例使用 `2`
- `ksp.sms.enabled`：是否启用 starter；为 `false` 时不执行解密和刷新
- `ksp.sms.cipherPrefix`：密文占位前缀，默认 `SMS{`
- `ksp.sms.jsonKey`：当配置写成 `SMS{label}` 且返回值是 JSON 时，默认提取的字段名
- `ksp.sms.refresh.enabled`：是否启用运行期刷新线程
- `ksp.sms.refresh.intervalSeconds`：刷新线程轮询周期，单位秒
- `ksp.sms.refresh.leadTimeSeconds`：旧版提前量参数；在 `lifecycleDriven=false` 或静态凭据路径中作为兜底使用
- `ksp.sms.refresh.refreshStatic`：是否允许静态凭据参与运行期刷新；动态凭据通常保持 `true`，纯静态场景可按需关闭
- `ksp.sms.refresh.datasourceEnabled`：是否让 starter 直接更新通用 `DataSource`
- `ksp.sms.refresh.hikariEnabled`：是否让 starter 直接更新 Hikari 连接池
- `ksp.sms.refresh.validateOnRotate`：切换数据源参数后是否立即执行校验 SQL
- `ksp.sms.refresh.validationQuery`：数据源校验 SQL，默认 `SELECT 1`
- `ksp.sms.refresh.evictDelayMs`：连接驱逐延迟时间，单位毫秒；`0` 表示立即驱逐
- `ksp.sms.refresh.lifecycleDriven`：是否按动态凭据生命周期字段驱动 `renew / reapply`，动态凭据场景建议开启
- `ksp.sms.refresh.renewTtlSeconds`：每次主动续约请求的 `renew_ttl`；建议与平台创建实例时的 `ttl` 保持一致
- `ksp.sms.refresh.maxRenewFailures`：连续主动续约失败达到阈值后，starter 会转为 `reapply`

推荐口径：

- 如果客户希望 `starter` 只负责“提供最新凭据”，建议：
  - `datasourceEnabled=false`
  - `hikariEnabled=false`
  - 由业务自己实现连接池更新
- 如果客户平台侧创建动态实例时设置了 `ttl=300`，建议 `renewTtlSeconds` 也配置为 `300`

## 6. 动态凭据运行逻辑

动态凭据路径按以下顺序执行：

1. 周期性 `fetchCipher`
2. 读取生命周期字段
3. 判断是否进入主动窗口
4. 优先 `renew`
5. 如果本次再续一定超过 `max_ttl`，则直接 `reapply`
6. 动作完成后再次 `fetchCipher`
7. 刷新本地 `created_time / expire_time / max_ttl`

主动窗口口径：

- 窗口基数 = `min(renew_ttl, 当前实例初始可用时长)`
- 剩余有效期 `<= 窗口基数 * 30%` 时进入主动窗口

提前重申口径：

- 若 `expire_time + renew_ttl > created_time + max_ttl`
- 则不再发 `renew`
- 直接提前 `reapply`

目标只有一个：

- 在旧凭据失效前拿到下一份有效凭据，保障最终客户业务不断

## 7. 静态凭据与动态凭据的区别

静态凭据：

- 通常没有有效期轮转语义
- 更多依赖启动期解密
- `refreshStatic=false` 时默认不参与运行期刷新

动态凭据：

- 有 `created_time / expire_time / max_ttl`
- 客户端需要在运行期自主判断 `renew / reapply`
- 推荐始终启用 `lifecycleDriven=true`

## 8. 数据源热更新

当开启以下配置后：

- `datasourceEnabled=true`
- `hikariEnabled=true`

Starter 会在凭据实际变化时尝试：

- 更新 Hikari 数据源用户名密码
- 驱逐旧连接
- 可选执行连接校验

这样业务应用无需重启即可切换到新凭据。

但需要注意：

- 这属于可选能力，不应默认视为所有客户场景的推荐边界
- 对于生产交付，更推荐由业务系统自己控制连接池切换时机、灰度策略和失败回退
- 如果客户已有成熟的数据源治理机制，建议仅使用 starter 的“取凭据 + 刷新最新值”能力

## 9. 常见日志

`1.5.0` 默认采用脱敏日志口径，不输出凭据明文、密文、签名串、请求体和响应体。

可用于排障的关键日志包括：

- `KSP SMS startup decryption completed, resolved ... encrypted properties`
- `KSP SMS startup decryption failed; keeping original placeholder`
- `KSP SMS proactive renew triggered during lifecycle refresh`
- `KSP SMS proactive reapply triggered during lifecycle refresh`
- `KSP SMS datasource credentials updated`

如果需要更细粒度的联调日志，建议仅在测试环境临时提升日志级别，并确认输出内容仍满足客户侧脱敏要求。

## 10. 接入建议

推荐接入口径如下：

1. `appKey/appSecret` 通过环境变量注入
2. 动态凭据的 `renewTtlSeconds` 与服务端单轮 `ttl` 保持一致
3. 运行期启用 `lifecycleDriven=true`
4. 先在测试环境验证完整的 `renew -> renew -> reapply` 节奏
5. 明确客户业务是否自行控制连接池切换，避免职责边界不清
6. 若客户自行控制连接池切换，建议业务侧至少补齐：
   - 读取 starter 刷新后的最新凭据
   - 发现凭据变化后按业务策略更新连接池
   - 切换失败后的回退与告警
7. 如果确需使用 starter 自带的数据源热更新能力，再单独验证该链路

### 10.1 业务侧监测变化的参考逻辑

如果客户选择“starter 只负责提供最新凭据，由业务自己更新连接池”，
业务侧可参考下面这类逻辑：

1. 启动时用当前 `Environment` 中的 `spring.datasource.username/password` 初始化业务连接池
2. 定时读取 starter 刷新后的最新用户名和密码
3. 将“最新值”与“业务当前已应用值”做对比
4. 无变化时不执行任何切换
5. 检测到变化时，先创建新的业务连接池
6. 新连接池验证通过后，再替换旧连接池
7. 替换完成后关闭旧连接池，并记录同步时间与同步结果

伪代码示例：

```java
@Scheduled(fixedDelay = 5000)
public void autoRefreshIfNeeded() {
    String latestUser = environment.getProperty("spring.datasource.username");
    String latestPass = environment.getProperty("spring.datasource.password");

    if (same(appliedUser, latestUser) && same(appliedPass, latestPass)) {
        return;
    }

    DataSource next = buildDataSource(latestUser, latestPass);
    validate(next);

    DataSource previous = currentDataSource;
    currentDataSource = next;
    appliedUser = latestUser;
    appliedPass = latestPass;

    close(previous);
}
```

这类实现的核心点是：

- starter 只刷新最新凭据
- 业务自己决定切换时机
- 业务自己保证切换过程中的平滑性和回退能力

## 11. 相关文档

- 总体方案与最佳实践：`Java spring boot标准框架对接SMS最佳实践.md`
- 联调与实施步骤：`SMS动态凭据联调实操说明.md`





