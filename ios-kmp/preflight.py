#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""在没有 Mac 的情况下，对 iOS 探针工程做力所能及的静态检查。

它**不能**替代编译器（SwiftUI/UIKit 的类型在 Windows 上无从检查），
但能拦住「手写代码最容易犯、又最浪费一次云构建」的那几类错误：

  1. 括号/大括号不配对（字符串、注释里的不算）
  2. Info.plist 不是合法 XML，或 ATS 配置漏了
  3. project.yml 指向的目录/文件不存在
  4. PRODUCT_NAME 不是 ASCII（免费重签会挂）
  5. 出现了 entitlement / App Group（免费账号用不了）
  6. Swift 里用了未声明的本项目类型

用法： python ios-kmp/preflight.py
退出码 0 = 未发现结构性问题。
"""
import io
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROBE = os.path.join(ROOT, "ios-kmp", "probe-ios")

problems = []
notes = []


def fail(msg):
    problems.append(msg)


def ok(msg):
    notes.append(msg)


# ---------- 1. Swift 括号平衡 ----------
def strip_literals(src):
    """去掉注释与字符串字面量，只留代码结构（插值里的括号也一并去掉）。"""
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != "\n":
                i += 1
        elif c == "/" and i + 1 < n and src[i + 1] == "*":
            depth = 1
            i += 2
            while i < n and depth:
                if src.startswith("/*", i):
                    depth += 1
                    i += 2
                elif src.startswith("*/", i):
                    depth -= 1
                    i += 2
                else:
                    i += 1
        elif c == '"':
            # 三引号
            if src.startswith('"""', i):
                i += 3
                while i < n and not src.startswith('"""', i):
                    i += 1
                i += 3
            else:
                i += 1
                while i < n:
                    if src[i] == "\\":
                        i += 2
                        continue
                    if src[i] == '"':
                        i += 1
                        break
                    i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


SWIFT_FILES = []
for root, _dirs, files in os.walk(os.path.join(PROBE, "Sources")):
    for f in files:
        if f.endswith(".swift"):
            SWIFT_FILES.append(os.path.join(root, f))

if not SWIFT_FILES:
    fail("Sources/ 下没有 .swift 文件")

decl_types = set()
for path in SWIFT_FILES:
    src = io.open(path, encoding="utf-8").read()
    code = strip_literals(src)
    for open_ch, close_ch in (("{", "}"), ("(", ")"), ("[", "]")):
        a, b = code.count(open_ch), code.count(close_ch)
        if a != b:
            fail("%s：'%s' 与 '%s' 数量不匹配（%d vs %d）"
                 % (os.path.basename(path), open_ch, close_ch, a, b))
    opens = len(re.findall(r"^\s*#if\b", src, re.M))
    ends = len(re.findall(r"^\s*#endif\b", src, re.M))
    if opens != ends:
        fail("%s：#if 与 #endif 不匹配（%d vs %d）" % (os.path.basename(path), opens, ends))
    for m in re.finditer(r"\b(?:final\s+)?(?:class|struct|enum|actor)\s+([A-Z]\w*)", src):
        decl_types.add(m.group(1))
    for m in re.finditer(r"^\s*(?:@\w+\s+)*enum\s+(\w+)", src, re.M):
        decl_types.add(m.group(1))

# 项目内类型（跨文件引用）是否存在
EXPECTED = {"ProbeLine", "JwProbe", "RSASPKI", "HTML", "ProbeViewModel", "ContentView", "ZSTSProbeApp"}
for t in sorted(EXPECTED):
    if t not in decl_types:
        fail("类型 %s 未在任何 Swift 文件里声明（多半是改名没改全）" % t)

# @main 只能有一个
main_count = 0
for path in SWIFT_FILES:
    src = io.open(path, encoding="utf-8").read()
    main_count += len(re.findall(r"@main\b", src))
if main_count != 1:
    fail("@main 出现 %d 次，必须恰好 1 次" % main_count)

