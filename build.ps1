<#
============================================================
 TY课程表 · Windows 构建脚本（build.sh 的 PowerShell 移植版，不依赖 gradle）

 依赖：
   - JDK 17+（javac / java / keytool）
   - Android SDK：platforms/android-34/android.jar
                  build-tools/<ver>/（aapt2.exe / apksigner.bat / zipalign.exe / lib/d8.jar）
   - libs/pdfbox-android-*.aar（缺失时才需要联网下载）

 用法：
   build.cmd                     （推荐：双击，或在 CMD / 终端里跑）
   powershell -ExecutionPolicy Bypass -File build.ps1
   powershell -ExecutionPolicy Bypass -File build.ps1 -SdkRoot D:\android-sdk -VersionName 7.13

 产物：release/TY-CourseTable-7.12.apk

 移植时踩过的两个坑（别改回去）：
   1. Windows 版 aapt2 会把「嵌套的 assets 路径」写成反斜杠（assets/fonts\x.ttf），
      而 Android 运行时是按正斜杠查找的 → 字体和 PDFBox 资源会全部加载失败。
      本脚本在 aapt2 link 之后把 APK 条目名里的 \ 统一规范成 /，并做断言自检。
   2. Copy-Item 把目录复制到「已存在的目标目录」时会多套一层（assets/assets），
      与 bash 的 `cp -r src/assets dst/assets` 语义不同 → 必须用 assets\* 复制内容。
============================================================
#>
[CmdletBinding()]
param(
  [string]$SdkRoot  = $(if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { 'D:\dsh\android-sdk' }),
  [string]$JavaHome = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'D:\dsh\toolchain\jdk\jdk-17.0.20.1+1' }),
  [string]$BuildTools = '',
  [int]$VersionCode = 16,
  [string]$VersionName = '7.12',
  [int]$MinSdk = 24,
  [int]$TargetSdk = 34,
  [string]$PublishDir = 'D:\dsh\安装包',
  [string]$ApkName = 'TY-CourseTable-7.12.apk'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'

function Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Die($msg)  { Write-Host "失败: $msg" -ForegroundColor Red; exit 1 }

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

# 把 APK 条目名里的反斜杠原地改成正斜杠（Windows aapt2 会把嵌套 assets 路径写成 `assets/fonts\x.ttf`）。
#
# ⚠️ 必须「原地改字节」，绝不能重建 zip：
#    .NET 的 CompressionLevel.NoCompression 名字骗人 —— 它仍然写 deflate(method=8)，只是不压数据。
#    一旦用 ZipArchive 重建，aapt2 原本「存储(method=0)」的 resources.arsc 等 23 个条目会被改成压缩态，
#    而 targetSdk >= 30 的应用要求 resources.arsc 必须不压缩，安装时会被判为「与系统不兼容」
#    （实测 vivo 装包时报"安装应用与当前系统有兼容性问题"）。
#    这里只把名字字段里的 0x5C('\') 改成 0x2F('/')：两个字符都是单字节，偏移/长度/CRC 全不变，
#    zip 的其余部分与 aapt2 的输出逐字节一致。
function Repair-ApkEntryNames {
  param([string]$Apk)
  $b = [System.IO.File]::ReadAllBytes($Apk)
  $eocd = -1
  for ($i = $b.Length - 22; $i -ge 0; $i--) {
    if ($b[$i] -eq 0x50 -and $b[$i+1] -eq 0x4b -and $b[$i+2] -eq 0x05 -and $b[$i+3] -eq 0x06) { $eocd = $i; break }
  }
  if ($eocd -lt 0) { Die 'APK 里没找到 zip 的 EOCD 记录' }
  $count = [BitConverter]::ToUInt16($b, $eocd + 10)
  $cdOffset = [BitConverter]::ToUInt32($b, $eocd + 16)
  $patched = 0
  $p = $cdOffset
  for ($k = 0; $k -lt $count; $k++) {
    if (-not ($b[$p] -eq 0x50 -and $b[$p+1] -eq 0x4b -and $b[$p+2] -eq 0x01 -and $b[$p+3] -eq 0x02)) { break }
    $nlen = [BitConverter]::ToUInt16($b, $p + 28)
    $elen = [BitConverter]::ToUInt16($b, $p + 30)
    $clen = [BitConverter]::ToUInt16($b, $p + 32)
    $lho  = [BitConverter]::ToUInt32($b, $p + 42)
    for ($j = $p + 46; $j -lt ($p + 46 + $nlen); $j++) {              # 中央目录里的名字
      if ($b[$j] -eq 0x5C) { $b[$j] = 0x2F; $patched++ }
    }
    $lnlen = [BitConverter]::ToUInt16($b, $lho + 26)
    for ($j = $lho + 30; $j -lt ($lho + 30 + $lnlen); $j++) {         # 本地文件头里的名字
      if ($b[$j] -eq 0x5C) { $b[$j] = 0x2F; $patched++ }
    }
    $p += 46 + $nlen + $elen + $clen
  }
  [System.IO.File]::WriteAllBytes($Apk, $b)
  return $patched
}

