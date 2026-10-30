# Java SMS Adapter Demo

这是一个独立于 `ksp-sms` 仓库的标准 Spring Boot 示例工程，用来验证
`ksp-sms-spring-boot-starter` 的客户接入方式是否可用。

## 目标

- 模拟客户的标准 Spring Boot 接入方式
- 验证启动期 `SMS{...}` 自动解密
- 验证运行期 `renew / reapply / refetch` 刷新链路
- 验证 starter 只负责提供最新凭据，业务侧自己决定何时切换连接池
- 作为后续交付给客户时的最小可运行示例

## 目录

```text
javasmsadpter/
├── pom.xml
├── README.md
└── src/
    └── main/
        ├── java/com/example/javasmsadpter/
        │   ├── JavaSmsAdpterApplication.java
        │   └── SmsDebugController.java
        └── resources/
            └── application.yml
```

## 使用前准备

先把 starter 安装到本机 Maven 本地仓库，二选一即可：

### 方式 1：直接安装当前源码

在 `E:\traecn\ksp-sms` 下执行：

```powershell
mvn install
```

### 方式 2：安装交付包中的 jar

执行：

```powershell
E:\traecn\ksp-sms\delivery\ksp-sms-starter-delivery\install-local.bat
```

## 配置说明

`src/main/resources/application.yml` 里已经预留了环境变量覆盖方式，重点配置：

- `KSP_SMS_URL`
- `KSP_SMS_DOMAIN`
- `KSP_SMS_APP_KEY`
- `KSP_SMS_APP_SECRET`
- `KSP_SMS_VERSION`
- `KSP_SMS_RENEW_TTL`

当前默认已固化为你提供的联调参数：

- `url = https://192.168.0.130:8390`
- `domain = 1`
- `appKey = admin`
- `appSecret = 9d0022...c7acb5da`
- `version = 2`
- `label = mysql-sh`
- `renewTTL = 3600`
- `spring.datasource.url = jdbc:mysql://192.168.0.130:3307/test?...`

如果后续联调环境变化，你可以直接改 `application.yml`，
或者继续使用环境变量覆盖。

当前 demo 已引入 `spring-boot-starter-jdbc + mysql-connector-j`。

其中：

- starter 侧保持：
  - `ksp.sms.refresh.datasourceEnabled=false`
  - `ksp.sms.refresh.hikariEnabled=false`
- demo 自己额外维护了一份“业务连接池”，用于演示：
  - starter 刷新出新凭据后，业务仍可继续使用旧连接池
  - 业务通过定时检测 starter 最新值，自动决定是否刷新自己的连接池
  - 也可以在联调时手动触发一次同步

也就是说，starter 只负责“从 SMS 获取并刷新最新值”，
连接池切换策略由业务自己掌控。

## 启动方式

在当前目录执行：

```powershell
mvn spring-boot:run
```

Linux/macOS：

```bash
mvn spring-boot:run
```

默认端口：`18081`

## 调试接口

- `GET /demo/health`
  - 基础存活检查
- `GET /demo/config`
  - 查看当前 Environment 中的解密结果和启动快照
- `GET /demo/refresh/status`
  - 查看刷新管理器运行状态与缓存
- `GET /demo/refresh/run-once`
  - 手动触发一次刷新 tick，便于联调时立即观察行为
- `GET /demo/db/ping`
  - 查看当前业务连接池是否可用，以及 `ksp_config` 总记录数
- `GET /demo/business/sync`
  - 让业务连接池主动同步到 starter 当前最新凭据
- `GET /demo/business/check-now`
  - 让业务立刻执行一次“检测 starter 最新值并决定是否切换”的逻辑
- `GET /demo/dashboard`
  - 返回总览数据，供页面展示

页面地址：

- `GET /index.html`

## 说明

这个工程当前默认走“本地 Maven 安装包”方式，便于你先完整实操一遍。

如果后续你把 starter 发布到 JitPack，只需要把 `pom.xml` 里的依赖坐标替换成
实际发布坐标，再补上对应 `repository` 配置即可。

## 推荐阅读

- [本地包集成测试手册](file:///e:/traecn/javasmsadpter/本地包集成测试手册.md)
