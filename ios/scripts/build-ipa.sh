#!/usr/bin/env bash
#
# build-ipa.sh —— 在 **macOS** 上把本工程打成 .ipa。
#
# 为什么需要脚本：Xcode 的 GUI 归档流程要人点六下、还得记得选对证书；
# CI 里更是只能用命令行。这个脚本把两条路径都固化了：
#
#   1) 未签名 ipa（无需 Apple 开发者账号）
#      ./scripts/build-ipa.sh
#      → build/ZhangShangTangShi-1.2.0-unsigned.ipa
#      这个包**不能直接双击安装**：iOS 要求可执行文件有签名。把它拖进
#      Sideloadly / AltStore / Xcode 的 Devices 窗口，用你自己的 Apple ID 重签后即可安装
#      （免费 Apple ID 签的包 7 天到期，开发者账号 1 年）。
#
#   2) 已签名 ipa（需要开发者账号与对应设备的 UDID）
#      ./scripts/build-ipa.sh --team ABCDE12345 --method development
#      → build/export/ZhangShangTangShi.ipa
#
#   3) 只在模拟器里跑一下看效果（不产出 ipa）
#      ./scripts/build-ipa.sh --simulator
#
set -euo pipefail

SCHEME="ZhangShangTangShi"
PROJECT_NAME="ZhangShangTangShi"
BUNDLE_ID="com.tstc.zstangshi"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT="$IOS_DIR/$PROJECT_NAME.xcodeproj"
BUILD_DIR="$IOS_DIR/build"
OUT_DIR="$IOS_DIR/build"

TEAM=""
METHOD="development"
MODE="unsigned"

usage() {
  cat <<'EOF'
用法: build-ipa.sh [选项]

  --team <TEAM_ID>      Apple 开发者团队 ID（不填 = 出未签名包）
  --method <m>          导出方式: development | ad-hoc | app-store | enterprise
                        默认 development
  --simulator           只编译并启动到模拟器，不产出 ipa
  --output <dir>        产物目录（默认 ios/build）
  --clean               先清理 build 目录
  -h, --help            显示本帮助
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --team) TEAM="${2:-}"; MODE="signed"; shift 2 ;;
    --method) METHOD="${2:-}"; shift 2 ;;
    --simulator) MODE="simulator"; shift ;;
    --output) OUT_DIR="${2:-}"; shift 2 ;;
    --clean) rm -rf "$BUILD_DIR"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数: $1" >&2; usage; exit 1 ;;
  esac
done

# ── 环境检查 ────────────────────────────────────────────────────────────────
if [[ "$(uname -s)" != "Darwin" ]]; then
  cat >&2 <<'EOF'
✗ 这个脚本必须在 macOS 上运行。

原因：iOS 的 .app 是 arm64 的 Mach-O，只能用 Apple 的 SDK（Xcode 自带）编译链接，
      而 Xcode 只提供 macOS 版本。Windows / Linux 上没有任何合法途径交叉编译出可安装的 ipa。

三条可行路径，按推荐顺序：
  1. 借一台 Mac（或 MacinCloud 之类的云端 Mac），跑 ./scripts/build-ipa.sh
  2. 用本仓库自带的 GitHub Actions 工作流在 macOS runner 上编译，
     产物直接从 Actions 页面下载（.github/workflows/ios-ipa.yml）
  3. 只做 UI 走查：把 Resources/www 当普通网页用 Safari / Chrome 打开
     （数据层会走 web 通道，教务系统会被 CORS 拦，仅用于看界面）
EOF
  exit 1
fi

if ! command -v xcodebuild >/dev/null 2>&1; then
  echo "✗ 找不到 xcodebuild，请先安装 Xcode（App Store）并执行: sudo xcode-select -s /Applications/Xcode.app" >&2
  exit 1
fi

echo "▸ Xcode: $(xcodebuild -version | head -1)"

# ── H5 资源同步（web/ 是唯一真源，绝不能手工改 Resources/www） ──────────────
# 用 bash 显式调用，不依赖文件的可执行位：Windows 上 chmod 是空操作，
# git 里提交的可执行位可能是 644，macOS runner 上直接执行会 Permission denied。
bash "$SCRIPT_DIR/sync-web.sh"

mkdir -p "$OUT_DIR"
# 产物目录转绝对路径：打包 ipa 要在 `(cd "$STAGE" && zip ...)` 的子 shell 里写产物，
# 相对路径（CI 传的 --output out）在子 shell 里会指向 $STAGE/out 而失败，
# zip 报 `Could not create output file`。
OUT_DIR="$(cd "$OUT_DIR" && pwd)"
echo "▸ 产物目录: $OUT_DIR"

