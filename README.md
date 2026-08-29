# 得物任务筛选预演

这是一个 Kotlin + Android Views 实现的无障碍辅助工具，用于进入得物的品牌合作任务列表、解析当前可见任务，并按奖励、关键词、样品规格和名额进行本地筛选。

当前版本是**只读预演**：不会点击“报名”“立即报名”或“申请入驻”，也不会提交任何合作申请。项目参考了同目录 APK 的公开界面和运行流程，但没有复制其受保护源码。

## 当前流程

1. 读取并保存筛选条件。
2. 检查无障碍服务和屏幕信息。
3. 启动得物，依次识别“我 → 创作中心 → 玩转收益 → 查看更多 → 品牌合作”。
4. 选择排序方式与产品类目。
5. 从当前任务卡读取标题、奖励、报名人数、名额和截止信息。
6. 排除名额已满、已截止、命中排除词、奖励不在区间或样品规格不匹配的任务。
7. 在本应用中展示扫描数、符合数、排除数和最近结果。
8. 达到设置的列表下滑次数后结束预演。

账号未入驻时，页面底部可能显示“申请入驻”。程序只记录这一状态，不点击该入口。检测到验证码、人机验证、滑块验证等安全页面时也会停止。

## 关键文件

- `app/src/main/java/com/konnisan/dewuauto/automation/AutomationController.kt`：只读导航与扫描状态机
- `app/src/main/java/com/konnisan/dewuauto/automation/TaskCardParser.kt`：任务卡字段解析
- `app/src/main/java/com/konnisan/dewuauto/automation/TaskEligibilityEvaluator.kt`：筛选规则
- `app/src/main/java/com/konnisan/dewuauto/automation/DewuSelectors.kt`：得物页面文字选择器
- `preview/index.html`：可用 Playwright 打开的前端交互预览
- `docs/superpowers/specs/2026-08-29-filter-preview-ui-design.md`：界面与安全边界设计

## 构建与验证

环境：JDK 17、Android Gradle Plugin 8.12.2、Gradle 8.13、compileSdk 36、minSdk 26。

```bat
verify-filter.bat
gradlew.bat assembleDebug --offline
```

Debug APK 输出到：

`app/build/outputs/apk/debug/app-debug.apk`

前端预览可直接打开 `preview/index.html`。安装了 Playwright Node 模块时，可运行 `preview/check-preview.cjs` 验证交互状态及页面中不存在报名类按钮。

## 真机适配说明

得物使用动态 UI，不同账号和版本的可访问节点可能不同。当前已兼容 `已报名：16/20人` 和 `报名：9/40人` 两种人数格式，并同时支持独立报名文字节点和整张任务卡内容描述。若得物后续改版，优先更新 `DewuSelectors.kt` 和解析器测试样例。

普通第三方 Android 应用不能真正强制停止得物；本项目仅通过系统返回、主页和重新拉起完成可恢复导航。它不绕过验证码、不调用私有报名接口、不做多账号批量操作，也不规避平台风控。
