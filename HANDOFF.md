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

## 新窗口开场模板

```text
继续开发 D:\codex-workspace\Recall。先读取 HANDOFF.md、README.md、VALIDATION.md，并检查当前代码与工作区状态。不要依赖旧聊天记录；保护设备上的个人资料和项目签名。当前任务：[写明新需求]。
```