# ── 模拟器 ──────────────────────────────────────────────────────────────────
if [[ "$MODE" == "simulator" ]]; then
  echo "▸ 编译到模拟器…"
  xcodebuild -project "$PROJECT" -scheme "$SCHEME" -configuration Debug \
    -destination 'platform=iOS Simulator,name=iPhone 15' \
    -derivedDataPath "$BUILD_DIR" \
    CODE_SIGNING_ALLOWED=NO build
  APP_PATH="$BUILD_DIR/Build/Products/Debug-iphonesimulator/$PROJECT_NAME.app"
  echo "▸ 安装并启动…"
  xcrun simctl boot "iPhone 15" >/dev/null 2>&1 || true
  open -a Simulator
  xcrun simctl install booted "$APP_PATH"
  xcrun simctl launch booted "$BUNDLE_ID"
  echo "✓ 已启动。Safari → 开发 → 模拟器 → 可调试 WebView（isInspectable 已开）"
  exit 0
fi

# ── 未签名 ipa ──────────────────────────────────────────────────────────────
if [[ "$MODE" == "unsigned" ]]; then
  echo "▸ 编译 Release（关闭签名）…"
  xcodebuild -project "$PROJECT" -scheme "$SCHEME" -configuration Release \
    -sdk iphoneos -derivedDataPath "$BUILD_DIR" \
    CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" \
    build

  APP_PATH="$BUILD_DIR/Build/Products/Release-iphoneos/$PROJECT_NAME.app"
  if [[ ! -d "$APP_PATH" ]]; then
    echo "✗ 编译产物缺失: $APP_PATH" >&2
    exit 1
  fi

  STAGE="$BUILD_DIR/stage"
  rm -rf "$STAGE"
  mkdir -p "$STAGE/Payload"
  cp -R "$APP_PATH" "$STAGE/Payload/"

  VERSION="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' \
    "$APP_PATH/Info.plist" 2>/dev/null || echo "1.2.0")"
  IPA="$OUT_DIR/$PROJECT_NAME-$VERSION-unsigned.ipa"
  rm -f "$IPA"
  (cd "$STAGE" && /usr/bin/zip -qry "$IPA" Payload)

  echo ""
  echo "✓ 已生成: $IPA"
  echo "  大小: $(du -h "$IPA" | cut -f1)"
  echo ""
  echo "  下一步（三选一）："
  echo "    · Sideloadly / AltStore：拖进去，用你的 Apple ID 重签后安装（免费账号 7 天有效）"
  echo "    · Xcode：Window → Devices and Simulators → 拖入 .app 安装"
  echo "    · 已有开发者证书：改用 --team <TEAM_ID> 出正式签名包"
  exit 0
fi

# ── 已签名 ipa ──────────────────────────────────────────────────────────────
if [[ -z "$TEAM" ]]; then
  echo "✗ --team 不能为空" >&2
  exit 1
fi

echo "▸ Archive（team=$TEAM）…"
ARCHIVE="$BUILD_DIR/$PROJECT_NAME.xcarchive"
rm -rf "$ARCHIVE"
xcodebuild archive -project "$PROJECT" -scheme "$SCHEME" -configuration Release \
  -destination 'generic/platform=iOS' -archivePath "$ARCHIVE" \
  DEVELOPMENT_TEAM="$TEAM" CODE_SIGN_STYLE=Automatic \
  -allowProvisioningUpdates

EXPORT_DIR="$OUT_DIR/export"
rm -rf "$EXPORT_DIR"
mkdir -p "$EXPORT_DIR"
EXPORT_PLIST="$BUILD_DIR/ExportOptions.plist"
cat > "$EXPORT_PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>method</key><string>$METHOD</string>
  <key>teamID</key><string>$TEAM</string>
  <key>signingStyle</key><string>automatic</string>
  <key>stripSwiftSymbols</key><true/>
  <key>compileBitcode</key><false/>
  <key>destination</key><string>export</string>
</dict>
</plist>
EOF

echo "▸ 导出 ipa（method=$METHOD）…"
xcodebuild -exportArchive -archivePath "$ARCHIVE" \
  -exportPath "$EXPORT_DIR" -exportOptionsPlist "$EXPORT_PLIST" \
  -allowProvisioningUpdates

IPA="$(find "$EXPORT_DIR" -name "*.ipa" -maxdepth 1 | head -1)"
if [[ -z "$IPA" ]]; then
  echo "✗ 导出失败，没有找到 ipa" >&2
  exit 1
fi
echo ""
echo "✓ 已生成: $IPA"
echo "  大小: $(du -h "$IPA" | cut -f1)"
