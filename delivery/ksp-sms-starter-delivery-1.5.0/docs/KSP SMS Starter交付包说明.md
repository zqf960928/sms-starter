# KSP SMS Starter交付包说明

## 1. 适用场景

本文档用于把 `ksp-sms-spring-boot-starter` 以离线交付包的方式提供给其他同事或客户使用。

适用于以下场景：

- 对方机器暂时不能访问 JitPack、Nexus 或公司 Maven 私服
- 当前版本仍在联调阶段，先以本地 Maven 仓库方式试用
- 需要给实施、测试或客户现场快速分发 starter 包

## 1.1 1.5.0 默认交付口径

`1.5.0` 版本建议按以下口径向客户交付：

- starter 负责：登录 SMS、获取凭据、在运行期执行 `renew / reapply / refetch`、把最新值刷新到 Spring `Environment`
- 业务系统负责：决定何时切换新老凭据、如何平滑更新连接池、旧连接保留多久、切换失败后的回退与告警
- starter 自带的 `DataSource / Hikari` 热更新能力保留为可选项，不作为默认推荐边界

默认推荐配置：

```yaml
ksp:
  sms:
    refresh:
      datasourceEnabled: false
      hikariEnabled: false
```

如果客户确实需要由 starter 直接更新连接池，应在接入前明确该职责边界，并单独验证热更新链路。

## 2. 推荐交付包目录结构

建议打包成如下目录：

```text
ksp-sms-starter-delivery/
├── README.md
├── install-local.bat
├── install-local.sh
├── artifacts/
│   ├── ksp-sms-spring-boot-starter-1.5.0.jar
│   └── pom.xml
├── docs/
│   ├── KSP SMS Starter使用说明.md
│   ├── KSP SMS Starter交付包说明.md
│   ├── SMS动态凭据联调实操说明.md
│   ├── Java spring boot标准框架对接SMS最佳实践.md
│   └── 本地包集成测试手册.md
├── examples/
│   └── application.yml.example
└── demos/
    ├── local-package-demo/
    │   ├── pom.xml
    │   ├── README.md
    │   └── src/
    └── jitpack-demo/
        ├── pom.xml
        ├── README.md
        └── src/
```

## 3. 每个文件的作用

### 3.1 `README.md`

交付包入口说明，建议包含：

- 这个包是做什么的
- 适用版本
- 安装步骤
- Maven 依赖写法
- 常见问题

### 3.2 `artifacts/ksp-sms-spring-boot-starter-1.5.0.jar`

Starter 主体 jar 包。

### 3.3 `artifacts/pom.xml`

强烈建议和 jar 一起交付。

原因：

- 这个 starter 自身可能带有传递依赖
- 使用 `pom.xml` 安装到本地仓库时，Maven 可以同时记录依赖信息
- 只给 jar 不给 pom，后续别人项目里可能出现依赖不完整的问题

### 3.4 `install-local.bat`

给 Windows 用户一键安装到本地 Maven 仓库。

交付包可以放在任意目录执行，但请保持交付包内部目录结构不变。

### 3.5 `install-local.sh`

给 Linux/macOS 用户一键安装到本地 Maven 仓库。

交付包可以放在任意目录执行，但请保持交付包内部目录结构不变。

### 3.6 `docs/`

放接入、联调、方案类说明文档，方便交付对象按角色查阅。

### 3.7 `examples/application.yml.example`

放一份最小可运行配置样例，方便客户或同事快速接入。

### 3.8 `demos/local-package-demo/`

本地包接入 Demo，演示客户通过本地 Maven 仓库安装 starter 后如何接入、如何查看刷新状态、如何由业务侧消费最新凭据。

### 3.9 `demos/jitpack-demo/`

JitPack 接入 Demo，演示客户通过 JitPack 方式依赖 starter 后如何快速验证启动期解密、运行期刷新和业务侧连接池接入。

## 4. 推荐 README 内容

建议 `README.md` 至少包含以下内容：

