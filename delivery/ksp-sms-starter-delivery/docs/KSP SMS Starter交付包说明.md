# KSP SMS Starter交付包说明

## 1. 适用场景

本文档用于把 `ksp-sms-spring-boot-starter` 以离线交付包的方式提供给其他同事或客户使用。

适用于以下场景：

- 对方机器暂时不能访问 JitPack、Nexus 或公司 Maven 私服
- 当前版本仍在联调阶段，先以本地 Maven 仓库方式试用
- 需要给实施、测试或客户现场快速分发 starter 包

## 2. 推荐交付包目录结构

建议打包成如下目录：

```text
ksp-sms-starter-delivery/
├── README.md
├── install-local.bat
├── install-local.sh
├── artifacts/
│   ├── ksp-sms-spring-boot-starter-1.4.0.jar
│   └── pom.xml
├── docs/
│   ├── KSP SMS Starter使用说明.md
│   ├── SMS动态凭据联调实操说明.md
│   └── Java spring boot标准框架对接SMS最佳实践.md
└── examples/
    └── application.yml.example
```

## 3. 每个文件的作用

### 3.1 `README.md`

交付包入口说明，建议包含：

- 这个包是做什么的
- 适用版本
- 安装步骤
- Maven 依赖写法
- 常见问题

### 3.2 `artifacts/ksp-sms-spring-boot-starter-1.4.0.jar`

Starter 主体 jar 包。

### 3.3 `artifacts/pom.xml`

强烈建议和 jar 一起交付。

原因：

- 这个 starter 自身可能带有传递依赖
- 使用 `pom.xml` 安装到本地仓库时，Maven 可以同时记录依赖信息
- 只给 jar 不给 pom，后续别人项目里可能出现依赖不完整的问题

### 3.4 `install-local.bat`

给 Windows 用户一键安装到本地 Maven 仓库。

### 3.5 `install-local.sh`

给 Linux/macOS 用户一键安装到本地 Maven 仓库。

### 3.6 `docs/`

放接入、联调、方案类说明文档，方便交付对象按角色查阅。

### 3.7 `examples/application.yml.example`

放一份最小可运行配置样例，方便客户或同事快速接入。

## 4. 推荐 README 内容

建议 `README.md` 至少包含以下内容：

```md
# KSP SMS Starter 交付包

## 版本
- artifactId: ksp-sms-spring-boot-starter
- version: 1.4.0

## 安装
### Windows
双击执行 install-local.bat

### Linux/macOS
执行 sh install-local.sh

## Maven 依赖
<dependency>
    <groupId>com.ad</groupId>
    <artifactId>ksp-sms-spring-boot-starter</artifactId>
    <version>1.4.0</version>
</dependency>
```

## 5. 推荐安装脚本

### 5.1 Windows 安装脚本 `install-local.bat`

```bat
@echo off
setlocal

set BASE_DIR=%~dp0
mvn install:install-file ^
  -Dfile="%BASE_DIR%artifacts\\ksp-sms-spring-boot-starter-1.4.0.jar" ^
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
  -Dfile="${BASE_DIR}/artifacts/ksp-sms-spring-boot-starter-1.4.0.jar" \
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
2. Windows 执行 `install-local.bat`
3. Linux/macOS 执行 `sh install-local.sh`
4. 安装完成后，在自己项目中加入依赖：

```xml
<dependency>
    <groupId>com.ad</groupId>
    <artifactId>ksp-sms-spring-boot-starter</artifactId>
    <version>1.4.0</version>
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
      datasourceEnabled: true
      hikariEnabled: true
```

## 8. 推荐一起交付的文档

建议把以下三份一起放进交付包：

- [KSP SMS Starter使用说明.md](file:///E:/traecn/ksp-sms/docs/KSP%20SMS%20Starter%E4%BD%BF%E7%94%A8%E8%AF%B4%E6%98%8E.md)
- [SMS动态凭据联调实操说明.md](file:///E:/traecn/ksp-sms/docs/SMS%E5%8A%A8%E6%80%81%E5%87%AD%E6%8D%AE%E8%81%94%E8%B0%83%E5%AE%9E%E6%93%8D%E8%AF%B4%E6%98%8E.md)
- [Java spring boot标准框架对接SMS最佳实践.md](file:///E:/traecn/ksp-sms/docs/Java%20spring%20boot%E6%A0%87%E5%87%86%E6%A1%86%E6%9E%B6%E5%AF%B9%E6%8E%A5SMS%E6%9C%80%E4%BD%B3%E5%AE%9E%E8%B7%B5.md)

## 9. 最简交付版

如果你只想最小交付，也至少要包含这几个文件：

```text
ksp-sms-starter-delivery/
├── install-local.bat
├── artifacts/
│   ├── ksp-sms-spring-boot-starter-1.4.0.jar
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


