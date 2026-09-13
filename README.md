# TY课程表

从教务系统导出的课表 **PDF / Excel** 自动识别课程，按教学周以整表方式查看的安卓课表 App。

<p align="center">
  <img src="docs/icon.png" width="96" alt="图标">
</p>

- **版本**：7.12（versionCode 16）
- **最低系统**：Android 7.0（API 24），目标 API 34
- **作者**：sanwenTQ（GitHub: [@sanwenTQ](https://github.com/sanwenTQ)）
- **安装包**：[`release/TY-CourseTable-7.12.apk`](release/TY-CourseTable-7.12.apk)（直接下载安装，需允许"未知来源应用"）

---

## 关于本版本：7.12 稳定版

> **7.12 是本项目的稳定版，也是个人最终版。**
> 这是作者自用的课表 App，功能已经稳定，**除发现恶性 bug（崩溃、装不上、数据丢失这类）外，作者不再更新本仓库**。
> 想要新功能请自行 fork 修改，欢迎按 MIT 许可自由使用。
> 遇到恶性 bug 欢迎开 Issue 说明现象与复现步骤（附机型/系统版本）。

---

## 功能

### 导入课表
- **PDF**：解析对象流中的文字与坐标（需要文字层，扫描件/图片版不行）
- **Excel**：`.xls`（OLE2 + BIFF5/8）、`.xlsx`（zip + XML），
  以及**很多教务系统导出的"后缀是 .xls、其实是 HTML 表格"**的文件
- 按**文件头**自动判断格式，不看后缀
- 支持两种常见版式：星期横排/竖排的"表格型"课表；一行一门课的"列式"课表（自动识别表头列）
- 解析内容：星期、节次（`(1-2节)` / `第1-2节` / `1-2节`）、教学周
  （`1-16周`、`3-6周`、`1-7,11-16周`、单双周）、教师、场地、校区、考核方式、"其他课程"

### 查看课表
- 左边一列是节次 + 上课时间，横向是星期一~星期日；**跨节次的课合并成一格**
- 星期下面显示**真实日期**，今天那一列用主题色高亮（开学日期可设置）
- **点格子**：非线性展开成详情卡片（Material emphasized 曲线，从格子长出来、收回也回格子）；
  **长按**：显示原文件里的原始信息
- **删除课程**：详情卡片底部 → 选「删除这节课」（只删这个格子）或「删除这门课」（本学期同名课全删），可撤销
- **手动加课**：**双击或长按空白格子**（顶栏 ➕ 也行）→ 填课程名、星期、起止节次、周数、地点、老师（后两项选填）
- 顶部切换「第 N 周」或「全部课程」；右下角按钮一键回到本周

### 外观
- **主题色**：8 个预设 / 自定义 `#RRGGBB` / **从背景图自动取色**
- **格子配色三种模式**：主题色单色 / 背景图取色（糖果色按课程分配，同课同色）/ 主题色系多彩（按天变化）；
  表格线、课程格子包边、休息段底色都统一跟随主题色
- **背景图**：选图后手动裁切（按屏幕比例取景，**横竖屏各存一套**，不会拉伸变形），
  可调**不透明度**与**模糊**；没课的格子完全透明，图能整片透出来
- **圆角**全应用统一（格子、按钮、卡片、弹窗都跟随）
- **配色**：从背景图取 12 个糖果色按课程分配，**同一门课永远同色**，也可以关掉改用主题色系
- 界面动画可关闭；**全部弹窗/选择器都是自绘圆角卡片**（含日期选择、时间选择、确认框），没有系统原生控件

### 上课提醒
- 每节课**提前 N 分钟**（5/10/15/20/30，默认 10）弹提醒：**左边上课时间、右边课程名**，点一下直接进课表
- 由 `AlarmManager` 精确调度（有「闹钟和提醒」权限时用 `setExactAndAllowWhileIdle`，否则退化为窗口闹钟），
  开机/应用更新后自动重排
- **关于"灵动岛"**：Android 16（API 36）及以上走系统的**实时更新**公开 API
  （`Notification.ProgressStyle`）：通知会以状态栏胶囊 / 锁屏卡片呈现，带倒计时到上课，下课后自动收走。
  系统版本低于 16、或机型未适配时，会以**普通消息横幅**呈现，不需要额外设置。
  实现在 `LiveUpdate.java`；任何一环在设备上不可用时自动退回普通通知，提醒本身不受影响

### 作息
- 「课程」和「休息段」是**同级的两行**：都能单独改开始/结束时间；休息段不占节次、可以单独改名
  （午休/晚饭/大课间…），配色与课程不同
- **整张表按时间自动排序**：改了某一行的时间，它在列表里的先后和节次号会自己跟着变
- 每行右侧有 **➕**（在这行后面加一节课或一段休息）和 **✘**（只删这一行）
- **长按任意一行可上下拖动排序**（像桌面拖图标那样，其它行会平滑「挤」开让位），顺序会记住
- 底部三个入口：**添加课程 / 添加休息 / 恢复默认作息**，删空作息时底部入口依然在
- 点时间即改、**改完立刻生效**，只刷新受影响的行与顺序
- 默认作息：13 节（每节 45 分钟），上午 8:00 开始、下午 14:00 开始、晚上 19:20 开始；
  第 6 节后午休 13:30~14:00，第 11 节后休息 18:40~19:20
- 开学日期也用自绘卡片选（年/月/日胶囊 + 大预览），与其它弹窗同一套样式

---

## 构建

不需要 gradle，`build.sh` 直接用 `aapt2 + javac + d8 + apksigner` 出包。

**依赖**
- JDK 17+（`javac` / `keytool`）
- Android SDK：`platforms/android-34/android.jar`、`build-tools`（只用其中的 `lib/d8.jar`）
- `aapt2`、`apksigner`、`zipalign`（系统包或 build-tools 里都有）
- `curl`（首次会自动下载 PdfBox-Android）

**命令**
```bash
ANDROID_SDK_ROOT=/path/to/android-sdk ./build.sh
# 产物：release/TY-CourseTable-7.12.apk
```

**Windows**
```bat
build.cmd
:: 也可以带参数：build.cmd -VersionName 7.13 -VersionCode 17 -TargetSdk 34
```
`build.cmd` 是 `build.ps1`（`build.sh` 的 PowerShell 移植版）的入口包装，用
`-ExecutionPolicy Bypass` 绕开系统的"禁止运行脚本"限制。脚本会自己找 build-tools 下版本号最高的目录，
也可以指定：`build.cmd -SdkRoot D:\android-sdk -JavaHome C:\jdk-17`，
出包后会自动往 `D:\dsh\安装包\TY课程表-<版本>.apk` 拷一份（不需要可加 `-PublishDir ""` 关掉）。

移植时踩到的几个坑（Windows 版 aapt2 会把嵌套 assets 路径写成反斜杠、`resources.arsc` 必须保持非压缩态
否则 Android 11+ 会拒绝安装等）都写在脚本注释里，脚本末尾还有装包硬性条件的自检。

首次构建会在仓库根目录生成 `keystore.jks`（口令都是 `android`）。**正式发布请换成你自己的签名**，
并且不要把它提交到仓库（已在 `.gitignore` 中忽略）。

---

## 项目结构

```
app/                      Android 工程资源
  AndroidManifest.xml
  res/                    图标(几何生成的 PNG)、动画、样式
  assets/licenses/        Apache-2.0 / OFL / Unicode / MIT 许可全文 + NOTICE
src/com/dsh/coursetable/  全部 Java 源码（16 个文件，约 7000 行）
  CourseParser.java       PDF 解析：取字形坐标 → 聚行 → 按星期列分簇 → 解析单元格文字
  Xls.java                Excel/HTML 表格读取（OLE2+BIFF8 / zip+SAX / HTML）
  ExcelParser.java        表格网格 → 课程
  MainActivity.java       整表渲染、非线性动画、动态配色
  SettingsActivity.java   设置页（作息/主题/背景/圆角/关于）
  CropView/CropActivity   背景图手动裁切
  Sheet.java              自绘圆角弹窗（少数几处提示用；主界面/设置页以自绘卡片为主）
  Settings/Theme/Bg/Fonts 作息与日期 / MD3 动态配色 / 背景模糊 / 字体
  Reminder/ReminderReceiver 上课提醒：AlarmManager 调度 + 通知（左时间 / 右课程名），点一下进课表
  LiveUpdate.java         Android 16「实时更新」适配（反射调 Notification.ProgressStyle；不支持时自动退回普通通知）
fonts/                    子集化后的 HarmonyOS Sans SC（约 7500 字）
devtest/                  开发期脚本：图标与吉祥物生成、字体子集、Excel/PDF 解析回归测试
release/                  已构建的 APK
```

---

## 开源许可与致谢

本项目**自身代码**以 [MIT](LICENSE) 许可开源。

同时使用了以下第三方开源项目与资源（完整许可全文见 `app/assets/licenses/`）：

**随应用分发：**

| 组件 | 提供方 | 许可证 |
|---|---|---|
| PdfBox-Android 2.0.27.0 | Tom Roush | Apache-2.0 |
| Apache PDFBox / Apache FontBox 2.0.x | The Apache Software Foundation | Apache-2.0 |
| Apache Harmony（awt 兼容层） | The Apache Software Foundation | Apache-2.0 |
| Adobe CMap 资源 | Adobe Systems Incorporated | 随 PDFBox 分发 |
| Adobe Core 14 字体度量（AFM） | Adobe Systems Incorporated | 随 PDFBox 分发 |
| Liberation Sans Regular（PDFBox 兜底字体） | Red Hat / DigiTecs | SIL OFL 1.1 |
| Unicode 数据文件 `Scripts.txt` | Unicode, Inc. | Unicode License |
| HarmonyOS Sans SC 1.0（已子集化） | Huawei Device Co., Ltd & Hanyi Fonts（汉仪） | 见下方说明 |

> **界面字体单独说明**：内嵌的 HarmonyOS Sans SC Version 1.0 版权归 Huawei Device Co., Ltd
> 与汉仪所有，由华为以「免费商用」方式提供，**不适用本项目的 MIT 许可**；
> 再分发或商用前请以官方最新条款为准（官方页面的许可全文未随安装包分发）。

**仅构建期使用，不随应用分发：** Android SDK Build-Tools（Apache-2.0）、d8/R8（BSD-3-Clause）、
OpenJDK 17（GPLv2+CE）、fonttools 4.65（MIT）、xlwt 1.3（BSD，用于生成解析回归测试的 `.xls`）。

完整的第三方组件清单见 [`docs/开源许可与致谢.txt`](docs/开源许可与致谢.txt)（与安装包内
`assets/licenses/NOTICE.txt` 内容一致）。

### 关于应用图标

`docs/mascot-source.png` 是应用内吉祥物/图标的原图，**图片来源无法确认**。
如果你要用于商业项目，请替换掉：

- `app/res/drawable-xxhdpi/ic_mascot.png`（应用内 256×256）
- `app/res/drawable/ic_launcher.png`（启动图标 192×192）

也欢迎用 `devtest/mkicons2.py` / `devtest/mkmascot.py` 自己生成（纯 Python，几何绘制 + 4×4 超采样抗锯齿）。

---

## 已知限制

- 扫描件 / 图片型 PDF 没有文字层，**无法识别**
- 加密或有数字签名的 PDF 会解析失败（未打包 BouncyCastle）
- Excel 里含 JPEG2000 图片的 PDF 无法渲染（未打包 JP2 解码器，不影响文字解析）
- 只有 Android 客户端，没有服务端；**所有数据只存在本机，不联网**
