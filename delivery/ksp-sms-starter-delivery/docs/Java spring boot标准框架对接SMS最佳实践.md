# Java spring boot标准框架对接SMS最佳实践

## 1. 背景与目标

本文档面向在标准 Java Spring Boot 框架中接入 KSP SMS 凭据管理系统的场景，给出一套可直接落地的最佳实践：

- 在 `application.yml/properties` 中使用 `SMS{...}` 标记敏感配置，不落盘明文
- 应用启动早期自动解密并注入 Spring Environment，业务代码“零改造”
- 可选启用运行期轮转刷新（适用于会过期的动态凭据）
- 规避敏感信息泄露、提升可运维与可排障性

## 2. 适用范围与约束

适用：

- Spring Boot 标准工程接入（常见 Web/微服务工程）
- 需要在配置中心/配置文件中以占位符形式引用凭据（如数据库账号密码、第三方 API Key、Token 等）

约束：

- 如果启动解密失败，应用是否继续启动取决于接入方策略（建议生产环境明确 fail-fast 或告警策略，见第 6 节）
- 运行期刷新对“运行过程中仍会读取配置”的场景最有效；如果应用在启动时把配置固化进对象，运行期刷新未必自动生效，需要结合具体组件能力评估（例如数据库连接池是否支持热更新）

## 3. 依赖接入

当前 Starter 的 Maven 坐标如下：

```xml
<dependency>
    <groupId>com.ad</groupId>
    <artifactId>ksp-sms-spring-boot-starter</artifactId>
    <version>1.2.0</version>
</dependency>
```

最佳实践：

- 生产环境务必使用明确版本号，避免漂移版本
- 建议在公司级 parent/BOM 中锁定依赖版本，统一管理

## 4. 基础配置（ksp.sms）

在 `application.yml` 中配置 KSP SMS 连接信息与解密规则：

```yaml
ksp:
  sms:
    url: https://<ksp-sms-host>
    domain: 1
    appKey: ${KSP_SMS_APP_KEY}
    appSecret: ${KSP_SMS_APP_SECRET}
    version: ""
    enabled: true
    cipherPrefix: "SMS{"
    jsonKey: "value"
```

字段说明（与实现保持一致）：

- `ksp.sms.enabled`：总开关，false 时不做任何扫描/解密
- `ksp.sms.cipherPrefix`：密文前缀，默认为 `SMS{`，只有以该前缀开头且以 `}` 结尾的值才会被识别
- `ksp.sms.jsonKey`：当 SMS 返回 JSON 时，默认提取字段名

最佳实践：

- `appKey/appSecret` 只允许来自环境变量、启动参数或外部密钥系统注入，禁止写入仓库、镜像层、日志
- 建议对 `url` 使用统一的域名与证书策略，避免各环境自签名证书导致启动失败

## 5. 配置文件中引用凭据（SMS{...} 规范）

### 5.1 支持的格式

Starter 支持两种引用格式：

1. `SMS{label}`：使用全局默认 `ksp.sms.jsonKey`
2. `SMS{label:jsonKey}`：为该引用指定 JSON 提取字段名

示例：

```yaml
spring:
  datasource:
    username: SMS{mysql00:username}
    password: SMS{mysql00:password}

app:
  api:
    token: SMS{api00}
```

### 5.2 JSON 凭据与静态凭据

- 若 SMS 返回内容是 JSON：Starter 会尝试解析 JSON 并按 jsonKey 提取字段
- 若不是 JSON（静态字符串）：Starter 直接使用该字符串作为解密结果

## 6. 启动阶段自动解密（客户视角）

### 6.1 解密发生在什么时候

Starter 会在 Spring Boot 启动阶段尽早执行：读取应用配置（`application.yml/properties` 及其他配置源），识别并处理形如 `SMS{...}` 的密文占位符，把其替换为明文后再提供给 Spring 使用。

- `@Value("${...}")`
- `Environment#getProperty`
- Spring Boot 自动配置（如 DataSource 自动配置）等

### 6.2 扫描规则（会处理哪些配置）

- 仅当配置值满足以下条件时才会触发解密：
  - 以 `ksp.sms.cipherPrefix` 开头（默认 `SMS{`）
  - 以 `}` 结尾
- 解密后的明文会覆盖原配置值，因此应用后续读取到的就是明文

