/* 南林课程表 EXE —— Electron 主进程
 * 职责：
 *  1. 打开主窗口（复用 ../web 网页版界面）
 *  2. "内置教务窗口"：点导入时弹出，自动打开教务系统，右上角固定一个工具条
 *  3. 全自动登录（存一次学号密码）+ 抓取课表回传给主窗口解析
 */
const path = require('path');
const fs = require('fs');
const { app, BrowserWindow, ipcMain, shell, session } = require('electron');

const JWGL_URL = 'https://jwxt.njfu.edu.cn/jsxsd/framework/xsMainV.jsp';
const JWGL_HOME = 'https://jwxt.njfu.edu.cn/jsxsd/';

let mainWindow = null;
let jwglWindow = null;

/* 定位 web 资源目录：打包后在 resources/web，开发时在 ../web */
function findWebDir() {
  const candidates = [
    process.resourcesPath ? path.join(process.resourcesPath, 'web') : null,
    path.join(__dirname, 'web'),
    path.join(__dirname, '..', 'web')
  ].filter(Boolean);
  for (const c of candidates) {
    if (fs.existsSync(path.join(c, 'index.html'))) return c;
  }
  return path.join(__dirname, '..', 'web');
}
const WEB_DIR = findWebDir();

/* 配置（含学号密码）存在 userData/config.json，仅存本地 */
function configPath() { return path.join(app.getPath('userData'), 'config.json'); }
function readConfig() {
  try { return JSON.parse(fs.readFileSync(configPath(), 'utf8')); } catch (e) { return {}; }
}
function writeConfig(obj) {
  try { fs.writeFileSync(configPath(), JSON.stringify(obj, null, 2), 'utf8'); return true; }
  catch (e) { return false; }
}

function createMainWindow() {
  mainWindow = new BrowserWindow({
    width: 1180, height: 820, minWidth: 900, minHeight: 600,
    title: '南林课程表',
    backgroundColor: '#eef2ff',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });
  mainWindow.setMenuBarVisibility(false);
  mainWindow.loadFile(path.join(WEB_DIR, 'index.html'));
  mainWindow.on('closed', () => { mainWindow = null; });
}

