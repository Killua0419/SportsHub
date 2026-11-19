# GitHub 网页版上传完整步骤（v0.1.0）

全程在浏览器网页操作，不需要本机安装 Git。源码与打包二进制分开：**源码进仓库**，**exe/zip/apk 挂到 Release 附件**。

## 0. 准备清单

### 本地源码包（上传到仓库）

目录：`github-upload/SportsHub/`（本包已剔除依赖、构建产物与本机路径）

```
SportsHub/
├── README.md              # 项目说明（仓库门面）
├── .gitignore             # 忽略 node_modules / build / 二进制 / 密钥
├── web/赛事中心.html       # 单文件页面（权威副本）
├── proxy/server.js        # 本地代理（Node 零依赖）
├── pc/                    # Electron 桌面壳源码
├── mobile/android/        # 安卓 WebView 壳源码
└── docs/                  # 架构说明 / 更新日志 / 上传方案
```

### 打包产物（挂 Release 附件，不进仓库源码）

| 文件 | 大小 | 用途 |
| --- | --- | --- |
| `releases/pc/SportsHub Setup 0.1.0.exe` | ~75 MB | NSIS 安装包（可选目录） |
| `releases/pc/SportsHub 0.1.0.exe` | ~75 MB | 单文件便携版 |
| `releases/pc/SportsHub-0.1.0-pc.zip` | ~109 MB | 绿色免安装压缩包（体积偏大，网页传不动时用命令行补传） |
| `releases/mobile/SportsHub-0.1.0-mobile.apk` | ~1.2 MB | 安卓安装包（debug 签名） |

> `releases/pc/win-unpacked/` 是解包目录，不要上传。

## 1. 新建空仓库

1. 浏览器登录 GitHub，右上角 **+** → **New repository**。
2. 填写：
   - **Repository name**：建议 `SportsHub`（也可填 `sports-hub`）。
   - **Description**：可选，如「跨端体育赛事信息应用 v0.1.0」。
   - **Public / Private**：按需选择。
3. **下面三个选项全部不要勾**：
   - ☐ Add a README file
   - ☐ Add .gitignore
   - ☐ Choose a license
   （我们要上传自己的 README 与 .gitignore，勾选会产生冲突文件。）
4. 点 **Create repository**。进入后页面会显示 "Quick setup" 空仓库提示。

## 2. 网页上传源码

1. 在仓库主页点 **Add file** → **Upload files**。
2. 打开本地 `github-upload/SportsHub/` 文件夹，**把里面的内容拖到网页上传区**（拖 `web`、`pc`、`mobile`、`proxy`、`docs`、`README.md`、`.gitignore` 这些，而不是把外层 `SportsHub` 文件夹整个套进去；若套了一层也能跑，只是目录多一级）。
3. 等待网页解析出文件列表（本包 45 个文件、约 4.8 MB，可一次传完；文件多时可分目录分批传）。
4. 拉到页面底部 **Commit changes**：
   - 标题填：`chore: 初始提交 v0.1.0`
   - 说明可留空，或填「0.1.0 基线：Web / PC / 安卓三端源码与文档」。
   - 选 **Commit directly to the main branch**。
5. 点 **Commit changes**。回到仓库主页，确认目录结构与上面「准备清单」一致。

> 中文文件名（如 `赛事中心.html`）GitHub 正常支持，无需改名。

## 3. 核对仓库内容

上传后逐项确认：

- 仓库根有 `README.md`、`.gitignore`；打开 README 能正常显示。
- `web/赛事中心.html`、`proxy/server.js`、`pc/`、`mobile/android/`、`docs/` 都在。
- **没有** `node_modules/`、`build/`、`.gradle/`、`local.properties`、`*.exe/*.zip/*.apk`（这些已在本包剔除；若误传，进对应目录点文件右侧 **…** → Delete file 删除后再提交）。

## 4. 打 v0.1.0 Release 并上传二进制附件

1. 仓库主页右侧栏点 **Releases** → **Draft a new release**（或 **Create a new release**）。
2. **Choose a tag**：输入 `v0.1.0`，下拉选 **Create new tag: v0.1.0**（目标分支 `main`）。
3. **Release title**：`v0.1.0`。
4. **Describe this release**：粘贴 `docs/CHANGELOG.md` 中 `[0.1.0]` 一节内容（功能范围 / 数据源 / 产物 / 已知遗留）。
5. **Attach binaries**：把四个打包产物拖到附件区：
   - `SportsHub Setup 0.1.0.exe`
   - `SportsHub 0.1.0.exe`
   - `SportsHub-0.1.0-pc.zip`（若网页上传因体积被拒，见下方「常见问题」）
   - `SportsHub-0.1.0-mobile.apk`
6. 点 **Publish release**。发布后 `v0.1.0` 标签与附件即固定在该版本。

## 5. 验证下载后可用

他人（或你自己）从仓库下载 ZIP / clone 后：

- **Web**：直接浏览器打开 `web/赛事中心.html`。
- **代理**（需代理的国内源）：装 Node 18+，`cd proxy && node server.js`，默认 `http://127.0.0.1:8787`。
- **PC**：`cd pc && npm install && npm start`；打包用 `npm run dist`。
- **安卓**：Android Studio 打开 `mobile/android/`，或命令行 `./gradlew assembleRelease` 出 APK。

## 6. 常见问题与限制

- **单文件大小**：网页上传源码建议单文件 < 25 MB；Release 附件单个可到较大体积，但网页拖拽超大文件可能失败。`SportsHub-0.1.0-pc.zip`（~109 MB）若传不动，可改用 GitHub CLI：`gh release upload v0.1.0 ./SportsHub-0.1.0-pc.zip`，或只挂安装包 + 便携 exe + APK，绿色包稍后补。
- **依赖不入库**：`node_modules`、`.gradle`、`build` 都不进仓库；拉代码后 PC 需 `npm install`，安卓由 Gradle 自动下载依赖。
- **本机路径不入库**：`local.properties` 含本机 SDK 绝对路径，已剔除；安卓工程在本机首次打开时会自动生成。
- **密钥**：签名证书 `*.keystore/*.jks` 禁止入库；当前 APK 为 debug 签名，正式发布前换自有 release 密钥。
- **网页上传不创建本地 Git**：仓库历史由网页提交生成；后续若在本机用 Git 克隆、修改、再推回，按 `docs/github-upload.md` 的 Git 流程操作即可。

## 7. 后续版本约定

- 优化阶段**先不打包**，等明确下达打包命令后再出二进制。
- 版本号按更新体量定级：
  - 修订 / 小改动 → 第三位（patch）：如 `0.1.1`、`0.2.3`
  - 功能 / 联赛级更新 → 第二位（minor）：如 `0.2.0`、`0.3.0`
  - 整体体制 / 架构重构 → 第一位（major）：如 `1.1.0`
- 出新版本时重复第 4 节：新建对应标签（如 `v0.2.0`），更新 `docs/CHANGELOG.md`，挂新产物附件。