### 6.3 解密失败/超时如何处理（建议策略）

建议生产环境明确以下策略，并在接入评审时达成一致：

- 强安全/强一致场景：建议 fail-fast（解密失败直接终止启动），避免应用“带密文运行”
- 兼容性场景：允许启动但必须告警（启动日志、监控告警），并保证下游组件不会把密文当明文使用
- 超时阈值：建议结合网络与 SMS 服务 SLA 设置，避免启动阶段被长时间阻塞

## 7. 运行期轮转刷新（可选能力）

本项目包含运行期刷新能力，用于应对凭据过期/轮换场景（例如动态数据库凭据）。
自 v1.1 起，运行期轮转刷新逻辑已**与 K8S sms-agent 的 label-driven refresh 行为完全对齐**（见 sms_k8s 仓库 `sms_agent_label_driven_refresh_plan.md` 与 `src/cmd/sms-agent/main.go`）。

### 7.1 启用方式

需要显式打开：

```yaml
ksp:
  sms:
    refresh:
      enabled: true
      # ==== 基础开关（v1.0 已有，v1.1 保留） ====
      intervalSeconds: 60        # tick 间隔，最小 5
      leadTimeSeconds: 300       # lifecycleDriven=false 或静态凭据时使用
      refreshStatic: false
      datasourceEnabled: true
      hikariEnabled: true
      validateOnRotate: false
      validationQuery: "SELECT 1"
      evictDelayMs: 0
      # ==== 新增（v1.1，对齐 sms-agent 动态凭据生命周期策略） ====
      lifecycleDriven: true      # 主开关：true=新状态机(推荐)，false=旧逻辑降级
      renewTtlSeconds: 300       # 每次手动续约希望延长的秒数（renew_ttl），建议与服务端单轮 ttl 一致
      maxRenewFailures: 2        # 连续主动续约失败超限则立即转 reapply
```

装配条件：

- 仅当 `ksp.sms.refresh.enabled=true` 时才会启用运行期刷新能力
- `lifecycleDriven`（默认 true）决定是否启用"对齐 sms-agent 的 cipher→renew/reapply→再 cipher"生命周期状态机

### 7.2 刷新机制说明（v1.1 对齐 sms-agent 的新流程）

#### 架构边界（与 v1.0 保持不变）
- **启动阶段 [EnvironmentPostProcessor]**：仅在应用启动时做"一次性解密 + Environment 注入"，不碰任何续约/重新申请逻辑（与用户判断一致）
- **运行阶段 [KspSmsCredentialRefreshManager]**：每一轮 tick 负责"使用过程中判断续约/重新申请"，本阶段是状态机主流程所在

#### 每轮 tick 的状态机（与 sms-agent 的 `fetchAndDecrypt` 对齐）
1. **先 fetchCipher**：调 `/open_api/credential/cipher`，同时拿到密文段（`cipher_key/ciphertext`）和全量生命周期字段（`expire_time/created_time/max_ttl/can_renew/need_reapply/next_action/status/status_msg`；若服务端已扩展也可兼容 `id`）
2. **按优先级决策下一步动作**（对齐 `resolveCipherAction`）：
   - `Disabled` → 无动作
   - `next_action` 非空 → 直接走它（优先级最高，use/renew/reapply）
   - `need_reapply == true` / `can_renew == false` → reapply
   - 以上都没有但没可用密文 → 优先走 renew
   - 否则 use（继续用）
3. **进入主动触发窗口的客户端判断**（对齐 `shouldTriggerProactiveRenew`、`shouldTriggerProactiveReapply`）：
   - 窗口基数取 `min(renewTtlSeconds, 当前实例初始可用时长)`；当剩余可用期 `<= 窗口基数 × 30%` 时进入主动窗口
   - 若 `expire_time + renewTtlSeconds > created_time + max_ttl`（下一次续约一定会超过上限）→ 不 renew，直接主动提前 reapply
   - 每次主动动作后进入 `max(60s, renewTtlSeconds×5%)` 冷却，避免每轮 tick 都刷 renew/reapply
   - `lifecycleDriven=true` 的动态凭据路径**不再叠加** `leadTimeSeconds`；避免新实例刚创建就因为 `leadTimeSeconds=300` 被误判进入主动窗口
