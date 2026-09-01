# 三字段屏蔽词筛选实施计划

## 范围

本次只修改屏蔽词的数据来源和命中规则：

- 商品名字
- 合作方式
- 达人要求

不修改价格、名额、排序、类目、报名表单或最终确认逻辑。真机验证只观察命中后跳过，不产生新的真实报名。

## 步骤 1：建立三字段数据模型

修改文件：

- `app/src/main/java/com/konnisan/dewuauto/automation/TaskDetail.kt`

实施内容：

1. 将 `TaskDetail` 扩展为明确字段：`productName`、`cooperationMethod`、`creatorRequirements`。
2. 保留 `rawText` 仅供页面诊断，不再用它执行屏蔽词命中。
3. 商品名字优先使用详情页标题，未读取到时使用已记录的商单列表标题。

成功条件：后续筛选代码无需读取整页 `rawText` 就能获得三个待检测字段。

## 步骤 2：按顺序提取详情页字段

修改文件：

- `app/src/main/java/com/konnisan/dewuauto/accessibility/NodeUtils.kt`
- `app/src/main/java/com/konnisan/dewuauto/automation/TaskDetail.kt`
- `app/src/main/java/com/konnisan/dewuauto/automation/DewuSelectors.kt`

实施内容：

1. 增加按无障碍节点顺序返回文本片段的方法，不使用整页字符串模糊匹配。
2. 以页面标签定位字段：
   - `合作方式` 后的内容，到下一个顶级标签为止。
   - `达人要求` 后的内容，到下一个顶级标签或页面操作区为止。
   - 详情页商品标题从页面标题/任务商品区提取，并保留列表标题回退。
3. 提取器只返回这三个字段，不把发布时间、奖励、按钮或其他页面文字并入字段。

成功条件：给定一组有序页面文本片段，提取结果稳定且只包含三个目标字段。

## 步骤 3：建立单一的屏蔽词匹配器

新增或修改文件：

- `app/src/main/java/com/konnisan/dewuauto/automation/TaskEligibilityEvaluator.kt`
- 必要时新增 `BlockWordMatcher.kt`

实施内容：

1. 定义匹配结果：是否命中、命中字段、命中词。
2. 按固定顺序检查：商品名字 → 合作方式 → 达人要求。
3. 每个字段对用户填写的所有屏蔽词执行包含匹配；任意一次命中立即返回。
4. 原因文本固定为：`商品名字命中屏蔽词：露脸`、`合作方式命中屏蔽词：露脸` 或 `达人要求命中屏蔽词：露脸`。
5. 删除以 `task.rawText` 和 `detail.rawText` 为输入的整卡片/整页屏蔽词检查。

成功条件：屏蔽词的唯一业务判定入口只接收三个字段。

## 步骤 4：连接商单列表与详情流程

修改文件：

- `app/src/main/java/com/konnisan/dewuauto/automation/AutomationController.kt`

实施内容：

1. 列表阶段只保存任务商品标题；可在标题已命中时直接跳过。
2. 标题未命中时打开详情页，获取合作方式和达人要求后执行完整三字段匹配。
3. 命中后增加跳过计数，在扫描结果中显示命中字段和屏蔽词，返回商单列表。
4. 三字段都未命中时，才允许点击“立即报名”。

成功条件：不存在未完成三字段检查就进入报名表单的路径。

## 步骤 5：使屏蔽词只由 APK 前台提供

修改文件：

- `app/src/main/java/com/konnisan/dewuauto/config/AutomationConfig.kt`
- `app/src/main/java/com/konnisan/dewuauto/config/AutomationPrefs.kt`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/java/com/konnisan/dewuauto/MainActivity.kt`

实施内容：

1. `AutomationConfig.excludedWords` 默认为空列表。
2. `AutomationPrefs` 没有已保存值时返回空值，不在内部补入任何屏蔽词。
3. 前台输入框不再预填固定词，只保留分隔方式提示。
4. 保留已有用户保存的屏蔽词，升级 APK 时不主动删除。

成功条件：新安装或无保存配置时屏蔽词为空；运行时只使用前台当前显示并保存的内容。

## 步骤 6：添加离线回归验证

修改文件：

- `verification/TaskFilterVerifier.kt`
- `verify-filter.bat`

必须覆盖：

1. 商品名字包含“露脸”：跳过，原因指向商品名字。
2. 合作方式包含“露脸”：跳过，原因指向合作方式。
3. 达人要求包含“露脸”：跳过，原因指向达人要求。
4. 三个字段均不包含：通过。
5. 多屏蔽词任意一个命中：跳过。
6. 空屏蔽词：通过。
7. 详情页三字段之外的文本含“露脸”：不产生屏蔽词命中。
8. 详情页字段边界提取正确，合作方式不吞入达人要求内容。

验证命令：

```powershell
.\verify-filter.bat
.\gradlew.bat --no-daemon '-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process' :app:assembleDebug
```

## 步骤 7：真机安全验证

1. 安装 debug APK 并恢复无障碍服务。
2. 保持单次实测模式，分别使用可在当前任务的商品名字、合作方式和达人要求中观察到的词进行三次命中测试。
3. 每次必须在点击“立即报名”前返回列表，日志必须显示对应字段和命中词。
4. 设置一个三字段都不包含的屏蔽词，确认任务可进入报名信息阶段，但在最终确认闸门前停止任务，不授权最终确认。
5. 检查报名成功计数没有增加，真机验证期间最终不可逆确认点击数为 0。

## 完成标准

- 屏蔽词只来自 APK 前台。
- 屏蔽词只检查商品名字、合作方式、达人要求。
- 任意字段命中任意屏蔽词时必须跳过。
- 三个字段都未命中时才允许继续报名。
- 扫描结果可见命中字段和命中词。
- 离线验证、Android 构建和真机非报名验证全部通过。
