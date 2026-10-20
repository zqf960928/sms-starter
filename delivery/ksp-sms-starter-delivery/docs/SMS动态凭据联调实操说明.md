# SMS动态凭据联调实操说明

## 1. 适用场景

本文档用于本地或测试环境联调 SMS 动态凭据场景，目标是验证以下链路是否符合预期：

- 首次 `fetchCipher` 成功拿到动态凭据
- 客户端在剩余有效期进入 `30%` 主动窗口后先尝试 `renew`
- 若本次再续一定会超过 `max_ttl`，则提前 `reapply`
- `reapply` 成功后再次 `fetchCipher`，确保旧凭据失效前已经拿到下一份有效凭据

## 2. 联调前准备

1. 服务端已创建或准备好测试标签，例如 `mysql00`
2. 明确本次测试参数：
   - `ttl`
   - `max_ttl`
   - 标签名
3. 本地测试工程已配置：

```yaml
ksp:
  sms:
    url: ${KSP_SMS_URL}
    domain: ${KSP_SMS_DOMAIN:1}
    appKey: ${KSP_SMS_APP_KEY}
    appSecret: ${KSP_SMS_APP_SECRET}
    refresh:
      enabled: true
      intervalSeconds: 10
      lifecycleDriven: true
      renewTtlSeconds: 300
      maxRenewFailures: 2
```

注意：

- `renewTtlSeconds` 建议与服务端该凭据的单轮 `ttl` 一致
- 动态凭据联调时，推荐 `intervalSeconds=10`
- `lifecycleDriven=true` 是本次标准联调口径

## 3. 启动测试程序

在测试工程目录执行：

```powershell
mvn spring-boot:run
```

如需显式指定环境变量，可在启动前设置：

```powershell
$env:KSP_SMS_URL='https://<sms-host>'
$env:KSP_SMS_DOMAIN='1'
$env:KSP_SMS_APP_KEY='<app-key>'
$env:KSP_SMS_APP_SECRET='<app-secret>'
```

## 4. 运行期行为口径

客户端运行期状态机如下：

1. 每轮 tick 先调用 `/open_api/credential/cipher`
2. 从响应中读取：
   - `created_time`
   - `expire_time`
   - `max_ttl`
   - `can_renew`
   - `need_reapply`
   - `next_action`
3. 主动窗口判定：
   - 窗口基数 = `min(renew_ttl, 当前实例初始可用时长)`
   - 剩余有效期 `<= 窗口基数 * 30%` 时进入主动窗口
4. 进入主动窗口后：
   - 能续约则先 `renew`
   - 若 `expire_time + renew_ttl > created_time + max_ttl`，直接 `reapply`
5. `renew` 或 `reapply` 成功后，必须再次 `fetchCipher`，刷新本地生命周期缓存

## 5. 关键日志关键字

联调时重点关注以下日志：

- `fetchCipher label=mysql00`
- `label mysql00 proactive renew`
- `label mysql00 proactive reapply`
- `renewCredential label=mysql00`
- `reapplyCredential label=mysql00`
- `expire_time`
- `created_time`
- `max_ttl`

推荐观察方法：

```powershell
Get-Content <output.log> -Wait | Select-String "mysql00|proactive renew|proactive reapply|renewCredential|reapplyCredential|expire_time|created_time|max_ttl"
```

## 6. 以 `ttl=5m / max_ttl=20m` 为例的预期

若服务端配置为：

- `ttl = 300s`
- `max_ttl = 1200s`
- `renew_ttl = 300s`

则客户端预期行为为：

1. 新实例创建后，先正常使用，不应刚创建就立即 `renew`
2. 每轮在剩余约 `90s` 时进入主动窗口
3. 只要 `expire_time + 300 <= created_time + 1200`，就继续 `renew`
4. 当再续一次会超过 `created_time + 1200` 时，提前 `reapply`

一句话判断标准：

- 不是等实例 `Revoked` 后再补救
- 而是要在旧实例失效前拿到下一份有效凭据

## 7. 常见问题

### 7.1 刚创建的新实例很快就触发 renew

这通常不符合标准口径。动态凭据路径应只按 `30%` 主动窗口判断，不应再叠加 `leadTimeSeconds`。

### 7.2 一直不 renew，直接 reapply

重点检查：

- 服务端返回的 `can_renew`
- 服务端返回的 `need_reapply`
- 是否已接近 `max_ttl`
- `renew` 是否返回 `6130`

### 7.3 `fetchCipher` 返回 `6116`

通常表示：

- 标签不存在
- 当前账号没有权限
- 用户在控制台刚进行了重建或切换，客户端正好命中了短暂过渡窗口

### 7.4 `cipher` 登录失败 `4314`

重点核对：

- `url`
- `appKey`
- `appSecret`
- 所属 `domain`

## 8. 联调完成的验收标准

满足以下几点即可认为动态凭据联调通过：

1. 首次拉取成功
2. 新实例不会刚创建就立即续约
3. 客户端能在 `30%` 主动窗口内触发 `renew`
4. 到达续约极限前，客户端会主动 `reapply`
5. `reapply` 后能立即重新拿到新实例密文
6. 整个过程中业务侧不会因为旧凭据失效而中断
