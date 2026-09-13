#!/bin/bash
# ============================================================
#  TY课程表 · 构建脚本（不依赖 gradle）
#
#  依赖：
#    - JDK 17+（javac / keytool）
#    - Android SDK：platforms/android-34/android.jar
#                    build-tools（只需要 lib/d8.jar）
#    - aapt2 / apksigner / zipalign（系统包或 build-tools 里都行）
#    - curl（首次会自动下载 PdfBox-Android）
#
#  用法：
#    ANDROID_SDK_ROOT=/path/to/sdk ./build.sh
#  产物：release/TY课程表.apk
# ============================================================
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-/root/android-sdk/sdk}"
PLATFORM="$SDK/platforms/android-34/android.jar"
D8JAR="${D8_JAR:-$SDK/build-tools/android-14/lib/d8.jar}"

BUILD="$ROOT/build"
OUT="$ROOT/release"
LIBS="$ROOT/libs"
AAR="$LIBS/pdfbox-android-2.0.27.0.aar"
FONT="$ROOT/fonts/HarmonyOS_Sans_SC_Subset.ttf"
APKNAME="TY-CourseTable-7.12.apk"

# ---- 工具定位（优先系统命令，其次 build-tools）
pick() { command -v "$1" >/dev/null 2>&1 && echo "$1" || echo "$SDK/build-tools/android-14/$1"; }
AAPT2="$(pick aapt2)"
APKSIGNER="$(pick apksigner)"
ZIPALIGN="$(pick zipalign)"

for f in "$PLATFORM" "$D8JAR" "$FONT"; do
  [ -f "$f" ] || { echo "缺少依赖: $f"; exit 1; }
done

# ---- 依赖下载（PdfBox-Android，Apache-2.0）
mkdir -p "$LIBS"
if [ ! -f "$AAR" ]; then
  echo "下载 PdfBox-Android 2.0.27.0 …"
  curl -sL -o "$AAR" "https://repo1.maven.org/maven2/com/tom-roush/pdfbox-android/2.0.27.0/pdfbox-android-2.0.27.0.aar"
fi
[ -f "$LIBS/aar/classes.jar" ] || { mkdir -p "$LIBS/aar"; unzip -oq "$AAR" -d "$LIBS/aar"; }

rm -rf "$BUILD"
mkdir -p "$BUILD/res" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$OUT"

# ---- 1) 生成 Android 版解析器（桌面版 PDFBox 包名 → pdfbox-android）
mkdir -p "$BUILD/src"
for f in "$ROOT"/src/com/dsh/coursetable/*.java; do
  sed 's/^import org\.apache\.pdfbox\./import com.tom_roush.pdfbox./' "$f" > "$BUILD/src/$(basename "$f")"
done

# ---- 2) assets = pdfbox 资源 + 应用自带（字体、许可文本）
rm -rf "$BUILD/assets"
cp -r "$LIBS/aar/assets" "$BUILD/assets"
[ -d "$ROOT/app/assets" ] && cp -r "$ROOT/app/assets/." "$BUILD/assets/"
mkdir -p "$BUILD/assets/fonts"
cp "$FONT" "$BUILD/assets/fonts/HarmonyOS_Sans_SC_Regular.ttf"
cp "$FONT" "$BUILD/assets/fonts/HarmonyOS_Sans_SC_Bold.ttf"

# ---- 3) 资源 + manifest
"$AAPT2" compile --dir "$ROOT/app/res" -o "$BUILD/res.zip"
"$AAPT2" link \
  -o "$BUILD/base.apk" \
  -I "$PLATFORM" \
  --manifest "$ROOT/app/AndroidManifest.xml" \
  -A "$BUILD/assets" \
  --java "$BUILD/gen" \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code 16 --version-name 7.12 \
  "$BUILD/res.zip"

# ---- 4) 编译 java
find "$BUILD/src" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
set -o pipefail
javac -encoding UTF-8 -source 8 -target 8 -nowarn \
  -bootclasspath "$PLATFORM" \
  -classpath "$PLATFORM:$LIBS/aar/classes.jar" \
  -d "$BUILD/classes" @"$BUILD/sources.txt"
set +o pipefail

# ---- 5) dex
java -cp "$D8JAR" com.android.tools.r8.D8 --release --min-api 24 --lib "$PLATFORM" \
  --output "$BUILD/dex" \
  $(find "$BUILD/classes" -name '*.class') \
  "$LIBS/aar/classes.jar"

# ---- 6) 打包 + 对齐
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
( cd "$BUILD/dex" && zip -q -X "$BUILD/unsigned.apk" classes*.dex )
"$ZIPALIGN" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

# ---- 7) 签名（首次自动生成 keystore；正式发布请换成自己的）
KS="$ROOT/keystore.jks"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias tycoursetable -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=TY CourseTable, OU=Dev, O=TY, L=Beijing, ST=Beijing, C=CN" >/dev/null 2>&1
fi
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT/$APKNAME" "$BUILD/aligned.apk"

echo
echo "✅ 构建完成: $OUT/$APKNAME"
ls -l "$OUT"
