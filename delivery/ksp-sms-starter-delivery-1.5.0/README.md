# KSP SMS Starter 交付包

## 内容说明

本交付包用于把 `ksp-sms-spring-boot-starter` 安装到本机 Maven 本地仓库，并配套提供接入与联调文档。

当前交付包内容包括：

- `artifacts/`：starter 的 `jar` 和 `pom.xml`
- `docs/`：方案说明、联调说明、使用说明、交付包说明
- `examples/`：最小配置样例
- `demos/local-package-demo/`：本地包接入 Demo
- `demos/jitpack-demo/`：JitPack 接入 Demo
- `install-local.bat`：Windows 本地安装脚本
- `install-local.sh`：Linux/macOS 本地安装脚本

当前交付版本：

- starter：`1.5.0`
- JitPack tag：`v1.5.0`

## 1.5.0 交付说明

`1.5.0` 版本在交付口径上重点保持以下原则：

- 默认按脱敏口径输出日志，不输出凭据明文、密文、签名串、请求体和响应体
- 默认推荐由业务系统自己控制连接池切换时机、灰度策略和失败回退
- starter 保留 `DataSource / Hikari` 热更新能力，但该能力属于可选项，不作为默认交付边界

如果客户希望 starter 只负责提供最新凭据，建议在配置中保持：

```yaml
ksp:
  sms:
    refresh:
      datasourceEnabled: false
      hikariEnabled: false
```

如果客户确实需要由 starter 直接尝试热更新连接池，应在业务侧确认接受该模式后再单独启用并验证。

## 安装方式

### Windows

双击执行：

```bat
install-local.bat
```

### Linux/macOS

执行：

```bash
sh install-local.sh
```

## Maven 依赖

安装完成后，在业务工程里加入：

```xml
<dependency>
    <groupId>com.ad</groupId>
    <artifactId>ksp-sms-spring-boot-starter</artifactId>
    <version>1.5.0</version>
</dependency>
```

## 前提条件

- 已安装 JDK
- 已安装 Maven
- 命令行执行 `mvn -v` 正常

## 推荐阅读顺序

1. `docs/KSP SMS Starter交付包说明.md`
2. `docs/KSP SMS Starter使用说明.md`
3. `docs/本地包集成测试手册.md`
4. `docs/SMS动态凭据联调实操说明.md`
5. `docs/Java spring boot标准框架对接SMS最佳实践.md`

## Demo 使用建议

- 如果客户先走本地包方式，优先查看 `demos/local-package-demo/`
- 如果客户后续准备走 JitPack，查看 `demos/jitpack-demo/`



