# 拾忆项目交接

## 当前状态

- 项目目录：`D:\codex-workspace\Recall`
- 当前版本：拾忆 1.2.1，`versionCode 5`，应用标识 `com.hh.recall`。
- 最新功能：分类页等宽等高文件夹卡片、篇数置右、绿色竖向双点直接重命名、平板横竖屏和手机竖屏适配。
- 正式版已同签名覆盖安装到小米平板 7。用户资料仍在：2 篇文章、2 个文件夹；覆盖前后完整数据库快照一致。
- 工作区已初始化 Git；当前工程改进以提交 `c4dfe32` 为源代码基线，后续提交见 `git log`。更早的 1.2.0→1.2.1 历史无法恢复。

## 重要文件

- `README.md`：功能、架构、构建、签名和交付说明。
- `VALIDATION.md`：各版本设备验收记录；1.2.1 记录在最前面。
- `app/src/main/java/com/hh/recall/ui/CategoryScreen.kt`：分类页及文件夹卡片。
- `app/src/main/java/com/hh/recall/ui/RecallApp.kt`：页面导航、重命名弹框、键盘适配。
- `app/src/main/java/com/hh/recall/AppModel.kt`：页面和资料状态、重命名行为。
- `app/src/androidTest/java/com/hh/recall/CategoryDeviceTest.kt`：分类页、排序、重命名、卡片尺寸测试。
- `dist/拾忆-v1.2.1.apk`：已签名安装包。
- `dist/Recall-source-v1.2.1.zip`：源码分发包，不含签名材料和本机路径。

## 构建与验证

Windows 使用 JDK 17：

```powershell
$env:JAVA_HOME = 'D:\JDK'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug
```

设备测试脚本：

```powershell
& 'C:\Users\hh\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -X utf8 scripts/run_device_tests.py
```

1.2.1 原始验收结果见 `VALIDATION.md`。2026-10-23 工程改进后，JVM 单测 36 项通过；Debug、AndroidTest、Release 均可构建；Lint 0 错误、5 条建议。小米平板 Reader 6 项通过，Xiaomi 23116PN5BC 手机竖屏真机 30 项通过。手机设备回归后已卸载临时 App 和测试包；平板正式 Release 未改动。

ADB 平板序列号：`b840bb56`（小米平板 7，Android 16）；手机序列号可通过 `adb devices -l` 查询，脚本用环境变量 `RECALL_DEVICE_SERIAL` 指定。测试脚本通过 `adb install -r` 安装 Debug 版；测试完应恢复原正式版或卸载临时测试包，不要向个人数据库恢复备份或导入测试资料。手机是竖屏目标，不需要横屏验收。

## 后续开发注意事项

- 先阅读本文件、`README.md`、`VALIDATION.md`，再检查当前源代码和设备状态；以代码为准，不依赖旧聊天记录。
- 继续发布可保留用户资料的版本时，递增 `app/build.gradle.kts` 中的 `versionCode` 和 `versionName`，并使用同一应用标识及原签名。
- `signing/recall-personal.jks` 与 `signing/signing.properties` 是私密签名材料；不要输出、复制进分发包或上传。签名遗失会导致无法覆盖更新。
- 分类时间已由 Room schema 2 和 ZIP v1 兼容处理；纯 UI 修改不需要迁移。确需改数据结构时，补 Room 迁移、备份兼容测试和设备数据保留验证。
- 双击红字取消整处挖空是既有规则，用户确认保持原样。
- 手机目标为竖屏；平板需兼容横屏和竖屏。

## 1.2.1 后续工程改进（已完成源码实现，未发布）

- 2026-10-23 已为当前源码建立 Git 基线提交 `c4dfe32`；没有恢复旧版本提交历史。私有签名、机器路径、QA 目录和 APK 分发文件均不纳入版本控制。
- 修正背诵页双击空白时单击回调先切换、双击回调再切换的问题；单击在手势确认后处理。6 项 Reader 设备测试通过，其中新增双击仅揭示一次的回归测试。
- ZIP 恢复新集合时，导入的原文件夹保留原名，避免每次恢复累积“恢复 时间 /”前缀；增加往返恢复单测。
- 拖动目标区域重叠时改为选择面积较小的包含目标；增加单测。
- 背诵答案显隐只更新颜色 Span、无障碍描述和绘制失效，不再因显隐重建文本并调用 `setText`；Reader 设备测试断言同一个文本 Layout 实例被复用。
- 本次 JVM 单测 36 项通过；Debug、AndroidTest、Release 构建和 Lint 通过。Lint 当前报告 0 错误、5 条警告，剩余为目标 API 与依赖更新建议；没有为清零而盲目升级。
- 设备上最终恢复安装 `dist/拾忆-v1.2.1.apk`，系统报告 versionCode 5。未执行主资料导入、删除或恢复操作。当前改动尚未提升版本号，也未生成正式 Release 或新分发包。
- 2026-10-23 已在 Xiaomi 23116PN5BC 手机竖屏真机运行完整 30 项设备回归，全部通过，最终代码日志为 `qa/v121-phone-real-tests.txt`。设备原先未安装 App；结束后卸载本轮安装的应用和测试包。测试脚本现支持 `RECALL_DEVICE_SERIAL` 并按设备序列号分别保存日志。
- 新增 `AppLog` 统一 Debug 日志：保存、草稿写入／恢复、备份导入、数据库迁移和异步操作失败均有事件；事件不写入文章正文或草稿内容。Release 不输出这些诊断日志。
- 仍待评估：`AppModel` 状态转换的独立 JVM 测试设计；`MaskRules` 局部 Unicode 字素边界扫描优化（固定字符窗口可能切断任意长的组合字素）；`Repository.reload()` 与阅读位置状态双来源的一致性重构；剩余 5 条 Lint 建议（目标 API、依赖更新和 KTX）；签名材料异地备份状态。当前手机竖屏自动回归已完成，手动输入法候选、字体缩放及系统手势探索尚未完成。不要为清空 Lint 建议而无测试升级 target SDK 或依赖。

### 本轮需求实现

- 标题保持独立于正文；主页文章卡片继续分别显示标题和正文摘要。保存空字符串或纯空白标题时显示「文章标题还未命名」，「去命名」会聚焦标题框并打开键盘，标题有效后才能保存。文章列表沿用同一篇文章状态，分类页仍只展示文件夹。
- 首页右上角三个点左侧增加「＋」导入入口，提供 Markdown 与 PDF 文件选择器。选中文件仅提示「解析导入将在后续版本开放」，不读取、不建文章、不改资料。
- 挖空标记模式支持从文字以外的区域（包括卡片内边距和行间空白）垂直拖动滚动；拖动期间滑块临时显示浏览，抬起或取消后恢复标记，且不会创建挖空。右上角保留完成／保存，底部只保留撤销和重做。
- 新增空标题、导入菜单和空白滚动设备回归。平板完整套件 33/33、手机竖屏完整套件 33/33；Reader 7/7；JVM 36 项、Debug/AndroidTest/Release 和 lintDebug 均通过。Lint 仍为 0 错误、5 条建议。
- 2026-10-23 测试完成后已将平板覆盖恢复为 `dist/拾忆-v1.2.1.apk`，系统报告 versionCode 5；手机临时 Debug 主包和测试包已卸载。没有修改正式资料、数据库格式或版本号。

## 新窗口开场模板

```text
继续开发 D:\codex-workspace\Recall。先读取 HANDOFF.md、README.md、VALIDATION.md，并检查当前代码与工作区状态。不要依赖旧聊天记录；保护设备上的个人资料和项目签名。当前任务：[写明新需求]。
```
