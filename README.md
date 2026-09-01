# 得物自动报名 V1

这是一个 Kotlin + Android Views 实现的无障碍自动化工具，用于从达人号“我 → 商单”快捷入口进入任务列表，按本地规则筛选任务并完成报名。

自动报名属于不可撤销操作：启动前必须勾选风险确认并填写样品规格。程序不会点击“申请入驻”，不会绕过验证码，也不会通过坐标猜测报名确认按钮。项目参考了同目录 APK 的公开界面和运行流程，但没有复制其受保护源码。

## 当前流程

1. 读取并保存筛选条件。
2. 检查无障碍服务和屏幕信息。
3. 启动得物：达人号只走“我 → 商单”快捷入口；没有该入口的普通账号可兼容只读入口识别，但不会点击申请入驻。
4. 首先选择“最近发布”与产品类目。
5. 从当前任务卡读取标题、奖励、报名人数、名额和截止信息。
6. 排除名额已满、已截止、命中排除词或奖励不在区间的任务。
7. 打开候选任务详情，继续检查达人要求、发布要求和合作方式；默认屏蔽露脸、真人出镜、拍视频、口播、直播等要求。
8. 详情复核通过后进入报名信息页，校验收货地址，选择配置的样品规格，提交报名，并仅在识别到标准“报名后无法取消”弹窗时确认。
9. 识别到“已报名，待品牌方确认”后返回商单继续查找。
10. “最近发布”达到下滑上限后切换为界面配置的排序；本轮完成后返回首页随机浏览作品，再次进入商单。
11. 报名成功次数达到目标后自动停止。

账号未入驻时，页面底部可能显示“申请入驻”。程序只记录这一状态，不点击该入口。检测到验证码、人机验证、滑块验证等安全页面时也会停止。

## 关键文件

- `app/src/main/java/com/konnisan/dewuauto/automation/AutomationController.kt`：导航、详情复核、报名和循环状态机
- `app/src/main/java/com/konnisan/dewuauto/automation/TaskCardParser.kt`：任务卡字段解析
- `app/src/main/java/com/konnisan/dewuauto/automation/TaskDetail.kt`：详情页解析
- `app/src/main/java/com/konnisan/dewuauto/automation/EnrollmentFormHandler.kt`：报名表单与结果识别规则
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

前端预览可直接打开 `preview/index.html`。安装了 Playwright Node 模块时，可运行 `preview/check-preview.cjs` 验证风险确认与完成状态。

## 真机适配说明

得物使用动态 UI，不同账号和版本的可访问节点可能不同。当前已兼容 `已报名：16/20人` 和 `报名：9/40人` 两种人数格式，并同时支持独立报名文字节点和整张任务卡内容描述。若得物后续改版，优先更新 `DewuSelectors.kt` 和解析器测试样例。

达人号页面一旦存在“商单”快捷入口，程序只使用该入口；若入口不可点击会安全停止，不改走创作中心等其它路径。

普通第三方 Android 应用不能真正强制停止得物；本项目仅通过系统返回、主页和重新拉起完成可恢复导航。它不绕过验证码、不调用私有报名接口、不做多账号批量操作，也不规避平台风控。自动化依赖得物当前可访问节点，得物改版后应先重新验证选择器，避免直接在未知页面运行。
