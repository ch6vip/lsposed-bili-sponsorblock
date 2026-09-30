# 发布流程（维护者）

> 用户向的构建说明在 [README](../README.md#构建与发布)；本文只覆盖发版与镜像同步。

## 发一个版本

推一个 `v*` tag 即可，`.github/workflows/release.yml` 自动完成：

1. 跑单测 → 构建**已签名** release APK → `apksigner verify` 校验签名（防止密钥没配好时静默产出未签名包）→ `gh release create` 发到本仓库；
2. **同步到模块镜像仓库** [`Xposed-Modules-Repo/io.github.ch6vip.bilisb`](https://github.com/Xposed-Modules-Repo/io.github.ch6vip.bilisb)，tag 格式固定 `[versionCode]-[versionName]`（如 `13-0.7.3`）。

```bash
git tag -a v0.7.3 -m "发布说明……"   # tag 注释就是 Release 页正文（单一来源）
git push origin v0.7.3
```

- **发布说明的单一来源是 tag 注释**：workflow 取 `%(contents)` 作 release body（签名 tag 自动截掉 PGP 块；空注释回退 `--generate-notes` 的 changelog 链接）。
- versionCode / versionName 一致性由 `checkModuleProp` Gradle 任务把守：`module.prop` 与 `build.gradle.kts` 不同步时构建直接失败。

## 为什么有第 2 步（镜像同步）

`modules.lsposed.org` 从镜像仓库取数据，而那个仓库**不会**从本仓库自动同步，
每发一版都必须在那边补一个 release。版本号刻意从**产物 APK** 里解析（`aapt2 dump badging`），
而非读 `build.gradle.kts` —— 手动补同步旧版本时，工作区的 versionCode 可能已经前进，
读 gradle 会把 0.6.0 的包标成 7-0.6.0。

### 手动补同步

同步失败、或要补齐历史上没同步的版本时：

Actions → Release → Run workflow → 填 `tag`（留空则取本仓库 latest release）。

同步是**幂等**的 —— 镜像仓库已有同名 release 时改为覆盖资产并刷新正文，可以放心重跑。

## Secrets

签名材料与同步凭据都从仓库 Secrets 读取，密钥本身**不入库**：

| Secret | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | 密钥库文件的 base64（`base64 -w0 release.jks`） |
| `KEYSTORE_PASSWORD` | 密钥库口令 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥口令 |
| `MIRROR_TOKEN` | 同步到镜像仓库用的 PAT。**未配置时只跳过同步，不影响发布本身** |

```bash
gh secret set MIRROR_TOKEN --repo ch6vip/lsposed-bili-sponsorblock
```

`MIRROR_TOKEN` 用 **classic** PAT，且只勾 `public_repo`：

- 打开 <https://github.com/settings/tokens/new>
- Note 随意（如 `bilisb-mirror-release`）；Expiration 建议 1 年
- Scopes **只勾 `public_repo`** —— **不要**勾整个 `repo`

> 为什么不用看起来更"现代"的细粒度 PAT？镜像仓库属于 `Xposed-Modules-Repo` 组织，
> 而模块作者只是该仓库的 **outside collaborator**。GitHub 官方文档明确写着：
> *Outside collaborators can only use personal access tokens (classic) to access
> organization repositories that they are a collaborator on.* —— 这类仓库
> **不会出现**在细粒度 PAT 的仓库列表里（搜索只会得到 "No repositories found"）。
>
> 好在镜像仓库是 **public**，所以 `public_repo` 足够创建 release，
> 而这个 scope **完全不涉及任何私有仓库**，泄漏面比 `repo` 小得多。

## 本地签名构建

在仓库根目录放一份 `keystore.properties`（已 gitignore）：

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

CI 与本地共用 `build.gradle.kts` 里的同一套读取逻辑（keystore.properties 优先，Secrets 环境变量兜底）。

> ⚠️ 密钥一旦丢失，**再也无法**给已发布的版本推出可原地升级的 APK ——
> Android 只认同一把签名密钥，届时只能强制所有用户卸载重装。请多地备份。
