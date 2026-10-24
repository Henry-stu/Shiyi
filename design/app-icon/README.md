# 拾忆图标

按用户提供的双叶设计截图重描，保留原叶片轮廓及配色，去除截图外部背景；不是对低分辨率截图直接放大。

- `recall-icon.svg`：可编辑的矢量设计源图。
- `recall-icon.png`：1024×1024 圆角透明背景预览图。
- 安卓实际使用 `app/src/main/res/drawable/ic_recall_*` 与 `mipmap-anydpi/ic_launcher.xml`；前景和纯色主题前景遵守自适应图标安全范围，圆角由系统遮罩确定。
- `python scripts/generate_app_icon.py` 重新生成 SVG／Android XML，仅使用 Python 标准库；追加 `--preview` 可导出 PNG，需要 Pillow 和 NumPy。

PNG 预览在矢量路径基础上采样绘制。最终桌面图标由 Android 原生矢量渲染。
