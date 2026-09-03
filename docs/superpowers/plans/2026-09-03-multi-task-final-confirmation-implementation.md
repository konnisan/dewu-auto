# 多任务演练与真实最终确认实施计划

## 范围

本次将单次人工确认闸门替换为本轮运行级别的“最终确认报名”授权，支持连续演练多个任务和连续真实报名。默认仍为演练模式；本轮实现与验证不自行执行真实最终确认。

## 步骤 1：替换运行配置与偏好

修改文件：

- `app/src/main/java/com/konnisan/dewuauto/config/AutomationConfig.kt`
- `app/src/main/java/com/konnisan/dewuauto/config/AutomationPrefs.kt`

实施内容：

1. 用 `finalConfirmationEnabled` 替代 `singleEnrollmentTestMode`。
2. 目标处理任务数始终限制为 1 至 20，不再因单次模式固定为 1。
3. 最终确认授权不写入偏好，加载配置时始终默认为关闭。
4. 保留其他已有配置和用户数据，不清除旧偏好文件。

成功条件：停止、重启或重新创建运行后，真实报名授权必定恢复为关闭。

## 步骤 2：建立多任务确认保护与运行策略

新增或修改文件：

- 新增 `app/src/main/java/com/konnisan/dewuauto/automation/FinalConfirmationGuard.kt`
- 新增 `app/src/main/java/com/konnisan/dewuauto/automation/EnrollmentRunPolicy.kt`
- 删除 `app/src/main/java/com/konnisan/dewuauto/automation/SingleEnrollmentGate.kt`

实施内容：

1. 确认保护以运行编号和任务签名为键。
2. 同一任务一轮最多获取一次最终确认锁；不同任务可分别获取。
3. 运行策略根据授权状态决定最终弹窗动作：演练取消或真实确认。
4. 运行策略分别计算演练进度和真实成功进度。

成功条件：确认锁不再限制整轮只能报名一次，也不能对同一任务重复点击。

## 步骤 3：扩展状态机与运行数据

修改文件：

- `app/src/main/java/com/konnisan/dewuauto/automation/AutomationState.kt`
- `app/src/main/java/com/konnisan/dewuauto/automation/AutomationRuntime.kt`
- `app/src/main/java/com/konnisan/dewuauto/automation/AutomationController.kt`

实施内容：

1. 删除等待任务级人工授权状态，增加演练取消验证状态。
2. 运行数据增加模式、目标数、演练通过数和最终确认点击数。
3. 到达最终弹窗后：演练模式停留约 2 秒并点击“取消”；真实模式获取任务锁并点击一次“确认”。
4. 演练取消后验证弹窗消失，再返回商单列表；真实报名成功后按现有路径返回列表。
5. 两种模式都在回到商单列表后按对应完成数判断是否结束，否则继续下一个任务。
6. 已演练或已确认的任务加入本轮已处理集合，重复出现时跳过。

成功条件：演练和真实模式都能连续处理任务，且计数和最终确认动作互不混用。

## 步骤 4：简化首页安全控件

修改文件：

- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/java/com/konnisan/dewuauto/MainActivity.kt`

实施内容：

1. 删除两个旧复选框和动态操作员闸门面板。
2. 增加常驻“最终确认报名”按钮，默认显示未开启。
3. 运行前允许切换，运行期间锁定；停止或终态后恢复未开启。
4. 点击开始时把按钮状态写入本轮配置快照。
5. “目标报名成功次数”改为“目标处理任务数”。
6. 状态区分别显示演练通过数或真实报名成功数。

成功条件：首页不再出现相互冲突的单次模式与最终确认控件。

## 步骤 5：更新离线回归

修改文件：

- `verification/TaskFilterVerifier.kt`
- `verify-filter.bat`

必须覆盖：

1. 最终确认默认关闭。
2. 演练模式选择取消动作，真实模式选择确认动作。
3. 同一任务只能获取一次确认锁。
4. 同一运行的不同任务可以分别确认。
5. 错误运行编号不能获取确认锁。
6. 演练数和成功数分别决定完成状态。
7. 目标数允许 2 至 20，不再被固定为 1。

## 步骤 6：构建与演练真机验证

1. 执行离线回归和 Android debug 构建。
2. 覆盖安装 APK，恢复无障碍服务。
3. 保持“最终确认报名：未开启”，目标处理任务数设为 2。
4. 验证两个不同任务依次到达最终弹窗、自动取消、返回商单列表并继续。
5. 验证演练通过数为 2，真实报名成功数和最终确认点击数均为 0。
6. 验证停止、重新打开应用后按钮恢复未开启。

## 步骤 7：真实模式静态验证与打包

1. 通过离线策略测试和代码路径检查验证真实模式只在本轮授权后可达。
2. 不在没有用户再次明确指定真实报名次数的情况下点击真机最终确认。
3. 升级测试版本号并生成唯一文件名 APK。
4. 记录 Git 提交、APK SHA-256、安装版本和演练测试结果。

## 完成标准

- 首页安全控制只剩一个默认关闭的最终确认按钮。
- 演练模式连续完成两个任务且真实确认点击数为 0。
- 真实模式支持不同任务各确认一次，同一任务禁止重复。
- 两种模式都按目标处理任务数停止。
- 授权不持久化，错误与重启后自动关闭。
- 离线回归、Android 构建和真机演练全部通过。