```md
# KSP SMS Starter 交付包

## 版本
- artifactId: ksp-sms-spring-boot-starter
- version: 1.5.0

## 安装
### Windows
双击执行 install-local.bat

### Linux/macOS
执行 sh install-local.sh

## Maven 依赖
<dependency>
    <groupId>com.ad</groupId>
    <artifactId>ksp-sms-spring-boot-starter</artifactId>
    <version>1.5.0</version>
</dependency>
```

## 5. 推荐安装脚本

### 5.1 Windows 安装脚本 `install-local.bat`

```bat
@echo off
setlocal

set BASE_DIR=%~dp0
mvn install:install-file ^
  -Dfile="%BASE_DIR%artifacts\\ksp-sms-spring-boot-starter-1.5.0.jar" ^
  -DpomFile="%BASE_DIR%artifacts\\pom.xml"

echo.
echo Installed ksp-sms-spring-boot-starter to local Maven repository.
pause
```

### 5.2 Linux/macOS 安装脚本 `install-local.sh`

```bash
#!/usr/bin/env bash
set -e

BASE_DIR="$(cd "$(dirname "$0")" && pwd)"

mvn install:install-file \
  -Dfile="${BASE_DIR}/artifacts/ksp-sms-spring-boot-starter-1.5.0.jar" \
  -DpomFile="${BASE_DIR}/artifacts/pom.xml"

echo "Installed ksp-sms-spring-boot-starter to local Maven repository."
```

## 6. 对方机器安装步骤

### 6.1 前提

对方机器需要先安装：

- JDK
- Maven

并且命令行可直接执行：

```bash
mvn -v
```

### 6.2 安装

对方拿到交付包后：

1. 解压目录
2. 可将交付包放在任意目录，例如桌面、临时目录或应用安装目录旁
3. 不要修改交付包内部目录结构
4. Windows 执行 `install-local.bat`
5. Linux/macOS 执行 `sh install-local.sh`
6. 安装脚本会自动基于脚本所在目录定位 `artifacts/` 下的 jar 和 pom
7. 安装完成后，在自己项目中加入依赖：
```xml
<dependency>
    <groupId>com.ad</groupId>
    <artifactId>ksp-sms-spring-boot-starter</artifactId>
    <version>1.5.0</version>
</dependency>
```


## 7. application.yml 示例

建议在 `examples/application.yml.example` 中提供：

```yaml
ksp:
  sms:
    enabled: true
    url: ${KSP_SMS_URL:https://192.168.0.130:8390}
    domain: ${KSP_SMS_DOMAIN:1}
    appKey: ${KSP_SMS_APP_KEY}
    appSecret: ${KSP_SMS_APP_SECRET}
    version: ""
    cipherPrefix: "SMS{"
    jsonKey: "value"
    refresh:
      enabled: true
      intervalSeconds: 10
      lifecycleDriven: true
      renewTtlSeconds: 300
      maxRenewFailures: 2
      datasourceEnabled: false
      hikariEnabled: false
```

如果客户希望 starter 只负责提供最新凭据、由业务系统自己控制连接池切换，
建议在示例中保持 `datasourceEnabled=false`、`hikariEnabled=false`。
如果客户确实需要 starter 直接接管 Hikari 热更新，再单独切换为 `true` 并验证该链路。

`1.5.0` 同时默认采用脱敏日志口径，不输出凭据明文、密文、签名串、请求体和响应体。

## 8. 推荐一起交付的文档

建议把以下三份一起放进交付包：

- `KSP SMS Starter使用说明.md`
- `KSP SMS Starter交付包说明.md`
- `SMS动态凭据联调实操说明.md`
- `Java spring boot标准框架对接SMS最佳实践.md`
- `本地包集成测试手册.md`

## 9. 最简交付版

如果你只想最小交付，也至少要包含这几个文件：

```text
ksp-sms-starter-delivery/
├── install-local.bat
├── artifacts/
│   ├── ksp-sms-spring-boot-starter-1.5.0.jar
│   └── pom.xml
└── README.md
```

## 10. 建议

当前这种“交付 jar + 安装到本地仓库”的方式，适合：

- 小范围试用
- 联调验证
- 客户现场临时安装

如果后续需要长期分发给多台机器，建议再升级到：

- JitPack
- 公司 Maven 私服
- Nexus / Artifactory

这样版本管理、升级和依赖分发都会更稳定。



