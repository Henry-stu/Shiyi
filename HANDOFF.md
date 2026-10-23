# 拾忆项目交接

## 当前状态

- 项目目录：`D:\codex-workspace\Recall`
- 当前版本：拾忆 1.2.1，`versionCode 5`，应用标识 `com.hh.recall`。
- 最新功能：分类页等宽等高文件夹卡片、篇数置右、绿色竖向双点直接重命名、平板横竖屏和手机竖屏适配。
- 正式版已同签名覆盖安装到小米平板 7。用户资料仍在：2 篇文章、2 个文件夹；覆盖前后完整数据库快照一致。
- 工作区不是 Git 仓库；不要假设有可用的 Git 历史。

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

1.2.1 最近结果：34 项单元测试通过；小米平板横屏 29 项设备测试通过；平板竖屏和 1080 × 2400 / 440 dpi 手机竖屏模拟各 7 项分类测试通过；Lint 0 错误、8 条已有警告。真实小米 14 Pro 未连接。截图和按设备区分的日志位于 `qa/`，汇总见 `VALIDATION.md`。

ADB 平板序列号：`b840bb56`。设备为小米平板 7，Android 16。手机竖屏测试是在平板上模拟尺寸，不是真机测试。测试脚本会通过 `adb install -r` 覆盖安装 Debug 版；不要卸载正式 App，也不要向个人数据库恢复备份或导入测试资料。

## 后续开发注意事项

- 先阅读本文件、`README.md`、`VALIDATION.md`，再检查当前源代码和设备状态；以代码为准，不依赖旧聊天记录。
- 继续发布可保留用户资料的版本时，递增 `app/build.gradle.kts` 中的 `versionCode` 和 `versionName`，并使用同一应用标识及原签名。
- `signing/recall-personal.jks` 与 `signing/signing.properties` 是私密签名材料；不要输出、复制进分发包或上传。签名遗失会导致无法覆盖更新。
- 分类时间已由 Room schema 2 和 ZIP v1 兼容处理；纯 UI 修改不需要迁移。确需改数据结构时，补 Room 迁移、备份兼容测试和设备数据保留验证。
- 双击红字取消整处挖空是既有规则，用户确认保持原样。
- 手机目标为竖屏；平板需兼容横屏和竖屏。

## 1.2.1 后续工程改进（进行中）

- 2026-10-23 已为当前源码建立 Git 基线提交 `c4dfe32`；没有恢复旧版本提交历史。私有签名、机器路径、QA 目录和 APK 分发文件均不纳入版本控制。
- 修正背诵页双击空白时单击回调先切换、双击回调再切换的问题；单击在手势确认后处理。6 项 Reader 设备测试通过，其中新增双击仅揭示一次的回归测试。
- ZIP 恢复新集合时，导入的原文件夹保留原名，避免每次恢复累积“恢复 时间 /”前缀；增加往返恢复单测。
- 拖动目标区域重叠时改为选择面积较小的包含目标；增加单测。
- 背诵答案显隐只更新颜色 Span、无障碍描述和绘制失效，不再因显隐重建文本并调用 `setText`；Reader 设备测试断言同一个文本 Layout 实例被复用。
- 本次 JVM 单测 36 项通过；Debug、AndroidTest、Release 构建和 Lint 通过。Lint 当前报告 0 错误、5 条警告，剩余为目标 API 与依赖更新建议；没有为清零而盲目升级。
- 设备上最终恢复安装 `dist/拾忆-v1.2.1.apk`，系统报告 versionCode 5。未执行主资料导入、删除或恢复操作。当前改动尚未提升版本号，也未生成正式 Release 或新分发包。
- 2026-10-23 已在 Xiaomi 23116PN5BC 手机竖屏真机运行完整 30 项设备回归，全部通过，最终代码日志为 `qa/v121-phone-real-tests.txt`。设备原先未安装 App；结束后卸载本轮安装的应用和测试包。测试脚本现支持 `RECALL_DEVICE_SERIAL` 并按设备序列号分别保存日志。
- 仍待分阶段评估：AppModel 可独立测试的状态逻辑、MaskRules 大文本边界扫描、Repository 全量 reload、统一 Debug 日志、Lint 警告清理、手机竖屏真机验收和签名材料异地备份状态确认。手机目前未连接；平板尺寸模拟不替代真机验证。

## 新窗口开场模板

```text
继续开发 D:\codex-workspace\Recall。先读取 HANDOFF.md、README.md、VALIDATION.md，并检查当前代码与工作区状态。不要依赖旧聊天记录；保护设备上的个人资料和项目签名。当前任务：[写明新需求]。
```