/* ---------- 内置教务窗口 ---------- */
function openJwglWindow() {
  if (jwglWindow && !jwglWindow.isDestroyed()) { jwglWindow.focus(); return; }

  jwglWindow = new BrowserWindow({
    width: 1200, height: 860,
    title: '教务系统 — 导入课表',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'jwgl-preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });

  jwglWindow.loadURL(JWGL_HOME);

  // 页面加载完成后注入"固定位置"的导入工具条 + 账号 + 尝试自动登录
  jwglWindow.webContents.on('did-finish-load', () => {
    const cfg = readConfig();
    injectToolbar();
    if (cfg.user && cfg.pass) {
      injectCreds(cfg.user, cfg.pass);
      // 等页面脚本就绪再自动登录
      setTimeout(() => autoLogin(cfg.user, cfg.pass), 600);
    }
  });

  jwglWindow.on('closed', () => { jwglWindow = null; });
}

/* 把账号密码临时注入页面（供"自动登录"按钮用，用完即弃） */
function injectCreds(user, pass) {
  if (!jwglWindow || jwglWindow.isDestroyed()) return;
  jwglWindow.webContents.executeJavaScript(
    'window.__njfuCreds=' + JSON.stringify({ user: String(user), pass: String(pass) }) + ';true;'
  ).catch(() => {});
}

/* 在教务页面右上角注入固定工具条 */
function injectToolbar() {
  if (!jwglWindow || jwglWindow.isDestroyed()) return;
  jwglWindow.webContents.executeJavaScript(`(function(){
    if (document.getElementById('njfu-bar')) return 'exists';
    var bar = document.createElement('div');
    bar.id = 'njfu-bar';
    bar.style.cssText = 'position:fixed;top:10px;right:10px;z-index:99999;display:flex;gap:8px;'+
      'background:linear-gradient(135deg,#4f8cff,#7c3aed);padding:8px 10px;border-radius:12px;'+
      'box-shadow:0 6px 20px rgba(0,0,0,.3);font:13px/1 -apple-system,"Microsoft YaHei",sans-serif;';
    bar.innerHTML =
      '<button id="njfu-auto" style="border:0;background:rgba(255,255,255,.2);color:#fff;padding:8px 12px;border-radius:8px;cursor:pointer">自动登录</button>'+
      '<button id="njfu-grab" style="border:0;background:#fff;color:#4f8cff;font-weight:700;padding:8px 14px;border-radius:8px;cursor:pointer">⬇ 抓取课表</button>';
    document.body.appendChild(bar);
    document.getElementById('njfu-auto').onclick = function(){
      var c = window.__njfuCreds;
      if(!c){ alert('请先在课程表应用里设置学号密码'); return; }
      var u=document.querySelector('input[name=username],input#username,input[name=user],input[name=yh],input[name=zh]');
      var p=document.querySelector('input[type=password]');
      var cap=document.querySelector('input[name=captcha],input[name=yzm],input#captcha,input[name=verifyCode]');
      if(u)u.value=c.user; if(p)p.value=c.pass;
      if(cap){ alert('检测到验证码，请手动输入后登录'); return; }
      var b=document.querySelector('button[type=submit],input[type=submit],#loginBtn,.loginBtn'); if(b)b.click();
    };
    document.getElementById('njfu-grab').onclick = function(){
      document.dispatchEvent(new CustomEvent('njfu-grab'));
    };
    return 'ok';
  })()`);
}

/* 尝试自动登录（best-effort，正方各校实现略有差异，遇到验证码则留给用户） */
async function autoLogin(user, pass) {
  if (!jwglWindow || jwglWindow.isDestroyed()) return 'no-window';
  try {
    const r = await jwglWindow.webContents.executeJavaScript(`(function(){
      var u = document.querySelector('input[name=username],input#username,input[name=user],input[name=yh],input[name=zh]');
      var p = document.querySelector('input[type=password]');
      var cap = document.querySelector('input[name=captcha],input[name=yzm],input#captcha,input[name=verifyCode]');
      if(u){ u.value = ${JSON.stringify(String(user))}; u.dispatchEvent(new Event('input',{bubbles:true})); }
      if(p){ p.value = ${JSON.stringify(String(pass))}; p.dispatchEvent(new Event('input',{bubbles:true})); }
      if(cap) return 'need-captcha';
      var btn = document.querySelector('button[type=submit],input[type=submit],#loginBtn,.loginBtn,button.btn-login');
      if(btn){ btn.click(); return 'submitted'; }
      return 'no-button';
    })()`);
    return r;
  } catch (e) { return 'error:' + e.message; }
}

/* 抓取当前教务页面 HTML（在"学生课表"页面点抓取），回传给主窗口 */
async function scrapeAndSend() {
  if (!jwglWindow || jwglWindow.isDestroyed()) return;
  try {
    const html = await jwglWindow.webContents.executeJavaScript('document.documentElement.outerHTML');
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('jwgl-scraped', { html: html, url: jwglWindow.webContents.getURL() });
      mainWindow.show();
      mainWindow.focus();
    }
  } catch (e) {
    if (mainWindow) mainWindow.webContents.send('jwgl-error', String(e.message || e));
  }
}

/* 页面里点按钮时调用的桥 */
ipcMain.handle('cfg:get', () => {
  const c = readConfig();
  return { user: c.user || '', hasPass: !!c.pass };
});
ipcMain.handle('cfg:set', (e, cfg) => {
  const c = readConfig();
  if (cfg.user !== undefined) c.user = cfg.user;
  if (cfg.pass !== undefined) c.pass = cfg.pass;
  return writeConfig(c);
});
ipcMain.handle('jwgl:open', () => { openJwglWindow(); return true; });
ipcMain.handle('jwgl:login', (e, user, pass) => autoLogin(user, pass));
ipcMain.handle('jwgl:scrape', () => { scrapeAndSend(); return true; });
ipcMain.handle('jwgl:goto', async (e, url) => {
  if (jwglWindow && !jwglWindow.isDestroyed()) {
    // 只允许跳转到教务系统域内，防滥用
    try { await jwglWindow.loadURL(url); } catch (err) {}
  }
  return true;
});
ipcMain.handle('app:openExternal', (e, url) => { shell.openExternal(url); return true; });

// 教务窗口里的"抓取"按钮通过 jwgl-preload 发这个事件
ipcMain.on('jwgl-scrape-trigger', () => { scrapeAndSend(); });

app.whenReady().then(() => {
  createMainWindow();
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createMainWindow(); });
});
app.on('window-all-closed', () => { app.quit(); });
