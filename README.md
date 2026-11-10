# 文档阅读（Docs Reader）

一个轻量级 Android 文档阅读 App，**只读、纯本地解析、无网络依赖、无需任何账号权限**。

## 支持格式

| 格式 | 扩展名 | 渲染方式 |
|------|--------|----------|
| Markdown | .md .markdown | Markwon 原生渲染（表格/删除线/任务列表） |
| PDF | .pdf | 系统 PdfRenderer，横向翻页 + 双指缩放 |
| Word | .docx | 解析 OOXML → HTML（标题/加粗斜体/列表/表格合并单元格/超链接/图片） |
| Excel | .xlsx | 解析 OOXML → 表格 HTML（共享字符串/日期格式/合并单元格，多表滑动切换） |
| PPT | .pptx | 解析 OOXML → 逐页幻灯片（文字/颜色/字号/表格/图片，保持版面位置） |
| 纯文本 | .txt .json .xml .csv .log 等 | 自动识别 UTF-8 / GBK 编码 |

> 旧版二进制格式（.doc/.xls/.ppt）不支持，请在电脑上另存为对应的新版格式。

## 功能

- SAF 文件选择器打开任意位置（本地/云盘/Downloads）的文档
- **最近查看**列表：最多 10 条，按最近打开时间倒序，重复打开同一文件自动置顶
  - 应用内选择器打开的文件使用持久化 URI 授权，**重启后仍可一键再开**
  - 被其他应用调起打开的文件（仅临时授权）在应用存活期间也会显示，授权失效后自动从列表移除
  - 每条支持单独移除（垃圾桶图标）
- 支持被其他应用"分享/打开方式"调起（ACTION_VIEW / ACTION_SEND）
- 深色/浅色主题跟随系统（动态取色）

## 构建

```bash
# 需要 JDK 17+ 与 Android SDK（compileSdk 36）
JAVA_HOME=<jdk21路径> ./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 安装到手机

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 技术要点

- Kotlin 2.x + Jetpack Compose（Material 3），minSdk 26
- Office 文档不依赖 Apache POI / WPS 引擎：docx/xlsx/pptx 本质是 zip+XML，
  自研轻量解析（`app/src/main/java/com/wenbo/docsreader/ooxml/`），
  转 HTML 后用 WebView 渲染；图片从 zip 解压到缓存目录经 `file://` 引用
- PDF 用系统 `android.graphics.pdf.PdfRenderer`，位图走 LruCache，Mutex 串行渲染
- 单元测试：解析器为纯 JVM 代码，直接构造 OOXML zip 断言 HTML 输出；
  另用 python-docx / openpyxl / python-pptx 生成真实文件做端到端验证
  （`tools/gen_office_samples.py` 生成到 /tmp/office_samples 后，RealFilesTest 自动启用）
