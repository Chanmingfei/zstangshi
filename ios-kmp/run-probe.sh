#!/usr/bin/env bash
# P0 探针：在 Windows/Linux 上编译共享层并跑逻辑回归，不需要 macOS 与 Xcode。
#
#   bash ios-kmp/run-probe.sh
#
# 退出码 0 = 全绿。改完共享层随时重跑，秒级出结果 —— 这就是「共享层越厚、
# 云构建往返越少」的具体含义。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HERE="$ROOT/ios-kmp"
OUT="$HERE/.probe-out"

# ---- 工具链：沿用仓库里 Android 那套，不额外装东西 ----
# Kotlin 1.9.24 的编译器读不懂 JDK 25 的版本号（JavaVersion.parse 抛异常），
# 所以必须显式用 JDK 17，不能退回 PATH 上的 java。
JDK_HOME="${JDK_HOME:-C:/Android/jdk17}"
if [ ! -x "$JDK_HOME/bin/java" ] && [ -d "$JDK_HOME" ]; then
  JDK_HOME="$(ls -d "$JDK_HOME"/zulu* 2>/dev/null | head -1)"
fi
[ -x "$JDK_HOME/bin/java" ] || { echo "需要 JDK 17（Kotlin 1.9 不认 JDK 25）：$JDK_HOME"; exit 1; }

KC_DIR="C:/Android/kotlinc19"
: "${KC_DIR:?未找到 Kotlin 编译器}"
KC_CP="$(ls "$KC_DIR"/*.jar | tr "\n" ";")"
KOTLIN_STDLIB="$KC_DIR/kotlin-stdlib-1.9.24.jar"
[ -f "$KOTLIN_STDLIB" ] || { echo "找不到 $KOTLIN_STDLIB"; exit 1; }

echo "==> JDK: $JDK_HOME"
echo "==> Kotlin: $KC_DIR"

mkdir -p "$OUT"

# 共享层：只用 commonMain，编译时不带任何 Android/JVM 专属 jar。
# 能编过 = 这些代码没有偷用 java.* —— 这正是将来能搬去 iosMain 的前提。
# 硬校验：commonMain 里出现任何 java.* / android.* 引用就直接失败。
# 这是「这份代码将来能不能搬去 iosMain」的唯一客观标准，靠人眼 review 不可靠。
if grep -rn "^import java\.\|^import javax\.\|^import android\.\|^import androidx\." \
     "$HERE/shared/src/commonMain/kotlin" ; then
  echo "✗ 共享层里出现了平台专属 import（见上），KMP 的 iosMain 编不过"
  exit 1
fi
echo "==> 平台依赖检查：commonMain 无 java.* / android.* 引用 ✓"

SRC_SHARED=$(find "$HERE/shared/src/commonMain/kotlin" -name '*.kt' | tr '\n' ' ')
SRC_PROBE=$(find "$HERE/probe/src/main/kotlin" -name '*.kt' | tr '\n' ' ')

echo "==> 编译共享层 + 探针（$(echo $SRC_SHARED | wc -w) 个共享文件）"
"$JDK_HOME/bin/java" -Xmx1g -cp "$KC_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -jvm-target 17 \
  -classpath "$KOTLIN_STDLIB" \
  -d "$OUT/classes" \
  -nowarn \
  $SRC_SHARED $SRC_PROBE

echo "==> 运行探针"
cd "$ROOT"
# Windows 上 classpath 必须用 ';' 拼接的 Windows 路径，且关掉 MSYS 的路径改写，
# 否则 java.exe 收到的是 POSIX 形式，会报 ClassNotFoundException。
export MSYS_NO_PATHCONV=1
CP="$(cygpath -m "$OUT/classes");$(cygpath -m "$KOTLIN_STDLIB")"
"$JDK_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$CP" ProbeMainKt \
  "docs/diagnostics/bjkbdy-kb-sample.json"
