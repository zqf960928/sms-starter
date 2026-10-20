# Debug Session: renew-4603
- **Status**: [OPEN]
- **Issue**: `mysql00` 在进入主动续约窗口后调用 `/v1/ksp/open_api/credential/renew` 返回 `code=4603 msg=参数错误,可能存在空值`，预期应续约成功并在回读 cipher 后看到 `expire_time` 延长且 `created_time` 不变。
- **Debug Server**: `http://127.0.0.1:7777/event`
- **Log File**: `.dbg/trae-debug-log-renew-4603.ndjson`

## Reproduction Steps
1. 启动 test 子应用并保持后台持续运行。
2. 等待 `mysql00` 进入主动续约窗口。
3. 观察 refresh tick 的 renew 请求与响应。

## Hypotheses & Verification
| ID | Hypothesis | Likelihood | Effort | Evidence |
|----|------------|------------|--------|----------|
| A | OpenAPI renew 对外设计应支持 `label` 兜底，但部署环境中实际仍把 label-only 请求判成参数错误 | High | Low | Confirmed - label-only body=`{\"label\":\"mysql00\",\"renew_ttl\":300}` 仍稳定 4603 |
| B | cipher 接口对外响应没有返回实例 `id`，导致客户端永远无法提供 renew 所需主键 | High | Low | Confirmed |
| C | renew 请求体还缺少服务端真实校验字段，当前 OpenAPI 文档/仓库实现与部署环境不一致 | Medium | Medium | Inconclusive - `id:0 + label + renew_ttl` 和 label-only 两种 body 都稳定 4603 |
| D | OpenAPI renew 与 cipher 使用的用户上下文不同，导致 renew 内部按 label 解密时拿不到实例 | Medium | Medium | Weakened - 若仅是 label 回查失败，按仓库 [srv_vault.go] 应更可能返回 6116，而非现场 4603 |
| E | 客户端签名或序列化在 renew 接口上与 cipher/reapply 存在差异，服务端把请求判成参数错误 | Low | Medium | Rejected - `cipher`/`reapply` 正常，renew 也进入了业务响应而非签名/鉴权错误 |
| F | 现场部署的 renew 实现/前置校验与仓库当前代码不一致，导致返回码路径和源码分析对不上 | High | Medium | Confirmed - 仓库 `label` fallback 缺数据应回 6116，但现场稳定回 4603 |
| G | `renew=true` 是部署端隐藏必填项，缺失时被判参数错误 | Low | Low | Rejected - 直接对照实验中 `{\"label\":\"mysql00\",\"renew\":true,\"renew_ttl\":300}` 仍返回 4603 |
| H | OpenAPI 可通过 `/credential/info` 反查真实 id 进行对照验证 | Medium | Low | Blocked - 仓库当前 `CredentialOpenListEndpoint` 误绑 `AuthMiddleware`，open_api token 调 `/info` 返回 4604 |

## Log Evidence
- `.dbg/trae-debug-log-renew-4603.ndjson:25` / `31` / `37` / `43`：`cipher` 对 `mysql00` 的生命周期响应稳定为 `code=0`，但 `idField=null` 且 `idNodeExists=false`。
- `.dbg/trae-debug-log-renew-4603.ndjson:26` / `32` / `38` / `44`：客户端构造的 `renew` 请求体为 `{\"label\":\"mysql00\",\"renew_ttl\":300}`，`bodyHasId=false`。
- `.dbg/trae-debug-log-renew-4603.ndjson:27` / `33` / `39` / `45`：上述请求稳定返回 `code=4603 msg=参数错误,可能存在空值`。
- `post-fix` 对比：`.dbg/trae-debug-log-renew-4603.ndjson:1-4` 与 `7-10` / `13-16` 显示，修复后请求体已变为 `{\"id\":0,\"label\":\"mysql00\",\"renew_ttl\":300}`，`bodyHasId=true`，但响应仍稳定为 `code=4603`。
- 结合服务端源码结论：当前 OpenAPI `renew` 在 `id<=0` 时依赖服务端内部 `CredentialDecrypt(label, uid, domain)` 回查实例 ID；而客户端从 `cipher` OpenAPI 又拿不到 `id`，导致这条链路在现网条件下无法闭环。
- 新一轮 label-only 运行日志：`output.log:425` / `447` / `469` / `491` 记录 `renewCredential label=mysql00 id=0 body={label=mysql00, renew_ttl=300}`；对应 `output.log:429` / `451` / `473` / `495` 仍稳定 `code=4603`。
- 服务端仓库代码矛盾点：
  - label-only 入口允许通过 `resolveCredentialOpenRenewTarget`，见 `srv_vault.go:2123-2127,2199-2207`
  - 若 label 回查实例失败，按仓库实现应返回 `6116(RecodeAbsentDataErr)`，见 `srv_vault.go:2148-2167` 与 `utils/error.go:418,799`
  - 现场却返回 `4603(RecodeParamErr)`，见 `utils/error.go:161,728`
- 2026-08-25 11:10 直接签名对照实验（jshell 复用 starter 同签名实现）：
  - CASE1 body=`{"label":"mysql00","renew_ttl":300}` → `code=4603`, `request_id=1dc7ffb5-1466-a599-3903-0df957823a6a`
  - CASE2 body=`{"label":"mysql00","renew":true,"renew_ttl":300}` → `code=4603`, `request_id=d1b32e58-4222-15d2-441c-2642df8813de`
  - 结论：`renew=true` 不是当前 4603 的分水岭
- OpenAPI `/credential/info` 调试结论：
  - 路由实际是 `GET /v1/ksp/open_api/credential/info`，见 `handler.go:1185-1188`
  - 但 endpoint 生成代码把 `CredentialOpenListEndpoint` 绑成了 `AuthMiddleware()` 而不是 `AuthOpenAPIMiddleware()`，见 `endpoint_gen.go:668-674`
  - 因此 open_api login 拿到的 token 调 `/credential/info?label=mysql00` 会返回 `4604 用户身份信息错误`，无法用该接口反查 `id`

## Verification Conclusion
- 对比结论 1：请求体显式带 `id:0` 时稳定 `4603`。
- 对比结论 2：请求体改为纯 `label + renew_ttl` 后仍稳定 `4603`。
- 对比结论 3：请求体进一步补 `renew=true` 后仍稳定 `4603`。
- 当前可以排除“starter 误传 id=0 导致 4603”这一方向。
- 当前也可以排除“缺少 renew=true 导致 4603”这一方向。
- 根因已进一步收敛到**部署端行为与手头仓库源码不一致**，或部署端前置校验存在仓库中未体现的额外条件；继续只改客户端，无法把 `renew` 路径打通到 `code=0`。
