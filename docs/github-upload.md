# GitHub 上传与 Release 方案（预留）

本仓库当前为本地骨架，尚未关联远程、尚未推送。拿到 GitHub 仓库地址后按下面步骤执行即可。

> **当前基线（2026-11-21，v0.2.1）**：0.2.0 赛程取数并发与缓存提速之后的斯诺克中文译名与赛事名对照版本；手机端已内嵌 Kotlin 本地代理（复刻 Node 适配器），不再依赖手机端 Node 运行时。0.1.0 源文件快照见 `_backup/baseline-0.1.0/`。
> 本机暂未安装 Git；待 Git 环境就绪后，先按第 1 节 `git init` 提交基线，再按第 4 节打对应标签（当前 `v0.2.1`）并上传 `releases/` 产物。

## 1. 初始化本地仓库（如尚未 init）

```bash
cd app-release
git init
git add .
git commit -m "chore: 初始化赛事中心三端骨架（web / pc / mobile / releases / docs）"
```

## 2. 关联远程仓库

在 GitHub 新建空仓库（不要勾选 README / .gitignore，避免首推冲突），然后：

```bash
git remote add origin https://github.com/<你的账号>/<仓库名>.git
git branch -M main
git push -u origin main
```

> 若使用 SSH：`git remote add origin git@github.com:<你的账号>/<仓库名>.git`

## 3. 分支策略

| 分支 | 用途 |
| --- | --- |
| `main` | 稳定可发布版本，每次打 Tag 对应一个 Release |
| `dev` | 日常开发集成，合并回 main 后发布 |
| `feat/xxx` | 单个功能分支，从 dev 拉出，合并后删除 |

## 4. 版本与 Release 流程

1. 确认 `web/赛事中心.html`、`pc/`、`mobile/` 构建通过。
2. 在 `releases/` 产出产物：`SportsHub-<版本>-pc.zip`、`SportsHub-<版本>-pc.exe`（可选）、`SportsHub-<版本>-mobile.apk`。
3. 打 Tag：

```bash
git tag -a v0.2.1 -m "斯诺克中文译名与赛事名对照"
git push origin v0.2.1
```

4. 在 GitHub 仓库 **Releases → Draft a new release**，选择对应 Tag，上传 `releases/` 内的 zip / apk 作为 Release 附件（大二进制不进 git，只挂在 Release）。
5. Release Notes 写明：本版本功能清单、数据口径（示意 Mock）、已知限制、校验信息（文件大小 / 哈希）。

## 5. 产物命名规范

```
SportsHub-<版本号>-pc.zip        # PC 便携压缩包
SportsHub-<版本号>-pc.exe        # PC 安装/可执行（可选）
SportsHub-<版本号>-mobile.apk    # 手机安装包
```

版本号遵循语义化：`主版本.次版本.修订号`，如 `0.1.0`。

## 6. 密钥与敏感信息

- 签名用的 `*.keystore` / `*.jks`、`keystore.properties` 已在 `.gitignore` 排除，**禁止入库**。
- 发布签名密钥单独离线保管；APK 调试包可用 debug 签名，正式发布前替换为自有 release 密钥。
- 真实数据源 Token / 接口地址不写死在前端，放到环境配置或服务端代理。

## 7. 建议补充的仓库配置

- 仓库 Description、Topics（sports、basketball、football、esports、reactless、electron、android）。
- 开启 Issues、Wiki；CI（如 GitHub Actions）在接真实数据源后再补：构建 web 校验、PC 打包、APK 构建。
