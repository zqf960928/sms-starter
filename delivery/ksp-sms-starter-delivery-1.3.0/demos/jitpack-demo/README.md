# javajitpacksms

这是一个独立的 Spring Boot 测试项目，用于验证通过 JitPack 方式引入 KSP SMS starter 后：

- Maven 能否正常解析 JitPack 依赖
- `SMS{...}` 配置能否在启动期被解密
- 业务侧能否基于解密后的值初始化自己的 MySQL 连接池
- 是否能查询 `test.ksp_config` 的记录数

## 当前依赖

`pom.xml` 中使用的是：

```xml
<repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
</repository>

<dependency>
    <groupId>com.github.zqf960928</groupId>
    <artifactId>sms-starter</artifactId>
    <version>v1.4.0</version>
</dependency>
```

如果后续你发布了新的 Tag，只需要改 `ksp.sms.jitpack.version`。

## 启动

```powershell
mvn spring-boot:run
```

默认端口：`18084`

## 页面和接口

- 页面：`http://127.0.0.1:18084/`
- 健康检查：`GET /demo/health`
- 查看 starter 解密结果：`GET /demo/config`
- 初始化并检查业务连接池：`GET /demo/db/ping`
- 查看业务连接池状态：`GET /demo/business/status`
- 让业务连接池重新读取当前凭据：`GET /demo/business/sync`
- 立即触发一次业务侧凭据检查：`GET /demo/business/check-now`
- 查看 starter 刷新管理状态：`GET /demo/refresh/status`
- 手动触发一次 starter 刷新 tick：`GET /demo/refresh/run-once`
- 页面聚合数据：`GET /demo/dashboard`

## 说明

这个项目定位是 JitPack 接入验证，因此重点是：

- 依赖来源改成 JitPack
- 仍然使用 MySQL `test` 库做业务验证
- 业务侧通过定时监测最新凭据摘要，在新连接池验证成功后切换主池，并延迟回收旧池

其中 starter 负责凭据刷新与向环境注入最新值，demo 负责演示业务侧如何监测凭据变化并进行数据库平滑切换。


