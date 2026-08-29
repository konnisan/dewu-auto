# Playwright 界面预览

这是 Android 原生界面改版前的代码化视觉原型，不会连接得物或执行任何自动化动作。

在仓库根目录运行：

```powershell
npx playwright screenshot --viewport-size="432,862" --full-page "file:///D:/project/dewu-auto/preview/index.html" "preview/dewu-filter-preview.png"
```

生成的截图用于和视觉概念对照，生产界面仍由 `app/src/main/res/layout/activity_main.xml` 与 Kotlin 实现。

如本机已安装 Playwright Node 模块，可运行 `check-preview.cjs` 检查高级设置、预演完成状态以及不存在报名类按钮。
