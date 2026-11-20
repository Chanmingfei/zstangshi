#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 GitHub Release 同步到 Gitee。

Gitee 的「仓库镜像」只同步代码（分支 / tag / commit），
Release 和附件不在 git 里，镜像不会带过来，只能调 Gitee OpenAPI 补。

用法：
    GITEE_TOKEN=xxxx python3 tools/sync_gitee_release.py            # 同步最新
    GITEE_TOKEN=xxxx python3 tools/sync_gitee_release.py v4.0.6     # 同步指定 tag
    GITEE_TOKEN=xxxx python3 tools/sync_gitee_release.py v4.0.6 --dry-run

幂等：已存在的 Release 只更新标题/说明，已有同名附件不重复上传。
"""

import argparse
import json
import mimetypes
import os
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_GH = "Chanmingfei/zstangshi"
DEFAULT_GE = "mingfei123/zstangshi"
# Gitee 单个附件上限 100 MB
MAX_ASSET = 100 * 1000 * 1000
UA = "zstangshi-release-sync/1.0"


class NetError(Exception):
    """可重试的网络层失败（连接断开、写超时、502 之类）。"""


def http(method, url, data=None, headers=None, timeout=120, retries=3, note=""):
    """带重试的请求。

    GitHub 的 runner 在海外机房，到 gitee.com 的链路时好时坏，
    上传 1.7 MB 附件时单次 send 卡住很常见 —— 所以写操作必须能重来。
    """
    last = None
    for attempt in range(1, retries + 1):
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("User-Agent", UA)
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        t0 = time.time()
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                raw = r.read()
            if attempt > 1 or time.time() - t0 > 20:
                print("    · %s 用时 %.1fs（第 %d 次）" % (note or method, time.time() - t0, attempt),
                      flush=True)
            return r.status, raw
        except urllib.error.HTTPError as e:
            body = e.read()
            # 5xx 才值得重试，4xx 是参数/权限问题，重试也是一样的结果
            if e.code < 500 or attempt == retries:
                return e.code, body
            last = "HTTP %d" % e.code
        except (urllib.error.URLError, OSError) as e:
            last = str(getattr(e, "reason", e))
            if attempt == retries:
                raise NetError("%s %s 失败（%d 次）：%s" % (method, note or url, retries, last))
        wait = min(30, 3 * attempt * attempt)
        print("    ! %s 第 %d 次失败（%s），%ds 后重试" % (note or method, attempt, last, wait),
              flush=True)
        time.sleep(wait)
    raise NetError("%s %s 失败：%s" % (method, note or url, last))


def json_of(status, raw, what):
    if status < 200 or status >= 300:
        text = raw.decode("utf-8", "replace")[:300]
        raise SystemExit("%s 失败（HTTP %d）：%s" % (what, status, text))
    try:
        return json.loads(raw.decode("utf-8"))
    except ValueError:
        raise SystemExit("%s 返回的不是 JSON：%s" % (what, raw[:200]))


def gh_get(path, token):
    url = "https://api.github.com" + path
    headers = {"Accept": "application/vnd.github+json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    status, raw = http("GET", url, headers=headers)
    if status == 404:
        return None
    return json_of(status, raw, "GitHub " + path)


def ge_call(method, path, token, query=None, body=None, form=None, what="",
            timeout=150, retries=3):
    params = dict(query or {})
    if token:
        params["access_token"] = token
    url = "https://gitee.com/api/v5" + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    headers = {}
    data = None
    if form is not None:
        data = form["body"]
        headers.update(form["headers"])
    elif body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    status, raw = http(method, url, data=data, headers=headers,
                       timeout=timeout, retries=retries, note=what or path)
    if status == 404:
        return None
    return json_of(status, raw, what or ("Gitee " + method + " " + path))


def multipart(fields, file_field, filename, content, content_type):
    boundary = "----zstangshi" + os.urandom(8).hex()
    out = []
    for k, v in fields.items():
        out.append(("--" + boundary + "\r\n").encode())
        out.append(('Content-Disposition: form-data; name="%s"\r\n\r\n' % k).encode())
        out.append(str(v).encode("utf-8") + b"\r\n")
    out.append(("--" + boundary + "\r\n").encode())
    out.append(('Content-Disposition: form-data; name="%s"; filename="%s"\r\n'
                % (file_field, filename)).encode("utf-8"))
    out.append(("Content-Type: %s\r\n\r\n" % content_type).encode())
    out.append(content)
    out.append(b"\r\n--" + boundary.encode() + b"--\r\n")
    return b"".join(out), "multipart/form-data; boundary=" + boundary


def rewrite_body(body, gh, ge):
    """把说明里的「下载直链」换成 Gitee 的，其余原文照抄。

    只替换 /releases/ 路径：README 锚点（github.com/xxx#9-…）在 Gitee 上
    锚点文本不一定一致，硬换反而会跳到空处，所以不动。
    """
    if not body:
        return ""
    body = body.replace("https://github.com/%s/releases/" % gh,
                        "https://gitee.com/%s/releases/" % ge)
    body = body.replace("http://github.com/%s/releases/" % gh,
                        "https://gitee.com/%s/releases/" % ge)
    body = body.replace("github.com/%s/releases/" % gh,
                        "gitee.com/%s/releases/" % ge)
    return body


def tag_commit_sha(gh, tag, token):
    ref = gh_get("/repos/%s/git/ref/tags/%s" % (gh, urllib.parse.quote(tag)), token)
    if not ref:
        return None
    obj = ref.get("object") or {}
    if obj.get("type") == "tag":
        # 附注标签：再取一次，剥到真正的 commit
        peeled = gh_get("/repos/%s/git/tags/%s" % (gh, obj.get("sha")), token)
        if peeled and peeled.get("object"):
            return peeled["object"].get("sha")
    return obj.get("sha")


def gitee_tag_exists(ge, tag, token):
    page = 1
    while page <= 5:
        tags = ge_call("GET", "/repos/%s/tags" % ge, token,
                       query={"per_page": 100, "page": page}, what="查询 Gitee tag")
        if not tags:
            return False
        for t in tags:
            if t.get("name") == tag:
                return True
        if len(tags) < 100:
            return False
        page += 1
    return False


def gitee_default_branch(ge, token):
    repo = ge_call("GET", "/repos/%s" % ge, token, what="读取 Gitee 仓库信息")
    return (repo or {}).get("default_branch") or "master"


def ensure_tag(ge, tag, sha, token, dry):
    if gitee_tag_exists(ge, tag, token):
        return True
    if dry:
        print("  [dry-run] 会在 Gitee 建 tag %s → %s" % (tag, (sha or "")[:10]))
        return True
    if not sha:
        print("  ! GitHub 上找不到 tag %s 对应的提交" % tag)
        return False
    status, raw = http(
        "POST",
        "https://gitee.com/api/v5/repos/%s/tags?access_token=%s"
        % (ge, urllib.parse.quote(token)),
        data=json.dumps({"refs": sha, "tag_name": tag, "tag_message": tag},
                        ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json; charset=utf-8"},
        timeout=150, retries=3, note="建 tag " + tag,
    )
    if 200 <= status < 300:
        print("  + 已在 Gitee 建 tag %s" % tag)
        return True
    print("  ! 建 tag 失败（HTTP %d）：%s" % (status, raw.decode("utf-8", "replace")[:200]))
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("tags", nargs="*", default=[],
                    help="要同步的 tag，可以给多个；留空＝同步 GitHub 上全部 Release")
    ap.add_argument("--github", default=os.environ.get("SYNC_GH_REPO", DEFAULT_GH))
    ap.add_argument("--gitee", default=os.environ.get("SYNC_GE_REPO", DEFAULT_GE))
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    token = os.environ.get("GITEE_TOKEN", "").strip()
    if not token and not args.dry_run:
        raise SystemExit("缺少 GITEE_TOKEN（Gitee → 设置 → 私人令牌，勾 projects）")

    gh, ge = args.github, args.gitee
    gh_token = os.environ.get("GITHUB_TOKEN", "").strip()

    releases = []
    if args.tags:
        for tag in args.tags:
            rel = gh_get("/repos/%s/releases/tags/%s"
                         % (gh, urllib.parse.quote(tag)), gh_token)
            if not rel:
                raise SystemExit("GitHub 上没有 tag 为 %s 的 Release" % tag)
            releases.append(rel)
    else:
        # 补历史：一次把所有 Release 都过一遍，已经对齐的会很快跳过
        page = 1
        while page <= 10:
            rows = gh_get("/repos/%s/releases?per_page=100&page=%d" % (gh, page), gh_token)
            if not rows:
                break
            releases.extend(rows)
            if len(rows) < 100:
                break
            page += 1
        if not releases:
            raise SystemExit("GitHub 上还没有任何 Release")

    print("待处理 %d 个 Release" % len(releases), flush=True)
    failed = []
    for rel in releases:
        try:
            sync_one(rel, gh, ge, token, gh_token, args.dry_run)
        except NetError as e:
            print("  ! 跳过 %s：%s" % (rel.get("tag_name"), e), flush=True)
            failed.append(rel.get("tag_name"))
        except SystemExit as e:
            print("  ! 跳过 %s：%s" % (rel.get("tag_name"), e), flush=True)
            failed.append(rel.get("tag_name"))

    if failed:
        print("\n有 %d 个 Release 没同步成功：%s" % (len(failed), ", ".join(failed)), flush=True)
        sys.exit(1)
    print("\n全部完成。", flush=True)


def sync_one(release, gh, ge, token, gh_token, dry_run):
    tag = release["tag_name"]
    name = release.get("name") or tag
    body = rewrite_body(release.get("body") or "", gh, ge)
    prerelease = bool(release.get("prerelease"))
    assets = [a for a in release.get("assets") or [] if a.get("name")]

    print("== %s -> %s" % (tag, ge), flush=True)
    print("   标题：%s" % name, flush=True)
    print("   附件：%s" % (", ".join(a["name"] for a in assets) or "无"), flush=True)

    existing = ge_call("GET", "/repos/%s/releases/tags/%s"
                       % (ge, urllib.parse.quote(tag)), token, what="查询 Gitee Release")

    if existing:
        rel_id = existing["id"]
        print("   Release 已存在（id=%s）" % rel_id, flush=True)
        changed = (existing.get("name") != name
                   or (existing.get("body") or "").strip() != body.strip())
        if changed:
            if dry_run:
                print("  [dry-run] 更新标题/说明", flush=True)
            else:
                ge_call("PATCH", "/repos/%s/releases/%s" % (ge, rel_id), token,
                        body={"tag_name": tag, "name": name, "body": body,
                              "prerelease": prerelease},
                        what="更新 Gitee Release")
                print("  ~ 已更新标题/说明", flush=True)
        else:
            print("  = 标题/说明无变化", flush=True)
    else:
        sha = tag_commit_sha(gh, tag, gh_token)
        print("   提交：%s" % (sha or "（未知）"), flush=True)
        tagged = ensure_tag(ge, tag, sha, token, dry_run)
        target = sha if tagged and sha else gitee_default_branch(ge, token)
        if dry_run:
            print("  [dry-run] 会创建 Release（target_commitish=%s）" % (target or "")[:10], flush=True)
            rel_id = 0
        else:
            created = ge_call("POST", "/repos/%s/releases" % ge, token,
                              body={"tag_name": tag, "name": name, "body": body,
                                    "prerelease": prerelease,
                                    "target_commitish": target},
                              what="创建 Gitee Release")
            rel_id = created["id"]
            print("  + 已创建 Release（id=%s，target=%s）" % (rel_id, (target or "")[:10]),
                  flush=True)

    if not rel_id:
        print("[dry-run] 结束，未做任何写操作。", flush=True)
        return

    have = set()
    page = 1
    while page <= 5:
        rows = ge_call("GET", "/repos/%s/releases/%s/attach_files" % (ge, rel_id), token,
                       query={"per_page": 100, "page": page}, what="查询 Gitee 附件") or []
        for f in rows:
            have.add(f.get("name"))
        if len(rows) < 100:
            break
        page += 1

    missing = 0
    for a in assets:
        aname = a["name"]
        if aname in have:
            print("  = 附件已存在：%s" % aname, flush=True)
            continue
        size = a.get("size") or 0
        if size > MAX_ASSET:
            print("  ! 跳过 %s：%d 字节，超过 Gitee 单附件 100 MB 上限" % (aname, size), flush=True)
            continue
        if dry_run:
            print("  [dry-run] 会下载并上传：%s（%d 字节）" % (aname, size), flush=True)
            continue

        print("  ↓ 下载 GitHub 附件：%s（%.2f MiB）" % (aname, size / 1048576.0), flush=True)
        status, blob = http("GET", a["browser_download_url"],
                            headers={"Accept": "application/octet-stream"},
                            timeout=180, retries=3, note="下载 " + aname)
        if status != 200:
            print("  ! 下载失败（HTTP %d），跳过 %s" % (status, aname), flush=True)
            missing += 1
            continue
        if len(blob) != size and size:
            print("  ! 大小不符（%d ≠ %d），仍按实际内容上传" % (len(blob), size), flush=True)

        ctype = mimetypes.guess_type(aname)[0] or "application/octet-stream"
        payload, ct = multipart({}, "file", aname, blob, ctype)
        # 5 MB 走海外→国内的链路本来就慢，再叠上 gitee 偶发卡顿，
        # 单次 send 给到 300 秒；整段最多试 4 次。
        up = ge_call("POST", "/repos/%s/releases/%s/attach_files" % (ge, rel_id), token,
                     form={"body": payload, "headers": {"Content-Type": ct}},
                     what="上传附件 " + aname, timeout=300, retries=4)
        got = (up or {}).get("name") or aname
        print("  + 已上传：%s" % got, flush=True)

    if missing:
        raise SystemExit("有 %d 个附件没传上去" % missing)
    print("   完成：https://gitee.com/%s/releases/tag/%s"
          % (ge, urllib.parse.quote(tag)), flush=True)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
