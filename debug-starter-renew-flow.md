# Debug Session: starter-renew-flow
- **Status**: [OPEN]
- **Issue**: 验证本地 Java 测试工程是否使用最新 starter，并在初始化后的真实环境中验证标准 starter 运行期流程：首次获取凭据后，剩余可用期不足 30% 时先续约，续约不允许或失败再重新申请，随后再次获取新凭据并刷新本地生命周期缓存。
- **Debug Server**: http://127.0.0.1:7777/event
- **Log File**: .dbg/trae-debug-log-starter-renew-flow.ndjson

## Reproduction Steps
1. 确认根工程已编译并安装本地 starter 制品。
2. 确认 `test` 测试工程依赖的是本地最新 starter，而不是旧缓存或远端版本。
3. 使用用户提供的本地 SMS 配置启动测试应用。
4. 观察刷新线程在真实环境中的行为，验证 30% 阈值、续约/重申请顺序，以及 `expire_time / created_time / max_ttl` 缓存刷新情况。

## Hypotheses & Verification
| ID | Hypothesis | Likelihood | Effort | Evidence |
|----|------------|------------|--------|----------|
| A | `test` 工程没有使用本地最新 starter 制品，导致联调用的还是旧逻辑 | High | Low | Pending |
| B | `test` 工程已经指向最新 starter，但当前运行中的仍是旧进程/旧 classpath | High | Low | Pending |
| C | 最新 starter 已生效，30% 阈值与缓存刷新逻辑正确，但真实联调还没覆盖到 renew/reapply 窗口 | Medium | Medium | Pending |
| D | 最新 starter 已生效，但真实环境下 renew 仍会返回 `4603`，导致只能停留在服务端兼容性排查 | High | Medium | Pending |

## Log Evidence
- `test/pom.xml` 依赖 `com.ad:ksp-sms-spring-boot-starter:1.0-SNAPSHOT`；根工程 `pom.xml` 坐标完全一致。
- 当前测试进程 classpath 包含 `C:\Users\10158\.m2\repository\com\ad\ksp-sms-spring-boot-starter\1.0-SNAPSHOT\ksp-sms-spring-boot-starter-1.0-SNAPSHOT.jar`，证明测试工程使用的是本地安装的 starter。
- 首轮 mysql00 生命周期：
  - `output.log:417` `expire_time=1787637697`, `created_time=1787637397`, `max_ttl=1200`
  - `output.log:567-572` 在剩余期跌入 30% 窗口时触发 proactive renew，请求体是 `{"label":"mysql00","renew_ttl":300}`，但返回 `4603`
  - `output.log:589-599` 第二次进入窗口后再次 renew，仍返回 `4603`；连续失败达到阈值后自动 fallback 到 reapply，随后 refetch 成功
  - `output.log:599` 回读后的新生命周期变为 `expire_time=1787637934`, `created_time=1787637634`
- 第二轮 mysql00 生命周期：
  - `output.log:858-863` 再次命中 30% 窗口，renew 仍返回 `4603`
  - `output.log:880-890` 连续失败达到阈值后再次 fallback 到 reapply，随后 refetch 成功
  - `output.log:890` 回读后的新生命周期变为 `expire_time=1787638165`, `created_time=1787637865`
- `/debug/refresh/manager-status` 最终状态显示：`mysql00.lastActionAtSeconds=1787637864`、`renewConsecutiveFailures=0`，且缓存中的 `expireTime / createdTime / maxTtl` 已同步刷新。

## Verification Conclusion
- A ✅ Confirmed：`test` 工程确实依赖并加载了本地最新 starter。
- B ✅ Confirmed：旧进程已停止并重新启动，新的测试进程使用本地 snapshot jar。
- C ✅ Confirmed：标准 starter 的运行期主流程已命中真实窗口，能正确执行 `30% -> renew -> (失败后) reapply -> refetch`，并刷新本地生命周期缓存。
- D ✅ Confirmed：当前服务端环境下，label-only renew 仍稳定返回 `4603`；starter 的 reapply 兜底链路可以把凭据继续往后滚动，但 renew 仍未打通。
