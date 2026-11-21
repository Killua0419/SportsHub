# GitHub 网页版更新步骤（v0.1.0 → v0.2.0）

前提：仓库里**已经上传过 v0.1.0** 的源码，并且已经发过 `v0.1.0` Release。本次在原仓库上做两件事：

1. **更新仓库源码**到 0.2.0（同名文件覆盖，新增文件加入；旧 0.1.0 历史提交保留）。
2. **新发一个 `v0.2.0` Release**，把 0.2.0 的四个打包产物挂为附件；旧 `v0.1.0` Release 保持不动。

全程浏览器网页操作，不需要本机安装 Git。源码进仓库，exe/zip/apk 只挂 Release 附件、不进源码目录。

## 0. 本次准备清单

### 本地源码包（上传到仓库覆盖）

目录：`github-upload/SportsHub/`（本包已剔除依赖、构建产物与本机路径），顶层直接是 `web/ proxy/ pc/ mobile/ docs/ README.md .gitignore`。

```
SportsHub/
├── README.md              # 项目说明（已更新为 v0.2.0）
├── .gitignore             # 忽略 node_modules / build / 二进制 / 密钥
├── web/赛事中心.html       # 单文件页面（权威副本，0.2.0）
├── proxy/server.js        # 本地代理（Node 零依赖，含聚合端点 /proxy/games/all）
├── pc/                    # Electron 桌面壳源码
├── mobile/android/        # 安卓 WebView 壳源码
└── docs/                  # 架构说明 / 更新日志 / 上传方案
```

### 本次相对 0.1.0 变更的文件（上传时重点核对这些已被覆盖）

| 路径 | 变更 |
| --- | --- |
| `web/赛事中心.html` | 前端聚合端点、缓存单键、并发/超时、去重逻辑，版本号 0.2.0 |
| `proxy/server.js` | 新增 `/proxy/games/all` 聚合端点，多源并发、3s 超时降级、30s 短缓存 |
| `pc/main.js` | 桌面壳主进程（无逻辑改动则与 0.1.0 一致，随包覆盖即可） |
| `pc/proxy.js` | 与 `proxy/server.js` 同源副本，随包覆盖 |
| `pc/package.json` | version 0.2.0 |
| `pc/package-lock.json` | 顶部版本号同步 0.2.0（依赖版本不变） |
| `pc/resources/赛事中心.html` | 桌面壳内嵌页面副本，0.2.0 |
| `mobile/android/app/build.gradle` | versionCode 2、versionName "0.2.0" |
| `mobile/android/app/src/main/assets/index.html` | 安卓壳内嵌页面副本，0.2.0 |
| `docs/CHANGELOG.md` | 新增 `[0.2.0]` 一节，0.1.0 一节保留为历史 |
| `docs/architecture.md`、`docs/github-upload.md`、`README.md`、`pc/README.md`、`proxy/README.md` | 版本标注与聚合端点说明同步 |

> 本次**没有删除**任何 0.1.0 源码文件；若后续版本有废弃文件，网页上传不会自动删除，需进对应目录点文件右侧 **… → Delete file** 再提交。

### 打包产物（挂 v0.2.0 Release 附件，不进仓库源码）

| 文件 | 大小 | 用途 |
| --- | --- | --- |
| `releases/pc/SportsHub Setup 0.2.0.exe` | ~75.2 MB | NSIS 安装包（可选目录） |
| `releases/pc/SportsHub 0.2.0.exe` | ~74.9 MB | 单文件便携版 |
| `releases/pc/SportsHub-0.2.0-pc.zip` | ~109.1 MB | 绿色免安装压缩包（体积偏大，网页传不动时用命令行补传） |
| `releases/mobile/SportsHub-0.2.0-mobile.apk` | ~1.24 MB | 安卓安装包（debug 签名，versionCode 2） |

> `releases/pc/win-unpacked/` 是解包目录，不要上传。

## 1. 网页更新仓库源码

### 方式 A（推荐）：整目录上传覆盖

1. 浏览器打开你的仓库主页。
2. 点 **Add file** → **Upload files**。
3. 打开本地 `github-upload/SportsHub/` 文件夹，**把里面的内容拖到网页上传区**（拖 `web`、`pc`、`mobile`、`proxy`、`docs`、`README.md`、`.gitignore` 这些，而不是把外层 `SportsHub` 文件夹整个套进去；若套了一层也能跑，只是目录多一级）。
4. 网页解析文件列表后，**同名文件会显示为覆盖（modified）**，新文件显示为新增（added）。可按目录分批传，避免一次文件过多。
5. 拉到页面底部 **Commit changes**：
   - 标题填：`chore: 更新至 v0.2.0（赛程取数并发与缓存提速）`
   - 说明可留空，或填「0.2.0：代理聚合端点、缓存单键、并发与超时优化；三端源码与文档同步」。
   - 选 **Commit directly to the main branch**。
