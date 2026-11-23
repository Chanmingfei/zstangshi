#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Codemagic 构建小工具：触发 / 轮询 / 取日志。

token 从本机既有文件读取，避免在命令行里回显：
    C:/Users/chenm/.workbuddy/tmp/watch_build.py 的 TOKEN
也可以用环境变量 CM_TOKEN 覆盖。

用法：
    python _tools/codemagic.py apps
    python _tools/codemagic.py run <workflowId> [branch]
    python _tools/codemagic.py watch <buildId>
    python _tools/codemagic.py log <buildId> <stepId>
"""
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

API = "https://api.codemagic.io"
TOKEN_FILE = "C:/Users/chenm/.workbuddy/tmp/watch_build.py"
APP_ID = "6af9a61d41fc1e07018ad414"
TERMINAL = {"finished", "failed", "canceled", "timeout"}


def token():
    t = os.environ.get("CM_TOKEN", "").strip()
    if t:
        return t
    try:
        txt = open(TOKEN_FILE, encoding="utf-8").read()
        m = re.search(r'TOKEN\s*=\s*"([^"]+)"', txt)
        if m:
            return m.group(1)
    except OSError:
        pass
    sys.exit("找不到 Codemagic token（CM_TOKEN 或 %s）" % TOKEN_FILE)


def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header("x-auth-token", token())
    req.add_header("Content-Type", "application/json")
    req.add_header("User-Agent", "zsts-ios-probe")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read().decode("utf-8", "replace")
            return r.status, (json.loads(raw) if raw.strip().startswith(("{", "[")) else raw)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:600]


def cmd_apps():
    st, r = call("GET", "/apps")
    if st != 200:
        sys.exit("读 apps 失败 %s: %s" % (st, r))
    for a in r.get("applications", []):
        print("%-26s %-40s %s" % (a.get("_id"), a.get("appName"), a.get("repository", {}).get("htmlUrl", "")))
        ws = a.get("workflows") or []
        for w in ws:
            print("    └ workflow: %s" % (w if isinstance(w, str) else w.get("id")))


def cmd_run(workflow, branch="main"):
    st, r = call("POST", "/builds", {"appId": APP_ID, "workflowId": workflow, "branch": branch})
    if st not in (200, 201):
        sys.exit("触发失败 %s: %s" % (st, r))
    print(json.dumps(r, ensure_ascii=False, indent=1)[:800])
    bid = r.get("buildId") or (r.get("build") or {}).get("_id")
    print("BUILD_ID=%s" % bid)
    return bid


def fetch(bid):
    st, r = call("GET", "/builds/" + bid)
    if st != 200:
        return None, "HTTP %s %s" % (st, r)
    return r.get("build"), None


def line(b):
    steps = b.get("buildActions") or []
    parts = []
    for s in steps:
        parts.append("%s=%s" % ((s.get("name") or "?")[:10], s.get("status")))
    return "%s | %s" % (b.get("status"), "  ".join(parts))


def cmd_watch(bid, limit=120):
    last = None
    build = None
    for _ in range(limit):
        build, err = fetch(bid)
        if err:
            print("[%s] %s" % (time.strftime("%H:%M:%S"), err))
            time.sleep(10)
            continue
        cur = line(build)
        if cur != last:
            print("[%s] %s" % (time.strftime("%H:%M:%S"), cur), flush=True)
            last = cur
        if build.get("status") in TERMINAL:
            break
        time.sleep(10)
    if not build:
        return
    print("=== 最终 %s ===" % build.get("status"))
    for s in build.get("buildActions") or []:
        print("  %-34s %-9s %s" % (str(s.get("name"))[:34], s.get("status"), s.get("_id")))
        for sub in s.get("subactions") or []:
            print("      └ %-30s %-8s %s" % (str(sub.get("name"))[:30], sub.get("status"), sub.get("_id")))
    for k in ("artifacts",):
        arts = build.get(k) or []
        if arts:
            print("--- artifacts ---")
            for a in arts:
                print("  %s  %s" % (a.get("name"), a.get("url")))


def cmd_log(bid, step):
    st, r = call("GET", "/builds/%s/step/%s" % (bid, step))
    if st != 200:
        sys.exit("取日志失败 %s: %s" % (st, r))
    print(r if isinstance(r, str) else json.dumps(r, ensure_ascii=False)[:4000])


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cmd = sys.argv[1]
    if cmd == "apps":
        cmd_apps()
    elif cmd == "run":
        cmd_run(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "main")
    elif cmd == "watch":
        cmd_watch(sys.argv[2])
    elif cmd == "log":
        cmd_log(sys.argv[2], sys.argv[3])
    else:
        sys.exit(__doc__)