4. **无论 renew 还是 reapply 成功后，必须再 fetchCipher 回读最新密文**：绝不直接使用 renew/reapply 接口的返回值当凭据正文
5. **生命周期缓存必须同步刷新**：客户端要把最新 `expire_time / created_time / max_ttl` 写回本地缓存；后续“何时跌破 30% 阈值”的判断，一律基于这组最新生命周期重新计算
6. **解密比对**：解密后的明文与缓存内容不一致才刷新 Environment + Hikari 连接池，避免无谓的连接抖动

#### 关键错误码与兜底链路
- **续约失败 code=6130**：`续约时长超过最大有效期限制` → 立即转 reapply，不再重试续约（对齐 sms-agent 的 `isRenewLimitExceededError`）
- **连续主动续约失败 ≥ `maxRenewFailures`** → 转 reapply
- **全链路失败**：保持上一轮 cache 不变，等下个 tick 再试，不中断应用

#### 与旧逻辑的关系（`lifecycleDriven`）
- `lifecycleDriven=true`（默认）：走上述新状态机（推荐）
- `lifecycleDriven=false`：降级到 v1.0 旧逻辑，只按 `expire_time - now < leadTimeSeconds` 判断；用于服务端暂未部署 renew/reapply 接口的过渡阶段

### 7.3 数据库连接池热更新（Hikari）

当开启 `datasourceEnabled=true`，并且项目使用 HikariCP 连接池时，Starter 可以尝试在不重启应用的情况下更新连接池凭据，并逐步淘汰旧连接。

- 更新连接池的用户名与密码
- 逐步淘汰旧连接，促使新连接使用新凭据
- 可选执行连接校验（`validateOnRotate=true`）
- 失败时回滚到旧账号密码（避免凭据切换导致应用不可用）

最佳实践：

- 生产环境建议将 `validateOnRotate` 配合轻量 `validationQuery` 打开，避免切换到无效凭据导致连接池整体不可用
- `intervalSeconds` 不宜过小，避免频繁登录/拉取造成 SMS 压力
- 动态凭据建议将 `renewTtlSeconds` 设置为与服务端该凭据单轮 ttl 一致的值；例如 ttl=300 秒，则 `renewTtlSeconds` 也建议设为 300
- 若客户目标是“旧凭据失效前已经拿到新凭据”，优先使用 `lifecycleDriven=true`，并让客户端在 30% 主动窗口内先 renew、确定不能 renew 再 proactive reapply

### 7.4 新增配置项（v1.1）速查表

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|-------|------|
| `ksp.sms.refresh.lifecycleDriven` | boolean | `true` | 是否启用对齐 sms-agent 的 cipher→renew/reapply→再 cipher 的生命周期状态机；临时关闭可降级为旧逻辑 |
| `ksp.sms.refresh.renewTtlSeconds` | long | `300` | 每次主动续约请求延长的秒数（`renew_ttl`）；必须 > 0 才会触发主动续约；建议与服务端该凭据的单轮 ttl 保持一致 |
| `ksp.sms.refresh.maxRenewFailures` | int | `2` | 连续主动续约失败 ≥ 该值就不再重试续约，直接转 reapply |

原有配置（`intervalSeconds`、`leadTimeSeconds`、`refreshStatic`、`datasourceEnabled`、`hikariEnabled`、`validateOnRotate`、`validationQuery`、`evictDelayMs`）保留不变；
其中 `leadTimeSeconds` 在 `lifecycleDriven=true` + DbType=动态凭据时不再使用，仅在 `lifecycleDriven=false` 或静态凭据（DbType=0）兜底时生效。

## 7.5 实现文件映射（改造点一览，便于排障）

本次改造涉及的代码位置：