6. 点 **Commit changes**。

### 方式 B：逐文件在线改（改动少时用）

对单个文件：仓库内点开该文件 → 点右上角铅笔（**Edit this file**）→ 粘贴本地 0.2.0 内容 → 底部提交。适合只改一两个文件；本次改动面较大，建议用方式 A。

## 2. 核对仓库内容

提交后逐项确认：

- 仓库根有 `README.md`、`.gitignore`；打开 README 顶部显示 `v0.2.0`。
- `web/赛事中心.html`、`proxy/server.js`、`pc/`、`mobile/android/`、`docs/` 都在。
- 打开 `pc/package.json` 看到 `"version": "0.2.0"`；打开 `mobile/android/app/build.gradle` 看到 `versionCode 2`、`versionName "0.2.0"`。
- **没有** `node_modules/`、`build/`、`.gradle/`、`local.properties`、`*.exe/*.zip/*.apk`（这些已在本包剔除；若误传，进对应目录点文件右侧 **…** → Delete file 删除后再提交）。

## 3. 新发 v0.2.0 Release 并上传二进制附件

> 不要编辑旧的 `v0.1.0` Release；0.1.0 的源码与附件作为历史版本保留。

1. 仓库主页右侧栏点 **Releases** → **Draft a new release**（或 **Create a new release**）。
2. **Choose a tag**：输入 `v0.2.0`，下拉选 **Create new tag: v0.2.0**（目标分支 `main`）。
3. **Release title**：`v0.2.0`。
4. **Describe this release**：粘贴 `docs/CHANGELOG.md` 中 `[0.2.0]` 一节内容（本次改动 / 复测口径 / 产物 / 已知遗留）。
5. **Attach binaries**：把四个 0.2.0 打包产物拖到附件区：
   - `SportsHub Setup 0.2.0.exe`
   - `SportsHub 0.2.0.exe`
   - `SportsHub-0.2.0-pc.zip`（若网页上传因体积被拒，见下方「常见问题」）
   - `SportsHub-0.2.0-mobile.apk`
6. 点 **Publish release**。发布后 `v0.2.0` 标签与附件即固定在该版本；仓库 Releases 列表会同时保留 `v0.1.0` 与 `v0.2.0`。

## 4. 验证下载后可用

他人（或你自己）从 `v0.2.0` Release 下载 ZIP / clone 后：

- **Web**：直接浏览器打开 `web/赛事中心.html`。
- **代理**（需代理的国内源）：装 Node 18+，`cd proxy && node server.js`，默认 `http://127.0.0.1:8787`。
- **PC**：`cd pc && npm install && npm start`；打包用 `npm run dist`。
- **安卓**：Android Studio 打开 `mobile/android/`，或命令行 `./gradlew assembleRelease` 出 APK。

## 5. 常见问题与限制

- **同名覆盖不生效？** 网页上传时同名文件必须保持仓库内相对路径一致（如 `web/赛事中心.html` 不能传到别的目录）；提交后点开文件确认顶部版本号已是 0.2.0。
- **网页上传不删除旧文件**：本次无删除项；后续若删了本地文件，需在仓库里手动 Delete file 再提交。
- **单文件大小**：网页上传源码建议单文件 < 25 MB；Release 附件单个可到较大体积，但网页拖拽超大文件可能失败。`SportsHub-0.2.0-pc.zip`（~109 MB）若传不动，可改用 GitHub CLI：`gh release upload v0.2.0 ./SportsHub-0.2.0-pc.zip`，或先只挂安装包 + 便携 exe + APK，绿色包稍后补。
- **依赖不入库**：`node_modules`、`.gradle`、`build` 都不进仓库；拉代码后 PC 需 `npm install`，安卓由 Gradle 自动下载依赖。
- **本机路径不入库**：`local.properties` 含本机 SDK 绝对路径，已剔除；安卓工程在本机首次打开时会自动生成。
- **密钥**：签名证书 `*.keystore/*.jks` 禁止入库；当前 APK 为 debug 签名，正式发布前换自有 release 密钥。
- **旧版本保留**：`v0.1.0` Release 与其附件不删除、不替换；用户仍可从历史 Release 下载旧版。

## 6. 版本号约定（后续版本沿用）

- 优化阶段**先不打包**，等明确下达打包命令后再出二进制。
- 版本号按更新体量定级：
  - 修订 / 小改动 → 第三位（patch）：如 `0.2.1`、`0.2.3`
  - 功能 / 联赛级更新 → 第二位（minor）：如 `0.3.0`、`0.4.0`
  - 整体体制 / 架构重构 → 第一位（major）：如 `1.0.0`
- 出新版本时重复第 1～3 节：覆盖源码 → 新建对应标签（如 `v0.3.0`）→ 更新 `docs/CHANGELOG.md` → 挂新产物附件。
