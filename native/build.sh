#!/usr/bin/env bash
# 掌上唐师 v4（Kotlin 重写）· 手动编译脚本
#
# 与 v3 同一套「aapt2 + kotlinc + d8 + apksigner」路线：
# 构建环境访问不了 maven.google.com，因此本工程**零第三方依赖**，
# 只用 Android framework API（不含 androidx）。
#
# 4.0.0 起源码是**纯 Kotlin**（native/app/src/main/kotlin），不再有 Java 源码。
# Kotlin 编译器：C:/Android/kotlinc19（embeddable 1.9.24 + coroutines 1.7.3），
# 通过 `java -cp lib/*.jar org.jetbrains.kotlin.cli.jvm.K2JVMCompiler` 调用。
# 选 1.9.24 而不是 2.x：build-tools 34 自带的 R8 8.2.2 读不懂 2.0 的 kotlin.Metadata，
# d8 阶段会刷一屏 stacktrace。
#
# 用法：bash native/build.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/native/app/src/main"
OUT="$ROOT/native/build"
KOTLIN_SRC="$APP/kotlin"

# ---- JDK ----
if [ -d "C:/Android/jdk17" ]; then
  JDK_HOME="$(ls -d C:/Android/jdk17/zulu* 2>/dev/null | head -1)"
  [ -z "$JDK_HOME" ] && JDK_HOME="C:/Android/jdk17"
fi
: "${JDK_HOME:?未找到 JDK 17}"

export JAVA_HOME="$JDK_HOME"
export ANDROID_SDK_ROOT="C:/Android/sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$JAVA_HOME/bin:$PATH"

BT="$ANDROID_SDK_ROOT/build-tools/34.0.0"
PLATFORM="$ANDROID_SDK_ROOT/platforms/android-34/android.jar"
AAPT2="$BT/aapt2.exe"
D8="$BT/d8.bat"
ZIPALIGN="$BT/zipalign.exe"
APKSIGNER="$BT/apksigner.bat"

# ---- Kotlin ----
KC_DIR="C:/Android/kotlinc19"
KC_CP="$(ls "$KC_DIR"/*.jar | tr "\n" ";")"
KOTLIN_STDLIB="$KC_DIR/kotlin-stdlib-1.9.24.jar"
: "${KC_CP:?未找到 Kotlin 编译器}"

VP="$ROOT/native/version.properties"
VERSION_CODE="$(sed -nE 's/^versionCode=([0-9]+).*/\1/p' "$VP" | head -1)"
VERSION_NAME="$(sed -nE 's/^versionName=(.+)$/\1/p' "$VP" | head -1)"
: "${VERSION_CODE:?version.properties 缺 versionCode}"
: "${VERSION_NAME:?version.properties 缺 versionName}"

echo "==> JDK: $JAVA_HOME"
echo "==> 版本 $VERSION_NAME ($VERSION_CODE)"

mkdir -p "$OUT" "$OUT/gen" "$OUT/classes" "$OUT/dex"

# classpath 用 Windows 风格路径：';' 拼接的串里 MSYS 不会自动转换 /c/... 形式
OUTW="$(cygpath -m "$OUT")"

MANIFEST_TMP="$OUT/AndroidManifest.xml"
sed 's#<manifest xmlns:android="http://schemas.android.com/apk/res/android">#<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.tstc.zstangshi">#' \
  "$APP/AndroidManifest.xml" > "$MANIFEST_TMP"

echo "==> [1/5] aapt2 compile"
"$AAPT2" compile --dir "$APP/res" -o "$OUT/res.flata"

echo "==> [2/5] aapt2 link"
"$AAPT2" link -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$MANIFEST_TMP" \
  --java "$OUT/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  "$OUT/res.flata"

echo "==> [3/5] javac（R.java）"
rm -rf "$OUT/classes"; mkdir -p "$OUT/classes"
javac -nowarn -J-Duser.language=en -J-Duser.country=US -d "$OUT/classes" -cp "$PLATFORM" --release 17 -encoding UTF-8 \
  $(find "$OUT/gen" -name '*.java')

echo "==> [4/5] kotlinc"
KT_FILES="$(find "$KOTLIN_SRC" -name '*.kt' 2>/dev/null || true)"
if [ -n "$KT_FILES" ]; then
  "$JAVA_HOME/bin/java" -Xmx2g -cp "$KC_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect \
    -jvm-target 1.8 \
    -classpath "$PLATFORM;$KOTLIN_STDLIB;$OUTW/classes" \
    -d "$OUT/classes" \
    $KT_FILES
else
  echo "    （无 Kotlin 源码）" >&2
  exit 1
fi

echo "==> [5/5] d8 / 组装 / 对齐 / 签名"
rm -rf "$OUT/dex"; mkdir -p "$OUT/dex"
rm -f "$OUT/classes.jar" "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk" "$ROOT/android/build/app-debug.apk"
jar cf "$OUT/classes.jar" -C "$OUT/classes" .
"$D8" --lib "$PLATFORM" --min-api 26 --output "$OUT/dex" "$OUT/classes.jar" "$KOTLIN_STDLIB"

cp "$OUT/base.apk" "$OUT/app-unsigned.apk"
python - "$OUT/app-unsigned.apk" "$OUT/dex" <<'PY'
import sys, os, zipfile
apk, dexdir = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', compression=zipfile.ZIP_STORED) as z:
    for f in sorted(os.listdir(dexdir)):
        if f.endswith('.dex'):
            z.write(os.path.join(dexdir, f), arcname=f)
PY
"$ZIPALIGN" -f -p 4 "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"

KS="$ROOT/android/build/debug.keystore"
if [ ! -f "$KS" ]; then
  echo "==> 生成 debug keystore"
  keytool -genkeypair -v -keystore "$KS" -alias androidkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=ZSTS, OU=Dev, O=ZSTS, L=Tangshan, ST=Hebei, C=CN"
fi
"$APKSIGNER" sign --ks "$KS" --ks-key-alias androidkey \
  --ks-pass pass:android --key-pass pass:android \
  --out "$ROOT/android/build/app-debug.apk" "$OUT/app-aligned.apk"

echo ""
echo "✅ 产出：$ROOT/android/build/app-debug.apk"
ls -la "$ROOT/android/build/app-debug.apk"
