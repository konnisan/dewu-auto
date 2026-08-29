# dewu-auto

Android 无障碍版自动报名 V1。项目目标是把已有 Lua/自动化 APK 的思路重新实现为可维护的 Kotlin Android 工程，不复制第三方 APK 的受保护源码。

## 当前 V1 流程

对应当前需求：

1. 读取 UI 参数并持久化。
2. 检测 AccessibilityService。
3. 读取屏幕分辨率。
4. 卡密验证 + 心跳：已提供接口与 HTTP 实现；Debug 在未配置服务端时使用本地开发验证。
5. 得物软重置。普通第三方 Android App 无权 `force-stop` 得物，因此使用 Back/Home 后重新拉起，不伪装成真正杀进程。
6. 通过 `com.shizhuang.duapp` 启动得物。
7. 检测首页。
8. 点击“我/我的”。
9. 检测个人中心。
10. 点击“商单/品牌合作”；兼容品牌合作卡片的“查看更多”。
11. 检测商单页面。
12. 首轮排序切到“最近发布”。
13. 左滑筛选栏 → 产品类目 → 选择 UI 类目。
14. 扫描当前页“报名/立即报名”任务。
15. 根据 UI 下滑次数继续扫描。
16. 达到阈值后切换到 UI 设置的排序方式。
17. 清零下滑计数继续扫描。
18. 报名成功后继续下滑找任务。
19. 一轮完成后返回首页。
20. 根据 UI 数量随机打开首页作品；0 跳过。
21. 图文按 UI 范围左右滑动，视频/未知作品随机停留。
22. Back 返回首页。
23. 再次进入“我 → 商单”。
24. 重新执行筛选和扫描。
25. 达到目标报名次数后停止。

遇到“验证码 / 安全验证 / 人机验证 / 滑块验证”等页面会停止自动化，不实现验证码绕过、直接接口报名、多账号批量或风控规避。

## 开发环境

- Android Studio Quail 3 (2026.1.3)
- AGP 8.12.2
- Gradle 8.13
- JDK 17
- compileSdk 36
- targetSdk 35
- minSdk 26

Quail 3 支持该 AGP 版本。项目以传统 Views + Kotlin 实现，尽量减少依赖。

## 卡密服务

`app/build.gradle.kts` 当前：

```kotlin
buildConfigField("String", "LICENSE_API_BASE_URL", "\"\"")
```

Debug 且地址为空时允许本地开发测试。正式版应填入 HTTPS 服务地址。默认约定：

- `POST /verify`: `{ "cardKey": "...", "deviceId": "..." }`
- 成功：`{ "ok": true, "sessionToken": "..." }`
- `POST /heartbeat`: `{ "sessionToken": "...", "deviceId": "..." }`

服务端协议确定后再按真实接口调整 `LicenseManager.kt`。

## 真机调试重点

V1 选择器集中在：

`app/src/main/java/com/konnisan/dewuauto/automation/DewuSelectors.kt`

得物属于动态 UI，当前文字选择器是按已知流程做的兼容层。第一次真机跑通前，建议在以下页面各导出一次 UI XML：

- 首页
- 我的/个人中心
- 商单入口
- 商单列表
- 排序菜单
- 产品类目菜单
- 报名详情/确认页

然后优先补充真实 `viewIdResourceName`，再把坐标兜底减少到最低。

## 编译

Android Studio Quail 3 直接打开仓库根目录，然后执行 **Build > Build APK(s)**。CI 使用 Gradle 8.13 执行：

```text
gradle :app:assembleDebug
```

Windows 如果已经把 Gradle 8.13 加入 PATH，也可以运行：

```bat
build-debug.bat
```

APK 输出：

`app/build/outputs/apk/debug/app-debug.apk`