# 禁止项：免费重签装不了的东西
for path in SWIFT_FILES:
    src = io.open(path, encoding="utf-8").read()
    if "com.apple.security.application-groups" in src:
        fail("%s：出现 App Group entitlement" % os.path.basename(path))
    if "try!" in src:
        fail("%s：出现 try!（探针崩溃就没日志了）" % os.path.basename(path))

# ---------- 2. Info.plist ----------
plist_path = os.path.join(PROBE, "Resources", "Info.plist")
if not os.path.isfile(plist_path):
    fail("缺少 Resources/Info.plist")
else:
    try:
        tree = ET.parse(plist_path)
        text = io.open(plist_path, encoding="utf-8").read()
        if "NSAppTransportSecurity" not in text:
            fail("Info.plist 没有 NSAppTransportSecurity —— 明文 http 登录必失败")
        if "NSExceptionAllowsInsecureHTTPLoads" not in text:
            fail("Info.plist 没有 NSExceptionAllowsInsecureHTTPLoads")
        if "tstc.edu.cn" not in text:
            fail("Info.plist 的例外域名里没有 tstc.edu.cn")
        if "UILaunchScreen" not in text:
            fail("Info.plist 缺少 UILaunchScreen（否则会以兼容模式跑）")
        ok("Info.plist：XML 合法，ATS 例外齐全")
    except ET.ParseError as e:
        fail("Info.plist 不是合法 XML：%s" % e)

# ---------- 3. project.yml ----------
yml_path = os.path.join(PROBE, "project.yml")
if not os.path.isfile(yml_path):
    fail("缺少 project.yml")
else:
    y = io.open(yml_path, encoding="utf-8").read()
    if "sources:" not in y:
        fail("project.yml 没有 sources")
    for rel in re.findall(r"path:\s*(\S+)", y):
        if not os.path.exists(os.path.join(PROBE, rel)):
            fail("project.yml 指向的路径不存在：%s" % rel)
    m = re.search(r"^\s*INFOPLIST_FILE:\s*(\S+)", y, re.M)
    if not m:
        fail("project.yml 没有 INFOPLIST_FILE")
    elif not os.path.isfile(os.path.join(PROBE, m.group(1))):
        fail("INFOPLIST_FILE 指向的文件不存在：%s" % m.group(1))
    m = re.search(r"^\s*PRODUCT_NAME:\s*(\S+)", y, re.M)
    if not m:
        fail("project.yml 没有 PRODUCT_NAME")
    else:
        name = m.group(1)
        if not name.isascii():
            fail("PRODUCT_NAME=%s 含非 ASCII 字符，重签会挂" % name)
        ok("PRODUCT_NAME=%s（ASCII）" % name)
    if "entitlements" in y.lower():
        fail("project.yml 提到 entitlements，免费账号重签会失败")

# ---------- 4. codemagic.yaml ----------
cm_path = os.path.join(ROOT, "codemagic.yaml")
if os.path.isfile(cm_path):
    c = io.open(cm_path, encoding="utf-8").read()
    if "probe-ios" not in c:
        fail("codemagic.yaml 里的路径与探针目录不一致")
    if "CODE_SIGNING_ALLOWED=NO" not in c:
        fail("codemagic.yaml 没关签名，云构建会因无证书失败")
    ok("codemagic.yaml：路径与无签编译参数齐全")
else:
    fail("缺少仓库根目录的 codemagic.yaml")

# ---------- 汇总 ----------
print("—— 静态检查 ——")
for n in notes:
    print("  ✓ " + n)
print("  共检查 %d 个 Swift 文件，声明类型 %d 个" % (len(SWIFT_FILES), len(decl_types)))
if problems:
    print("\n发现 %d 个问题：" % len(problems))
    for p in problems:
        print("  ✗ " + p)
    sys.exit(1)
print("\n未发现结构性问题。注意：Swift 类型正确性只能在云构建里验证。")
