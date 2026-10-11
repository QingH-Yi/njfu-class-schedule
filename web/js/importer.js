/* 导入模块：生成书签导入用的 Bookmarklet，并把它渲染成可拖拽的链接 */
(function () {
  'use strict';

  /*
   * 书签小工具（Bookmarklet）运行在【教务系统】自己的页面里，
   * 所以它能读到课表格子（不受跨域限制）。它做三件事：
   *  1) 找到课表所在表格（优先），找不到就用整页
   *  2) 把该表格的 HTML 发回给"打开它的那个网页"（opener），用 postMessage
   *  3) 关闭教务系统这个标签页，回到我们的网页
   *
   * 用整页 outerHTML 而不是直接传 JSON，是为了把解析逻辑留在我们这边
   * （复用 parser.js），书签本身做到最小、最稳。
   */
  var BOOKMARKLET_SRC = '(' + function () {
    try {
      // 在分屏 iframe 里 -> 回传给父页面；在独立窗口 -> 回传给 opener
      var target = (window.parent && window.parent !== window) ? window.parent : window.opener;
      if (!target) { alert('请通过课程表网页打开教务系统后再点本按钮。'); return; }

      // 优先挑"看起来像课表"的表格
      var tables = document.querySelectorAll('table');
      var best = null, bestLen = -1;
      for (var i = 0; i < tables.length; i++) {
        var txt = tables[i].innerText || '';
        var hasDay = /周[一二三四五六日天]|星期[一二三四五六日天]/.test(txt);
        var len = txt.length + (hasDay ? 5000 : 0);
        if (len > bestLen) { bestLen = len; best = tables[i]; }
      }
      var payload = {
        source: 'njfu-schedule-import',
        url: location.href,
        html: document.documentElement.outerHTML,
        tableHtml: best ? best.outerHTML : ''
      };
      target.postMessage(payload, '*');
      document.title = '✔ 已回传课表，正在抓学分…';

      // 接着顺序抓三轮「个人中心课表」（悬停浮层 div.item-box 里带学分），
      // 三轮在网页端比对一致才采信（与 App 端同一约定）。
      var done = function () {
        if (!(window.parent && window.parent !== window)) {
          setTimeout(function () { try { window.close(); } catch (e) {} }, 300);
        }
      };
      var f = function (u) {
        return fetch(u, { credentials: 'include' })
          .then(function (r) { return r.text(); })
          .catch(function () { return ''; });
      };
      var one = function () {
        return f('/jsxsd/framework/xsMainV_new.htmlx?t1=1').then(function (t) {
          var m = t.match(/class="layui-this"[^>]*data-value="([0-9A-Fa-f]{32})"/) ||
                  t.match(/data-value="([0-9A-Fa-f]{32})"[^>]*class="layui-this"/) ||
                  t.match(/data-value="([0-9A-Fa-f]{32})"/);
          if (!m) return '';
          var tm = ((document.body.innerText || '').match(/\d{4}-\d{4}-[12]/) || [''])[0];
          var q = 'rq=all&sjmsValue=' + m[1] + (tm ? '&xnxqid=' + encodeURIComponent(tm) : '') + '&xswk=false';
          return f('/jsxsd/framework/mainV_index_loadkb.htmlx?' + q);
        });
      };
      var rounds = [], n = 0;
      var next = function () {
        if (n >= 3) {
          try { target.postMessage({ source: 'njfu-schedule-import', type: 'credits', rounds: rounds }, '*'); } catch (e) {}
          done();
          return;
        }
        n++;
        one().then(function (h) { rounds.push(h || ''); next(); });
      };
      next();
    } catch (err) {
      alert('导入出错：' + err.message);
    }
  }.toString() + ')();';

  function bookmarkletHref() {
    return 'javascript:' + encodeURIComponent(BOOKMARKLET_SRC);
  }

  function render() {
    var href = bookmarkletHref();
    // 所有带 .js-bookmarklet 的按钮都设为书签链接（分屏抽屉 + 旧弹窗共用）
    Array.prototype.forEach.call(document.querySelectorAll('.js-bookmarklet'), function (a) {
      a.setAttribute('href', href);
    });
  }

  document.addEventListener('DOMContentLoaded', render);
})();
