#!/usr/bin/env bash
# 掌上唐师 · 手动编译脚本（绕过 AGP，零第三方依赖）
#
# 背景：构建环境无法访问 maven.google.com（被代理拦截），常规 Gradle+AGP 路线走不通。
# 我们的 Java 代码零外部依赖，因此走「aapt2 + javac + d8 + apksigner」手动路线，
# 只依赖 dl.google.com（SDK 包，可达）与 JDK 17（Azul CDN，可达）。
#
# 前置：
#   - JDK 17 已解压到 $JDK_HOME（脚本自动探测 C:/Android/jdk17 下的 zulu 目录）
#   - Android SDK 已装好 platform-tools / platforms;android-34 / build-tools;34.0.0
#     （用 cmdline-tools 的 sdkmanager 安装，见下方 INSTALL 段说明）
#   - web/ 已同步到 app/src/main/assets/www（替代 gradle 的 syncWebAssets）
#
# 用法：在 Git Bash 里 bash android/build-manual.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"   # 项目根
APP="$ROOT/android/app/src/main"
OUT="$ROOT/android/build"
PKG_DIR="com/tstc/zstangshi"

# ── 探测 JDK 17 ──
if [ -d "C:/Android/jdk17" ]; then
  JDK_HOME="$(ls -d C:/Android/jdk17/zulu* 2>/dev/null | head -1)"
  [ -z "$JDK_HOME" ] && JDK_HOME="C:/Android/jdk17"
fi
: "${JDK_HOME:?未找到 JDK 17，请先解压到 C:/Android/jdk17}"

export JAVA_HOME="$JDK_HOME"            # C:/ 风格：bash 与 .bat 工具都能识别
export ANDROID_SDK_ROOT="C:/Android/sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$JAVA_HOME/bin:$PATH"

BT="$ANDROID_SDK_ROOT/build-tools/34.0.0"
PLATFORM="$ANDROID_SDK_ROOT/platforms/android-34/android.jar"
AAPT2="$BT/aapt2.exe"
D8="$BT/d8.bat"
ZIPALIGN="$BT/zipalign.exe"
APKSIGNER="$BT/apksigner.bat"
MANIFEST="$APP/AndroidManifest.xml"

echo "==> JDK      : $JAVA_HOME"
echo "==> SDK      : $ANDROID_SDK_ROOT"
echo "==> build-tools: $BT"

command -v java >/dev/null && java -version 2>&1 | head -1

mkdir -p "$OUT" "$OUT/gen" "$OUT/classes"

# ── 0. 同步 web/ -> assets/www（替代 gradle 的 syncWebAssets，保证一条命令可复现）──
echo "==> 同步 web/ -> assets/www"
rm -rf "$APP/assets/www"
mkdir -p "$APP/assets/www"
cp -r "$ROOT/web/." "$APP/assets/www/"
rm -rf "$APP/assets/www/dev"   # dev/ 调试脚手架，应用从不引用，剔除以缩减体积

# ── 0. 临时 manifest：注入 package 属性（原件保持 gradle 兼容的 namespace）──
MANIFEST_TMP="$OUT/AndroidManifest.xml"
sed 's#<manifest xmlns:android="http://schemas.android.com/apk/res/android">#<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.tstc.zstangshi">#' \
  "$MANIFEST" > "$MANIFEST_TMP"
echo "==> 临时 manifest 已生成（含 package 属性）"

# ── 1. aapt2 compile 资源 ──
echo "==> [1/5] aapt2 compile"
"$AAPT2" compile --dir "$APP/res" -o "$OUT/res.flata"

# ── 2. aapt2 link：产出 base.apk（资源+清单+assets）并生成 R.java ──
echo "==> [2/5] aapt2 link (+生成 R.java, 内联 assets/www)"
"$AAPT2" link -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$MANIFEST_TMP" \
  --java "$OUT/gen" \
  -A "$APP/assets" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --version-code 5 \
  --version-name 1.2.0 \
  "$OUT/res.flata"

# ── 3. javac 编译 R.java + 5 个 Java 源文件 ──
echo "==> [3/5] javac"
# 清掉上轮产物：javac 只覆盖它重新生成的 .class，旧匿名内部类（如 WebActivity$1）的残留
# 会被一起打进 classes.jar，导致 d8 在 dexing 残留匿名类时 NPE。必须整目录清空后重编。
rm -rf "$OUT/classes"
mkdir -p "$OUT/classes"
javac -d "$OUT/classes" -cp "$PLATFORM" --release 17 \
  $(find "$OUT/gen" -name '*.java') \
  $(find "$APP/java" -name '*.java')

# ── 4. d8 把 class 转 dex（先打包成 jar，再转 dex 输出到目录，自动生成 classes.dex）──
echo "==> [4/5] d8 -> classes.dex"
mkdir -p "$OUT/dex"
jar cf "$OUT/classes.jar" -C "$OUT/classes" .
"$D8" --lib "$PLATFORM" --output "$OUT/dex" "$OUT/classes.jar"

# ── 5. 组装 + 对齐 + 签名 ──
echo "==> [5/5] 组装 / zipalign / 签名"
# 清掉上一轮的产物，避免 zipalign 因输出已存在而拒绝覆盖
rm -f "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk" "$OUT/app-debug.apk"
cp "$OUT/base.apk" "$OUT/app-unsigned.apk"
# 把 dex/ 下所有 .dex 以「不压缩」方式加进 APK（ZIP_STORED，匹配标准 APK 结构，zipalign 才能对齐）
python3 - "$OUT/app-unsigned.apk" "$OUT/dex" <<'PY'
import sys, os, zipfile
apk, dexdir = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', compression=zipfile.ZIP_STORED) as z:
    for f in sorted(os.listdir(dexdir)):
        if f.endswith('.dex'):
            z.write(os.path.join(dexdir, f), arcname=f)
PY
"$ZIPALIGN" -f -p 4 "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"

if [ ! -f "$OUT/debug.keystore" ]; then
  echo "==> 生成 debug 签名 keystore"
  keytool -genkeypair -v -keystore "$OUT/debug.keystore" -alias androidkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=ZSTS, OU=Dev, O=ZSTS, L=Tangshan, ST=Hebei, C=CN"
fi
"$APKSIGNER" sign --ks "$OUT/debug.keystore" --ks-key-alias androidkey \
  --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/app-debug.apk" "$OUT/app-aligned.apk"

echo ""
echo "✅ 产出：$OUT/app-debug.apk"
ls -la "$OUT/app-debug.apk"
