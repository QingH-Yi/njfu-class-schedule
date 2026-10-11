/* 教务窗口的 preload：把页面里"抓取"按钮的 DOM 事件转发给主进程。
 * 虽然 contextIsolation 隔离了 JS 上下文，但两个 world 共享同一个 DOM，
 * 所以可以在 isolated world 监听 main world 派发的 CustomEvent。 */
const { ipcRenderer } = require('electron');

document.addEventListener('DOMContentLoaded', attach);
if (document.readyState !== 'loading') attach();

let bound = false;
function attach() {
  if (bound) return;
  bound = true;
  document.addEventListener('njfu-grab', function () {
    ipcRenderer.send('jwgl-scrape-trigger');
  });
}
