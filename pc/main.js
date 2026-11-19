const { app, BrowserWindow } = require('electron');
const path = require('path');
let proxyServer = null;

/* 内嵌本地代理：代为请求不开放 CORS 的国内公开源；端口被外部代理占用时静默退出，不影响界面 */
function startEmbeddedProxy() {
  try {
    const proxy = require('./proxy');
    proxyServer = proxy.startProxy(8787);
    proxyServer.on('error', (err) => {
      if (err && err.code === 'EADDRINUSE') {
        console.log('[sports-hub] 本地代理端口 8787 已被占用，沿用现有代理服务');
        proxyServer = null;
      } else {
        console.error('[sports-hub] 代理启动异常:', err && err.message || err);
      }
    });
  } catch (e) {
    console.error('[sports-hub] 内嵌代理不可用:', e && e.message || e);
  }
}

function createWindow () {
  const win = new BrowserWindow({
    width: 1280,
    height: 860,
    minWidth: 960,
    minHeight: 640,
    backgroundColor: '#1A1440',
    autoHideMenuBar: true,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });
  /* 公开数据源边缘会拦截带 Electron 标识的 UA，改为普通 Chrome UA */
  win.webContents.setUserAgent('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.234 Safari/537.36');
  win.loadFile(path.join(__dirname, 'resources', '赛事中心.html'));
}

app.whenReady().then(() => {
  startEmbeddedProxy();
  createWindow();
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (proxyServer) { try { proxyServer.close(); } catch (e) {} }
  if (process.platform !== 'darwin') app.quit();
});
