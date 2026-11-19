# PC 端（Electron 桌面壳）

## 结构

- `main.js`：创建桌面窗口，加载 `resources/赛事中心.html`；启动时在主进程内嵌拉起本地代理。
- `proxy.js`：本地代理脚本（与仓库根 `proxy/server.js` 同源），被主进程 `require` 后由 `startProxy(8787)` 监听；端口被占用时静默沿用现有代理。
- `resources/赛事中心.html`：界面与适配层（与 `web/` 同源副本）。
- `assets/icon.ico`：多尺寸应用图标（16/24/32/48/64/128/256），由 `icon-source.png` 经 `make_icon.py` 生成。
- `package.json`：Electron 与 electron-builder 配置。

## 本地开发

```bash
npm install
npm start
```

## 打包

```bash
npm run dist
```

产物输出到 `../releases/pc/`，同时生成两种 Windows 包：

| 产物 | 形态 | 说明 |
| --- | --- | --- |
| `SportsHub Setup 0.1.0.exe` | NSIS 安装包 | 非一键向导，可自选安装目录；同 appId 再次安装即覆盖旧版本；创建桌面与开始菜单快捷方式（名称「赛事中心」）。 |
| `SportsHub 0.1.0.exe` | 便携版 | 单文件绿色版，解压/放置任意目录直接运行，不写安装目录。 |

## 安装与覆盖安装说明

- 安装包为当前用户安装（`perMachine: false`），无需管理员权限即可装到用户目录。
- 重新运行同版本或更高版本安装包，默认覆盖安装到原目录，保留用户数据（卸载不清除应用数据）。
- 若需改安装目录，在安装向导的目录选择步骤切换即可。

## 说明

- 数据来自公开多源（ESPN 直连 + 本地代理转发）；未接入项目走空态。
- 图标已接入；暂未做代码签名，安装时 Windows 可能提示未知发布者，正式发布前需替换自有签名证书。
