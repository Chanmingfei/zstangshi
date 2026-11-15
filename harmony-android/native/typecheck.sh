#!/usr/bin/env bash
# 只跑 kotlinc 类型检查，不打包（用于快速迭代）。
# 依赖 native/build/classes 里已经生成的 R.class（先跑过一次 build.sh 即可）。
# 用法：bash native/typecheck.sh
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/native/app/src/main"
OUT="$ROOT/native/build"

if [ -d "C:/Android/jdk17" ]; then
  JDK_HOME="$(ls -d C:/Android/jdk17/zulu* 2>/dev/null | head -1)"
  [ -z "$JDK_HOME" ] && JDK_HOME="C:/Android/jdk17"
fi
: "${JDK_HOME:?未找到 JDK 17}"

PLATFORM="C:/Android/sdk/platforms/android-34/android.jar"
KC_DIR="C:/Android/kotlinc19"
KC_CP="$(ls "$KC_DIR"/*.jar | tr "\n" ";")"
KOTLIN_STDLIB="$KC_DIR/kotlin-stdlib-1.9.24.jar"
OUTW="$(cygpath -m "$OUT")"

# 清掉上一轮 Kotlin 产物，避免旧 class 掩盖编译错误；只保留 R.class / R$*.class
if [ ! -f "$OUT/classes/com/tstc/zstangshi/R.class" ]; then
  echo "!! 缺 R.class，请先完整跑一次 bash native/build.sh" >&2
  exit 1
fi
mkdir -p "$OUT/.rkeep"
cp "$OUT"/classes/com/tstc/zstangshi/R*.class "$OUT/.rkeep/" 2>/dev/null
rm -rf "$OUT/classes"
mkdir -p "$OUT/classes/com/tstc/zstangshi"
cp "$OUT"/.rkeep/*.class "$OUT/classes/com/tstc/zstangshi/"

KT_FILES="$(find "$APP/kotlin" -name '*.kt' | sort | tr '\n' ' ')"
KT_COUNT="$(echo $KT_FILES | wc -w)"
echo "==> kotlinc 类型检查：$KT_COUNT 个 .kt"

"$JDK_HOME/bin/java" -Xmx2g -cp "$KC_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 1.8 \
  -classpath "$PLATFORM;$KOTLIN_STDLIB;$OUTW/classes" \
  -d "$OUT/classes" $KT_FILES 2>&1 | grep -E "error:|warning: unused" || echo "==> 0 error"
