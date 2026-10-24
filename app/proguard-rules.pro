# ONNX JNI constructs Java metadata, tensor and exception classes reflectively.
-keep class ai.onnxruntime.** { *; }
# Optional JPX decoder is absent; scanned pages are rendered by Android PdfRenderer.
-dontwarn com.gemalto.jp2.JP2Decoder
