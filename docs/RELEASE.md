# 发版与 Gitee 同步

给维护者看的，普通用户不用管。

## 为什么要单独同步

Gitee 的「仓库镜像」只同步 git 对象：分支、tag、commit。
Release 是平台侧的另一套数据（标题、说明、附件），**镜像不会带过来**，
所以「GitHub 上发了版，Gitee 上自动就有了」这个预期实现不了。

两条路都行，选一条：

| 方式 | 触发 | 前提 |
| --- | --- | --- |
| GitHub Actions（推荐） | 在 GitHub 发布 Release，自动跑 | 配一次 `GITEE_TOKEN` |
| 本地一条命令 | 手动执行 | 手边有 token |

两条路用的是同一个脚本 `tools/sync_gitee_release.py`，逻辑一致、幂等。

**仓库简介（description）也不在 git 里**，镜像同样不会带过去。
`.github/workflows/sync-gitee-about.yml` 在 README 改动推送或发版时，用同一个
`GITEE_TOKEN` 调 Gitee PATCH 接口把简介覆写成含 iOS 的版本（Gitee 接口不支持 topics，
且 description 有长度上限，故用精简文案）。需要时也可在 Actions 页手动跑
「同步仓库简介到 Gitee」。

## 一次性配置

1. Gitee → 头像 → 设置 → 私人令牌 → 生成新令牌，勾 **projects**，复制。
2. GitHub 仓库 → Settings → Secrets and variables → Actions → New repository secret，
   名字 **`GITEE_TOKEN`**，值粘贴上一步的令牌。

> 令牌只放在 Actions Secret 里，不要写进代码或 README。
> Gitee 单附件上限 100 MB，本项目 APK 约 3.7 MB，远低于上限。

## 发版流程

```bash
# 1. 改版本号 + 编译（版本号只在 version.properties 里维护）
#    native/version.properties               标准版
#    harmony-android/native/version.properties 鸿蒙版

# 2. 打 tag 并推上去
git tag v4.0.6
git push origin v4.0.6

# 3. 在 GitHub 建 Release（附件上传 APK）
gh release create v4.0.6 android/build/app-debug.apk \
  --title "v4.0.6 · …" --notes-file release-notes-v4.0.6.md

# 4. 之后会自动发生：
#    镜像拉代码+tag → Actions 触发 → 脚本在 Gitee 建同名 Release 并上传同样的 APK
```

手动补同步（Actions 没跑、或想补历史版本）：

```bash
GITEE_TOKEN=xxxx python3 tools/sync_gitee_release.py                   # 全部 Release 对一遍
GITEE_TOKEN=xxxx python3 tools/sync_gitee_release.py v4.0.7            # 指定 tag
GITEE_TOKEN=xxxx python3 tools/sync_gitee_release.py v4.0.7 harmony-v4.1.2   # 多个 tag
python3 tools/sync_gitee_release.py v4.0.7 --dry-run                    # 只看差异，不写
```

也可以在 Actions 页面手动跑「同步 Release 到 Gitee」，`tag` 输入框留空＝全部对一遍，多个 tag 用空格分开。

> 只用这一行也可以：`gh workflow run 425084356 -R Chanmingfei/zstangshi -f tag="v4.0.7 harmony-v4.1.2"`

脚本做的事：

1. 按 tag 拉 GitHub Release（标题、说明、附件清单）；
2. Gitee 上没有就建 Release；缺 tag 时先建 tag，指向 GitHub 同名 tag 的 commit；
3. 说明里只把 `/releases/` 直链换成 Gitee 的（README 锚点不动，避免两边标题不一致跳空）；
4. 附件逐个下载再上传，已存在同名附件跳过 —— 重复跑不会产生重复附件；
5. 单个请求失败会重试（5xx / 断连），整轮失败 workflow 再重跑最多 3 次；因为幂等，重跑只会补上一轮没传完的部分。

**方向是单向的**：GitHub 是唯一源头，Gitee 只是镜像。Gitee 上没有 Release 事件 webhook（官方只有 Push / Tag Push / Issue / PR / 评论五种），也拿不到 `updated_at`，所以不做 Gitee → GitHub 的反向同步。若确实在 Gitee 上手工建了什么，以 GitHub 为准重跑一次即可覆盖。

## 与 App 内「检查更新」的关系

App 的更新检查是「Gitee → GitHub → gh-proxy」三源并发，下载前还会对候选地址测速。
所以：

- Gitee 同步正常 → 国内基本走 gitee.com 直连；
- Gitee 慢了或没同步 → 自动落到 GitHub，不会因为镜像滞后而降级或报错；
- 附件没传完时，Gitee 那条地址测速会失败，自动换源，不会让用户下到空文件。
