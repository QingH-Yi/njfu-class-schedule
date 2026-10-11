/* 主窗口 preload：把 Electron 能力安全地暴露给网页版（window.njfu） */
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('njfu', {
  // 是否运行在 EXE 里（网页版据此切换成"一键导入"）
  isDesktop: true,
  // 账号密码（仅存本地 userData/config.json）
  getConfig: () => ipcRenderer.invoke('cfg:get'),
  setConfig: (cfg) => ipcRenderer.invoke('cfg:set', cfg),
  // 打开内置教务窗口
  openJwgl: () => ipcRenderer.invoke('jwgl:open'),
  // 抓取完成后，网页版监听这个事件拿到 HTML
  onScraped: (cb) => ipcRenderer.on('jwgl-scraped', (e, data) => cb(data)),
  onError: (cb) => ipcRenderer.on('jwgl-error', (e, msg) => cb(msg)),
  openExternal: (url) => ipcRenderer.invoke('app:openExternal', url)
});
