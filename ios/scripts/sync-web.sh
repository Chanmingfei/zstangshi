#!/usr/bin/env bash
#
# sync-web.sh —— 把 H5 源码同步进 iOS 工程。
#
# 唯一真源是仓库根目录的 web/（与 Android 的 syncWebAssets 任务取自同一个目录）。
# 千万不要直接改 ios/ZhangShangTangShi/Resources/www —— 下次同步会被覆盖，
# 而且 iOS 与 Android 会各跑一份不同的 H5，双端漂移比 bug 更难查。
#
# 用法:
#   ./scripts/sync-web.sh          # 同步（覆盖 www）
#   ./scripts/sync-web.sh --check   # 只检查是否已同步，不一致则退出码 1（给 CI 用）
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$IOS_DIR/.." && pwd)"
DEST="$IOS_DIR/ZhangShangTangShi/Resources/www"

CHECK_ONLY=0
[[ "${1:-}" == "--check" ]] && CHECK_ONLY=1

# web/ 可能在仓库根（标准布局），也可能在当前工作区的 src_apk/ 下（源码包解压布局）
SRC=""
if [[ -n "${WEB_SRC:-}" && -d "${WEB_SRC}" ]]; then
  SRC="$WEB_SRC"
else
  for candidate in "$REPO_ROOT/web" "$REPO_ROOT/src_apk/web"; do
    if [[ -d "$candidate" ]]; then SRC="$candidate"; break; fi
  done
fi

if [[ -z "$SRC" ]]; then
  echo "✗ 找不到 H5 源码目录（试过: \$WEB_SRC, $REPO_ROOT/web, $REPO_ROOT/src_apk/web）" >&2
  echo "  可用 WEB_SRC=/path/to/web ./scripts/sync-web.sh 指定" >&2
  exit 1
fi

echo "▸ 源: $SRC"
echo "▸ 目标: $DEST"

# 防呆：源目录必须长得像 web/，别把整个仓库拷进去
if [[ ! -f "$SRC/index.html" ]]; then
  echo "✗ $SRC 里没有 index.html，看起来不是 web/ 目录" >&2
  exit 1
fi

if [[ "$CHECK_ONLY" == "1" ]]; then
  if [[ ! -d "$DEST" ]]; then
    echo "✗ 目标目录不存在，需要先同步" >&2
    exit 1
  fi
  if diff -rq "$SRC" "$DEST" >/dev/null 2>&1; then
    echo "✓ www 与源码一致"
    exit 0
  fi
  echo "✗ www 与源码不一致：" >&2
  diff -rq "$SRC" "$DEST" | head -20 >&2
  echo "  请执行 ./scripts/sync-web.sh 后重新提交" >&2
  exit 1
fi

mkdir -p "$(dirname "$DEST")"
rm -rf "$DEST"
cp -R "$SRC" "$DEST"
COUNT="$(find "$DEST" -type f | wc -l | tr -d ' ')"
echo "✓ 已同步 $COUNT 个文件"