# 解析 APK 的 zip 结构，用于自检（条目名 / 压缩方式 / 是否 4 字节对齐）
function Get-ApkZipInfo {
  param([string]$Apk)
  $b = [System.IO.File]::ReadAllBytes($Apk)
  $eocd = -1
  for ($i = $b.Length - 22; $i -ge 0; $i--) {
    if ($b[$i] -eq 0x50 -and $b[$i+1] -eq 0x4b -and $b[$i+2] -eq 0x05 -and $b[$i+3] -eq 0x06) { $eocd = $i; break }
  }
  if ($eocd -lt 0) { Die "APK 里没找到 zip 的 EOCD 记录: $Apk" }
  $count = [BitConverter]::ToUInt16($b, $eocd + 10)
  $cdOffset = [BitConverter]::ToUInt32($b, $eocd + 16)
  $list = @()
  $p = $cdOffset
  for ($k = 0; $k -lt $count; $k++) {
    if (-not ($b[$p] -eq 0x50 -and $b[$p+1] -eq 0x4b -and $b[$p+2] -eq 0x01 -and $b[$p+3] -eq 0x02)) { break }
    $method = [BitConverter]::ToUInt16($b, $p + 10)
    $nlen = [BitConverter]::ToUInt16($b, $p + 28)
    $elen = [BitConverter]::ToUInt16($b, $p + 30)
    $clen = [BitConverter]::ToUInt16($b, $p + 32)
    $lho  = [BitConverter]::ToUInt32($b, $p + 42)
    $name = [System.Text.Encoding]::UTF8.GetString($b, $p + 46, $nlen)
    $list += [pscustomobject]@{ Name = $name; Method = $method; Offset = $lho }
    $p += 46 + $nlen + $elen + $clen
  }
  return $list
}

