# 导入模块的第三方组件

本次整合保留独立实验验证过的版本。运行时离线，不包含桌面 Python、MarkItDown 或 PDF Skill 的运行时与代码。

| 组件 | 版本 | 上游 | 声明许可 |
| --- | --- | --- | --- |
| ONNX Runtime Android | 1.23.2 | https://github.com/microsoft/onnxruntime/tree/v1.23.2 | MIT；附上游 ThirdPartyNotices |
| OpenCV Android | 4.12.0 | https://github.com/opencv/opencv/tree/4.12.0 | Apache-2.0 |
| PDFBox Android | 2.0.27.0 | https://github.com/TomRoush/PdfBox-Android/tree/2.0.27.0 | Apache-2.0；保留 LICENSE.txt、NOTICE.txt 中附加声明 |
| Bouncy Castle（PDFBox 传递依赖） | 1.72 | https://github.com/bcgit/bc-java | MIT 风格许可 |
| RapidOCR（检测后处理与 CTC 规则参考） | 3.9.2 | https://github.com/RapidAI/RapidOCR/tree/v3.9.2 | Apache-2.0；Java 适配文件已标注修改 |

两份模型的精确来源为发布方 RapidAI/RapidOCR 的 ModelScope `v3.9.2`：

- https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.2/onnx/PP-OCRv6/det/PP-OCRv6_det_small.onnx
- https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.2/onnx/PP-OCRv6/rec/PP-OCRv6_rec_small.onnx

2026-10-24 核查该仓库发布声明为 Apache License 2.0；原始模型卡随 APK 保存在 `assets/licenses/RapidOCR-model-card-v3.9.2.txt`。同时核查 PaddlePaddle 的 [small det 模型卡](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det) 和 [small rec 模型卡](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec)，均声明 Apache-2.0。这是发布方声明记录，不将框架许可证单独当作权重许可证明。

| 模型 | 大小 | SHA-256 |
| --- | --- | --- |
| PP-OCRv6_det_small.onnx | 9,929,594 字节 | `090f04abcd9d9a7498bc4ebf677e4cb9bdce1fe4197ddb7e529f1ef44e1ff94f` |
| PP-OCRv6_rec_small.onnx | 21,234,383 字节 | `6f327246b50388f3c176ae304bd95767ea6dc0c9ae92153ef8cbe210b3c14884` |

模型未重新训练或修改参数。Java 适配包含四向尝试、倾斜处理、长图分段和逐页路由，不进行字词或标点替换。相关许可证和上游声明随 APK 放在 `assets/licenses/`，也包含在源码包中。

许可证原文从上述固定版本仓库获取；直连不可用时通过 jsDelivr 对应仓库版本文件获取。模型卡通过 ModelScope 官方 API 获取。本轮交付为用户个人使用的同签名更新。