- 新增生命周期响应实体：[CipherLifecycleResult.java](file:///e:/traecn/ksp-sms/src/main/java/com/andang/entity/CipherLifecycleResult.java)
- 新增 reapply 响应实体：[ReapplyResult.java](file:///e:/traecn/ksp-sms/src/main/java/com/andang/entity/ReapplyResult.java)
- 新增配置项：`KspSmsProperties.Refresh` 见 [KspSmsProperties.java](file:///e:/traecn/ksp-sms/src/main/java/com/andang/starter/KspSmsProperties.java#L74-L111)
- 新增接口调用 + token 复用 + RSA 密钥复用：`fetchCipher / renewCredential / reapplyCredential / decryptCipherLifecycle / ensureLogin / isRenewLimitExceeded` 见 [CredentialClient.java](file:///e:/traecn/ksp-sms/src/main/java/com/andang/CredentialClient.java#L432-L661)
- 运行期状态机主流程：`tickLifecycle / tickLegacy / resolveCipherAction / shouldTriggerProactiveRenew / shouldTriggerProactiveReapply / resolveUsableDeadline / wouldProactiveRenewExceedMaxTTL` 见 [KspSmsCredentialRefreshManager.java](file:///e:/traecn/ksp-sms/src/main/java/com/andang/starter/KspSmsCredentialRefreshManager.java#L130-L697)
- 启动阶段 `EnvironmentPostProcessor`：**完全未改动**，保持一次性注入语义
- `KspSmsAutoConfiguration` / `KspSmsCipherScanner`：**完全未改动**，装配条件与扫描逻辑保持一致

## 8. 安全最佳实践（强制要求）

### 8.1 绝不落盘与绝不打印

- 任何环境下禁止把明文凭据写入：
  - git 仓库
  - 制品（jar/镜像）
  - 启动日志/应用日志
- 对于排障日志，只允许打印：
  - 是否命中密文
  - 命中的配置 key（不含值）
  - 错误码/错误信息（不包含敏感载荷）

### 8.2 appKey/appSecret 管理

- 建议通过环境变量注入：`${KSP_SMS_APP_KEY}`、`${KSP_SMS_APP_SECRET}`
- 不建议在默认配置值中提供任何真实凭据；默认值应为空并在启动时校验必填

### 8.3 最小权限与轮换

- appKey 对应应用应遵循最小权限原则，只能访问其需要的 label 范围
- 对静态凭据也建议定期轮换，并结合刷新能力或发布策略

## 9. 运维与排障

### 9.1 自检清单

- Starter 是否生效：启动阶段能看到“已处理密文占位符/已完成解密”相关输出（具体日志由接入方日志规范决定）
- `ksp.sms.enabled=true` 是否开启
- `cipherPrefix` 是否与配置中 `SMS{` 标记一致
- 网络与证书是否可访问 `ksp.sms.url`
- label 是否存在、权限是否允许访问

### 9.2 常见问题

- 未解密（仍为 `SMS{...}`）
  - 可能原因：值没有以 `SMS{` 开头/没有以 `}` 结尾；或该配置来自某些非标准配置来源（导致无法被扫描）
- 解密失败但应用仍启动
  - 这是当前实现的回退策略导致；建议在生产要求 fail-fast 或至少告警
- 运行期刷新不生效
  - 可能原因：未开启 `ksp.sms.refresh.enabled`
  - 可能原因：配置引用不是“配置文件/配置中心中的固定值”（例如运行时拼接），导致无法识别为 `SMS{...}`
  - 数据库未热更新：连接池不是 HikariCP，或连接池不支持运行期更新账号密码

## 10. 推荐的接入模板

### 10.1 最小模板（仅启动解密）

```yaml
ksp:
  sms:
    enabled: true
    url: https://<ksp-sms-host>
    domain: 1
    appKey: ${KSP_SMS_APP_KEY}
    appSecret: ${KSP_SMS_APP_SECRET}
    cipherPrefix: "SMS{"
    jsonKey: "value"

spring:
  datasource:
    username: SMS{mysql00:username}
    password: SMS{mysql00:password}
```

### 10.2 动态凭据模板（启用刷新 + Hikari 热更新）

```yaml
ksp:
  sms:
    enabled: true
    url: https://<ksp-sms-host>
    domain: 1
    appKey: ${KSP_SMS_APP_KEY}
    appSecret: ${KSP_SMS_APP_SECRET}
    cipherPrefix: "SMS{"
    jsonKey: "value"
    refresh:
      enabled: true
      intervalSeconds: 60
      leadTimeSeconds: 300
      datasourceEnabled: true
      hikariEnabled: true
      validateOnRotate: true
      validationQuery: "SELECT 1"
```

## 11. 与 Spring Boot 3 的兼容提示（建议规划项）

如客户项目使用 Spring Boot 3.x，建议在接入前确认 starter 版本的兼容性策略（例如是否提供 Boot 3 对应版本或适配包）。如需适配，请联系交付方提供对应版本。

以上为兼容性提示，不影响当前文档中“客户如何使用”的接入方式。