# 装包前的硬性自检：这几条一旦不满足，Android 会直接拒绝安装
function Assert-ApkInstallable {
  param([string]$Apk)
  $entries = Get-ApkZipInfo -Apk $Apk

  $arsc = $entries | Where-Object { $_.Name -eq 'resources.arsc' }
  if (-not $arsc) { Die 'APK 里没有 resources.arsc' }
  if ($arsc.Method -ne 0) {
    Die ("resources.arsc 被压缩了（method=" + $arsc.Method + "）。targetSdk>=30 必须存储态，" +
         "否则 Android 11+ 会判为与系统不兼容、装不上")
  }
  if (($arsc.Offset % 4) -ne 0) {
    Write-Host ("    （提示：resources.arsc 本地头偏移 " + $arsc.Offset + "，对齐由下面的 zipalign 判定）")
  }

  $bad = @($entries | Where-Object { $_.Name.Contains('\') })
  if ($bad.Count -gt 0) { Die ("有 " + $bad.Count + " 个条目名仍含反斜杠，例如 " + $bad[0].Name) }

  $dup = @($entries | Group-Object Name | Where-Object { $_.Count -gt 1 })
  if ($dup.Count -gt 0) { Die ("条目名重复：" + $dup[0].Name) }

  # 对齐检查不自己实现（要算本地头里的变长 extra），直接交给 zipalign 判定
  $zlog = Join-Path $Build 'zipalign.log'
  & $Zipalign -c -v 4 $Apk > $zlog 2>&1
  if ($LASTEXITCODE -ne 0) {
    Get-Content $zlog | Select-Object -Last 5 | Write-Host
    Die 'zipalign -c 4 校验不通过（条目数据未 4 字节对齐）'
  }

  $stored = @($entries | Where-Object { $_.Method -eq 0 }).Count
  Write-Host ("    条目 " + $entries.Count + " 个（存储态 " + $stored + " 个）；resources.arsc 存储态 ✅；zipalign 4 字节对齐 ✅")
}

# ---------- 0. 定位工具 ----------
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $BuildTools) {
  $bt = Get-ChildItem (Join-Path $SdkRoot 'build-tools') -Directory -ErrorAction SilentlyContinue |
        Sort-Object { [version]($_.Name -replace '[^0-9.]','') } -Descending
  if (-not $bt) { Die "Android build-tools 目录（$SdkRoot\build-tools）" }
  $BuildTools = $bt[0].FullName
}

$Platform  = Join-Path $SdkRoot 'platforms\android-34\android.jar'
$D8Jar     = Join-Path $BuildTools 'lib\d8.jar'
$Aapt2     = Join-Path $BuildTools 'aapt2.exe'
$Apksigner = Join-Path $BuildTools 'apksigner.bat'
$Zipalign  = Join-Path $BuildTools 'zipalign.exe'
$Javac     = Join-Path $JavaHome 'bin\javac.exe'
$Java      = Join-Path $JavaHome 'bin\java.exe'

foreach ($f in @($Platform, $D8Jar, $Aapt2, $Apksigner, $Zipalign, $Javac, $Java)) {
  if (-not (Test-Path -LiteralPath $f)) { Die "缺少依赖: $f" }
}
$env:JAVA_HOME = $JavaHome
$env:PATH = (Join-Path $JavaHome 'bin') + ';' + $env:PATH

$Build    = Join-Path $Root 'build'
$Out      = Join-Path $Root 'release'
$Libs     = Join-Path $Root 'libs'
$AarDir   = Join-Path $Libs 'aar'
$Aar      = Get-ChildItem (Join-Path $Libs 'pdfbox-android-*.aar') -ErrorAction SilentlyContinue | Select-Object -First 1
$Font     = Join-Path $Root 'fonts\HarmonyOS_Sans_SC_Subset.ttf'
$Keystore = Join-Path $Root 'keystore.jks'

if (-not $Aar) {
  Step '未找到 PdfBox-Android，尝试下载 …'
  New-Item -ItemType Directory -Force -Path $Libs | Out-Null
  $aarpath = Join-Path $Libs 'pdfbox-android-2.0.27.0.aar'
  Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/com/tom-roush/pdfbox-android/2.0.27.0/pdfbox-android-2.0.27.0.aar' -OutFile $aarpath
  $Aar = Get-Item $aarpath
}
if (-not (Test-Path -LiteralPath $Font)) { Die "缺少字体: $Font" }

# ---------- 1. 解 AAR（取 classes.jar + assets） ----------
if (-not (Test-Path -LiteralPath (Join-Path $AarDir 'classes.jar'))) {
  Step "解压 $($Aar.Name)"
  if (Test-Path -LiteralPath $AarDir) { Remove-Item -Recurse -Force $AarDir }
  New-Item -ItemType Directory -Force -Path $AarDir | Out-Null
  [System.IO.Compression.ZipFile]::ExtractToDirectory($Aar.FullName, $AarDir)
}
$ClassesJar = Join-Path $AarDir 'classes.jar'

# ---------- 2. 清理 ----------
Step '清理 build 目录'
if (Test-Path -LiteralPath $Build) { Remove-Item -Recurse -Force $Build }
foreach ($d in @('res','gen','classes','dex','src','assets')) {
  New-Item -ItemType Directory -Force -Path (Join-Path $Build $d) | Out-Null
}
New-Item -ItemType Directory -Force -Path $Out | Out-Null

# ---------- 3. 生成 Android 版源码（桌面版 PDFBox 包名 → pdfbox-android） ----------
Step '改写 import（org.apache.pdfbox → com.tom_roush.pdfbox）'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$srcOut = Join-Path $Build 'src'
$srcFiles = @()
foreach ($f in Get-ChildItem (Join-Path $Root 'src\com\dsh\coursetable\*.java')) {
  $text = [System.IO.File]::ReadAllText($f.FullName)
  $text = [regex]::Replace($text, '(?m)^import org\.apache\.pdfbox\.', 'import com.tom_roush.pdfbox.')
  $dest = Join-Path $srcOut $f.Name
  [System.IO.File]::WriteAllText($dest, $text, $utf8NoBom)
  $srcFiles += $dest
}

# ---------- 4. assets = pdfbox 资源 + 应用自带 + 字体 ----------
Step '组装 assets'
$assetsDir = Join-Path $Build 'assets'
Copy-Item -Recurse -Force (Join-Path $AarDir 'assets\*') $assetsDir
if (Test-Path -LiteralPath (Join-Path $Root 'app\assets')) {
  Copy-Item -Recurse -Force (Join-Path $Root 'app\assets\*') $assetsDir
}
$fontDir = Join-Path $assetsDir 'fonts'
New-Item -ItemType Directory -Force -Path $fontDir | Out-Null
Copy-Item -Force $Font (Join-Path $fontDir 'HarmonyOS_Sans_SC_Regular.ttf')
Copy-Item -Force $Font (Join-Path $fontDir 'HarmonyOS_Sans_SC_Bold.ttf')

# ---------- 5. 资源 + manifest ----------
Step 'aapt2 compile / link'
$resZip  = Join-Path $Build 'res.zip'
$baseApk = Join-Path $Build 'base.apk'
& $Aapt2 compile --dir (Join-Path $Root 'app\res') -o $resZip
if ($LASTEXITCODE -ne 0) { Die 'aapt2 compile 失败' }

& $Aapt2 link `
  -o $baseApk `
  -I $Platform `
  --manifest (Join-Path $Root 'app\AndroidManifest.xml') `
  -A $assetsDir `
  --java (Join-Path $Build 'gen') `
  --min-sdk-version $MinSdk --target-sdk-version $TargetSdk `
  --version-code $VersionCode --version-name $VersionName `
  $resZip
if ($LASTEXITCODE -ne 0) { Die 'aapt2 link 失败' }

# ---------- 5b. 原地修正条目名的反斜杠（Windows aapt2 的坑；不重建 zip） ----------
Step '规范化 APK 内的 assets 路径分隔符（原地改字节）'
$patched = Repair-ApkEntryNames -Apk $baseApk
Write-Host "    替换字节数：$patched"

# ---------- 6. 编译 java ----------
Step 'javac'
$allJava = @($srcFiles) + @(Get-ChildItem (Join-Path $Build 'gen') -Recurse -Filter '*.java' | ForEach-Object FullName)
$javacArgs = @('-encoding', 'UTF-8', '-source', '8', '-target', '8', '-nowarn',
               '-bootclasspath', $Platform, '-classpath', "$Platform;$ClassesJar",
               '-d', (Join-Path $Build 'classes'))
$javacArgs += $allJava
$javacLog = Join-Path $Build 'javac.log'
# PowerShell 5.1 会把原生程序的 stderr 包成 ErrorRecord，配合 $ErrorActionPreference='Stop' 会中断脚本；
# javac 的 deprecation note 只走 stderr，这里临时降级为 Continue 并改看退出码。
$eapSaved = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $Javac @javacArgs 2>$javacLog | Out-Null
$javacExit = $LASTEXITCODE
$ErrorActionPreference = $eapSaved
if ($javacExit -ne 0) { if (Test-Path $javacLog) { Get-Content $javacLog | Write-Host }; Die 'javac 编译失败' }

# ---------- 7. dex ----------
Step 'd8'
$classFiles = (Get-ChildItem (Join-Path $Build 'classes') -Recurse -Filter '*.class' | ForEach-Object FullName).Count
Write-Host "    class 文件：$classFiles"
$d8Args = @('-Xmx2g', '-cp', $D8Jar, 'com.android.tools.r8.D8', '--release', '--min-api', '24', '--lib', $Platform,
            '--output', (Join-Path $Build 'dex'))
$d8Args += (Get-ChildItem (Join-Path $Build 'classes') -Recurse -Filter '*.class' | ForEach-Object FullName)
$d8Args += $ClassesJar
& $Java @d8Args
if ($LASTEXITCODE -ne 0) { Die 'd8 失败' }

# ---------- 8. 打包 + 对齐 ----------
Step '打包 dex + zipalign'
$unsigned = Join-Path $Build 'unsigned.apk'
Copy-Item -Force $baseApk $unsigned
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, [System.IO.Compression.ZipArchiveMode]::Update)
try {
  foreach ($dex in Get-ChildItem (Join-Path $Build 'dex') -Filter '*.dex') {
    $null = [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
              $zip, $dex.FullName, $dex.Name, [System.IO.Compression.CompressionLevel]::Optimal)
  }
} finally { $zip.Dispose() }

$aligned = Join-Path $Build 'aligned.apk'
& $Zipalign -f 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { Die 'zipalign 失败' }

# ---------- 9. 签名 ----------
if (-not (Test-Path -LiteralPath $Keystore)) {
  Step '未找到 keystore.jks，生成一个新的（口令都是 android）'
  & (Join-Path $JavaHome 'bin\keytool.exe') -genkeypair -keystore $Keystore `
    -storepass android -keypass android -alias tycoursetable `
    -keyalg RSA -keysize 2048 -validity 10000 `
    -dname "CN=TY CourseTable, OU=Dev, O=TY, L=Beijing, ST=Beijing, C=CN"
  if ($LASTEXITCODE -ne 0) { Die 'keytool 生成密钥失败' }
  Write-Host '    ⚠️ 新密钥无法覆盖安装手机上已有的 7.12！' -ForegroundColor Yellow
}

Step 'apksigner 签名'
$final = Join-Path $Out $ApkName
& $Apksigner sign --ks $Keystore --ks-pass pass:android --key-pass pass:android `
  --v1-signing-enabled true --v2-signing-enabled true `
  --out $final $aligned
if ($LASTEXITCODE -ne 0) { Die 'apksigner 签名失败' }

# ---------- 10. 自检 ----------
Step '验证产物（装包硬性条件）'
Assert-ApkInstallable -Apk $final
Step '验证签名与版本'
& $Apksigner verify --print-certs $final 2>&1 | Select-String 'DN|SHA-256' | Select-Object -First 2 | ForEach-Object { $_.Line }
& $Aapt2 dump badging $final 2>$null | Select-String -Pattern '^package:|^application-label' | ForEach-Object { $_.Line }
Get-Item $final | Select-Object @{n='文件';e={$_.Name}}, @{n='MB';e={[math]::Round($_.Length/1MB,2)}} | Format-Table -AutoSize

Write-Host ''
Write-Host "✅ 构建完成: $final" -ForegroundColor Green

# ---------- 11. 拷一份到显眼的「安装包」目录（直接拿去装，不用翻 build 目录） ----------
if ($PublishDir) {
  $parent = Split-Path -Parent $PublishDir
  if ($parent -and (Test-Path -LiteralPath $parent)) {
    try {
      New-Item -ItemType Directory -Force -Path $PublishDir | Out-Null
      $nice = Join-Path $PublishDir ("TY课程表-" + $VersionName + ".apk")
      Copy-Item -LiteralPath $final -Destination $nice -Force
      Write-Host ("📦 安装包: " + $nice) -ForegroundColor Green
    } catch {
      Write-Host ("（拷贝到 $PublishDir 失败：" + $_.Exception.Message + "）") -ForegroundColor Yellow
    }
  }
}
Write-Host '（如需关掉这个拷贝，加 -PublishDir ""）' -ForegroundColor DarkGray
