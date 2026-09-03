# 类目与奖励筛选可靠性实施计划

## 范围

本次只修复产品类目选择验真、任务卡片现金奖励解析和筛选结果可观测性。奖励区间使用包含端点的闭区间；真机测试不执行不可撤销的最终报名确认。

## 步骤 1：收紧现金奖励解析

修改文件：

- `app/src/main/java/com/konnisan/dewuauto/automation/TaskCardParser.kt`
- `app/src/main/java/com/konnisan/dewuauto/automation/TaskCard.kt`

实施内容：

1. 奖励正则必须以同一卡片中的“现金奖励”为上下文，不能读取任意人民币数字。
2. 无法唯一解析现金奖励时返回空金额，由筛选器安全跳过。
3. 任务签名继续包含标题和解析字段，确保同任务去重稳定。

成功条件：包含商品价格、报名人数或相邻任务金额的文本不会覆盖本卡片现金奖励。

## 步骤 2：让奖励判定原因包含证据

修改文件：

- `app/src/main/java/com/konnisan/dewuauto/automation/TaskEligibilityEvaluator.kt`
- `app/src/main/java/com/konnisan/dewuauto/automation/AutomationController.kt`
- `app/src/main/java/com/konnisan/dewuauto/MainActivity.kt`

实施内容：

1. 使用 `minPrice <= rewardAmount <= maxPrice` 的闭区间判断。
2. 通过和跳过原因都包含实际现金奖励及本轮固定范围。
3. 扫描结果显示“现金奖励”而非含义不明的“奖励”。
4. 进入候选任务前记录标题、金额和范围，便于真机日志核对。

成功条件：测试者无需查看代码即可判断为什么 `¥20` 被跳过、`¥30` 被接受。

## 步骤 3：限制任务卡片边界

修改文件：

- `app/src/main/java/com/konnisan/dewuauto/automation/AutomationController.kt`
- 必要时新增纯 Kotlin 卡片选择规则文件

实施内容：

1. 只从精确文字为“报名”的可操作按钮建立候选任务卡片。
2. 向上查找包含报名人数和现金奖励的最近祖先，并拒绝同时包含多个“报名”按钮的列表级祖先。
3. 每个报名按钮只生成一张卡片；使用按钮与卡片的直接关联选择候选，不再用整段文本反向寻找按钮。
4. 记录卡片边界和解析结果，发现边界不唯一时跳过。

成功条件：同屏 `¥20` 与 `¥30` 卡片不会串读，候选按钮始终属于通过筛选的卡片。

## 步骤 4：精确选择并验证产品类目

修改文件：

- `app/src/main/java/com/konnisan/dewuauto/automation/AutomationController.kt`
- 新增 `app/src/main/java/com/konnisan/dewuauto/automation/CategorySelectionRules.kt`

实施内容：

1. 筛选面板内只接受与目标类目完整文字相等、位于面板内容区域的节点，排除顶部筛选栏同名节点。
2. 目标类目不可见时，在类目区域执行有限次数滑动并重新查找。
3. 点击“确定”后，必须在顶部筛选栏检测到与配置精确相等的类目才进入扫描。
4. 第一次验真失败时重开面板再试；达到重试上限后安全停止并显示配置值和实际顶部值。

成功条件：任务列表出现不能再被误判为类目生效；“鞋”等后部类目在需要滑动时仍能被找到。

## 步骤 5：扩展离线回归测试

修改文件：

- `verification/TaskFilterVerifier.kt`
- `verify-filter.bat`（仅在新增纯 Kotlin 文件时加入编译列表）

必须覆盖：

1. 全部十个产品类目均可作为精确目标。
2. 类目目标初始可见、需要滑动、同名顶部节点被排除、顶部验真不匹配。
3. `30-99999` 对 `20`、`29.99`、`30`、`30.00`、`99999`、`99999.01` 和缺失金额的判断。
4. 相邻卡片分别为 `¥20` 与 `¥30` 时只接受 `¥30`。
5. 卡片包含“报名 20/100 人”和“现金奖励 ¥30”时按 `¥30` 判断。
6. 卡片存在其他人民币金额时仍只读取“现金奖励”。

验证命令：

```powershell
.\verify-filter.bat
.\gradlew.bat --no-daemon '-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process' :app:assembleDebug
```

## 步骤 6：真机矩阵验证

1. 安装新 debug APK并恢复无障碍服务。
2. 先使用“鞋、30-99999”复验：顶部必须显示鞋，`¥20`/`¥25` 显示跳过，`¥30` 可成为候选。
3. 依次测试服装、美妆个护、3C数码、配件、箱包、手表、鞋、家居、食品饮料、其他。
4. 每轮保存顶部类目、扫描结果和日志；没有符合金额的任务也可通过，只要每张卡片原因明确。
5. 测试窄边界范围和排除首屏全部任务的范围。
6. 一旦进入报名链路，只允许到达操作员最终确认闸门；取消并停止，不授权最终确认。

## 步骤 7：打包测试版

1. 生成新 debug APK。
2. 使用不带自动序号歧义的固定文件名复制到 `dist/`。
3. 记录 Git 提交、APK SHA-256、真机版本和测试矩阵结果。

## 完成标准

- `30-99999` 永远不进入 `¥20` 或 `¥25` 任务，端点金额可以通过。
- 候选任务金额来自自身卡片，不受相邻任务影响。
- 顶部实际类目必须与前台配置一致，否则停止。
- 十个产品类目和奖励边界离线测试通过。
- 真机矩阵有可核对证据，且最终不可逆确认点击次数为 0。
