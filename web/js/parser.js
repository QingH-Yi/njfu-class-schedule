/*
 * 正方教务系统 课表解析器
 * 既能解析粘贴进来的 HTML（app 内手动导入），
 * 也能被书签直接注入到教务系统页面里运行（自动抓取）。
 *
 * 输出结构:
 * {
 *   term: "2025-2026-1",
 *   courses: [
 *     { name, teacher, location, day(1-7), startPeriod, endPeriod,
 *       weeks:[1,2,...], startTime, endTime }
 *   ]
 * }
 */
(function (global) {
  'use strict';

  /* ---------- 工具 ---------- */
  function norm(s) {
    return (s || '').replace(/[\u00a0\u3000\s]+/g, ' ').trim();
  }

  // 去掉 HTML 标签，用换行保留块结构，便于逐行判断
  function innerText(html) {
    return (html || '')
      .replace(/<br\s*\/?>/gi, '\n')
      .replace(/<\/(div|p|tr|td|li)>/gi, '\n')
      .replace(/<[^>]+>/g, '')
      .replace(/&nbsp;/gi, ' ')
      .replace(/&amp;/gi, '&')
      .replace(/&lt;/gi, '<')
      .replace(/&gt;/gi, '>')
      .split('\n')
      .map(function (l) { return norm(l); })
      .filter(function (l) { return l.length; })
      // 教务格子常有重复内容（如强智 kbcontent1/kbcontent 双隐藏 div），
      // 去重可避免重复行顶掉真老师名、或把周次行漏进地点
      .filter(function (l, i, a) { return a.indexOf(l) === i; });
  }

  // 解析"周次"文本 -> [1,2,3...]
  function parseWeeks(text) {
    if (!text) return null;
    var out = {};
    var single = /单周/.test(text);
    var double = /双周/.test(text);

    // 形如 1-16, 18  或  1-8,10-12
    var re = /(\d+)\s*[-~—–]\s*(\d+)|(\d+)/g;
    var m;
    while ((m = re.exec(text)) !== null) {
      if (m[1] !== undefined) {
        var a = parseInt(m[1], 10), b = parseInt(m[2], 10);
        if (a > b) { var t = a; a = b; b = t; }
        for (var i = a; i <= b; i++) out[i] = true;
      } else {
        out[parseInt(m[3], 10)] = true;
      }
    }
    var weeks = Object.keys(out).map(Number).sort(function (x, y) { return x - y; });
    if (!weeks.length) return null;
    if (single) weeks = weeks.filter(function (w) { return w % 2 === 1; });
    if (double) weeks = weeks.filter(function (w) { return w % 2 === 0; });
    return weeks.length ? weeks : null;
  }

  // 解析节次文本 -> {start, end}
  function parsePeriods(text) {
    if (!text) return null;
    var m = text.match(/(\d+)\s*[-~—–]\s*(\d+)/);
    if (m) return { start: parseInt(m[1], 10), end: parseInt(m[2], 10) };
    m = text.match(/(\d+)/);
    if (m) { var n = parseInt(m[1], 10); return { start: n, end: n }; }
    return null;
  }

  /* 新版正方门户："第X大节" -> 节次区间 */
  var DAJIE = { '一': [1, 2], '二': [3, 4], '三': [5, 6], '四': [7, 8], '五': [9, 10], '六': [11, 12] };
  function parseRowPeriod(text) {
    if (!text) return null;
    var m = text.match(/第\s*([一二三四五六])\s*大节/);
    if (m && DAJIE[m[1]]) return { start: DAJIE[m[1]][0], end: DAJIE[m[1]][1] };
    // 中文数字小节 "第一二节"
    return parsePeriods(text);
  }

  /* 单元格里的 "01-02-03小节" -> [1,2,3] */
  function parsePeriodList(text) {
    if (!text) return null;
    var m = text.match(/([0-9][0-9\-~,，\s]*)\s*小\s*节/);
    if (!m) {
      // 兜底：整段里的 "01-02-03"（多个用-连接、后跟"节/小节/周"）
      m = text.match(/(\d{1,2}(?:\s*[-~—–]\s*\d{1,2}){1,3})\s*[大小]?节/);
    }
    if (!m) return null;
    var seg = m[1];
    var out = {};
    var re = /(\d+)\s*[-~—–]\s*(\d+)|(\d+)/g, mm;
    while ((mm = re.exec(seg)) !== null) {
      if (mm[1] !== undefined) {
        var a = +mm[1], b = +mm[2]; if (a > b) { var t = a; a = b; b = t; }
        for (var i = a; i <= b; i++) out[i] = true;
      } else out[+mm[3]] = true;
    }
    var list = Object.keys(out).map(Number).sort(function (x, y) { return x - y; });
    return list.length ? list : null;
  }

  // 从一行里识别时间 8:00-9:40
  function parseTime(text) {
    var m = (text || '').match(/(\d{1,2}):(\d{2})\s*[-~—–]\s*(\d{1,2}):(\d{2})/);
    if (!m) return null;
    return {
      startTime: (m[1].padStart(2, '0')) + ':' + m[2],
      endTime: (m[3].padStart(2, '0')) + ':' + m[4]
    };
  }

  // 判断一行是不是"周次"行
  function isWeekLine(l) { return /[0-9]/.test(l) && /周/.test(l) && !/上课|地点|老师|教师/.test(l); }

  /* ---------- 单元格解析（兼容旧版 + 新版正方门户） ---------- */
  function parseCell(html) {
    var lines = innerText(html).filter(function (l) {
      // 强智格子里的"通知单编号/班级/备注"管理元数据、横线分隔行、
      // "(讲课:16)"/"(舞龙)"等括号备注不是课程字段
      return !/^(通知单编号|班级|备注)/.test(l) &&
             !/^[-—=_]{3,}/.test(l) &&
             !/^\(.*\)$/.test(l);
    });
    if (!lines.length) return null;

    var course = { name: '', teacher: '', location: '', weeks: null, startPeriod: null, endPeriod: null, startTime: '', endTime: '' };
    var used = {};
    var periodList = null;

    for (var i = 0; i < lines.length; i++) {
      var l = lines[i];

      // 1) 节次 "01-02-03小节"（新版）——可能同一行也含周次，两者都要取
      var pl = parsePeriodList(l);
      if (pl && !periodList) {
        periodList = pl;
        var wkSame = parseWeeks(l);
        if (wkSame && !course.weeks) course.weeks = wkSame;
        used[i] = true; continue;
      }
      // 2) 周次："1-16周" / "(1-8,10周)" / "单周"
      // 周次行一律消费掉（首个设置周次，其余丢弃），防止漏进地点
      if (isWeekLine(l)) {
        if (!course.weeks) course.weeks = parseWeeks(l);
        used[i] = true; continue;
      }
      // 3) 时间 8:00-9:40
      var t = parseTime(l);
      if (t && !course.startTime) { course.startTime = t.startTime; course.endTime = t.endTime; used[i] = true; continue; }
      // 4) 传统 "第1-2节"
      var p = parsePeriods(l);
      if (p && /节/.test(l) && !used[i]) { course.startPeriod = p.start; course.endPeriod = p.end; used[i] = true; continue; }
      // 5) 教师："教师：戴斌" / "老师:张三"
      var tm = l.match(/^(?:教师|老师|任课)\s*[:：]\s*(.+)$/);
      if (tm && !course.teacher) { course.teacher = tm[1].trim(); used[i] = true; continue; }
    }

    // 用小节列表定节次
    if (periodList && course.startPeriod == null) {
      course.startPeriod = periodList[0];
      course.endPeriod = periodList[periodList.length - 1];
    }

    // 剩下的行: 第一行=课程名，之后判断老师 / 地点
    var rest = lines.filter(function (l, idx) { return !used[idx]; });
    if (rest.length) {
      course.name = rest[0];
      for (var j = 1; j < rest.length; j++) {
        var line = rest[j];
        var tm2 = line.match(/^(?:教师|老师|任课)\s*[:：]\s*(.+)$/);
        if (tm2) { if (!course.teacher) course.teacher = tm2[1].trim(); continue; }
        // 地点：含楼/教/实验/馆/室/校区 等，或形如 教9-101 / 实验楼A302
        var looksPlace = /(楼|教|实验|馆|室|区|号楼|楼\d|\d{2,4}\b)/.test(line) && !/@/.test(line);
        var looksTeacher = /(老师|教授|副教授|讲师|博士)/.test(line) || /^[一-龥·、,\s]{2,12}$/.test(line);
        if (!course.location && looksPlace && !looksTeacher) { course.location = line; }
        else if (!course.teacher && (looksTeacher || (!course.location && !looksPlace))) { course.teacher = course.teacher ? course.teacher + '、' + line : line; }
        else if (!course.location && !course.teacher) { course.teacher = line; }
      }
    }
    if (!course.name) return null;
    return course;
  }

  /* ---------- 星期表头识别 ---------- */
  function headerDay(text) {
    var s = norm(text);
    var map = { '一': 1, '二': 2, '三': 3, '四': 4, '五': 5, '六': 6, '日': 7, '天': 7 };
    var m = s.match(/周\s*([一二三四五六日天])/);
    if (m) return map[m[1]];
    m = s.match(/星期\s*([一二三四五六日天])/);
    if (m) return map[m[1]];
    m = s.match(/^([一二三四五六日天])$/);
    if (m) return map[m[1]];
    m = s.match(/\b(Mon|Tue|Wed|Thu|Fri|Sat|Sun)/i);
    if (m) {
      var en = { Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6, Sun: 7 };
      return en[m[1][0].toUpperCase() + m[1].slice(1, 3).toLowerCase()] || en[m[1].slice(0, 3).toLowerCase()];
    }
    return 0;
  }

  /* ---------- 主入口: 解析整个 doc ----------
   * root: Document 或元素
   */
  function parseCourseTable(root) {
    var doc = root && root.querySelectorAll ? root : document;
    var tables = doc.querySelectorAll('table');
    var best = null, bestScore = -1;

    for (var t = 0; t < tables.length; t++) {
      var tb = tables[t];
      var trs = tb.querySelectorAll('tr');
      var dayCount = 0, hasHeader = false, contentCells = 0;
      for (var r = 0; r < trs.length; r++) {
        var cells = trs[r].children;
        for (var c = 0; c < cells.length; c++) {
          var dd0 = headerDay(cells[c].textContent);
          if (dd0) { hasHeader = true; dayCount++; }
          if (norm(cells[c].innerText || cells[c].textContent).length > 1) contentCells++;
        }
      }
      if (!hasHeader) continue;
      var score = contentCells + dayCount;
      if (score > bestScore) { bestScore = score; best = tb; }
    }
    if (!best) return null;

    /* --- 网格填充：把带 rowspan/colspan 的表格铺成二维网格 --- */
    var trs = best.querySelectorAll('tr');
    var R = trs.length, C = 40;
    var grid = [];
    for (var gr = 0; gr < R; gr++) grid.push(new Array(C).fill(null));
    var occupied = {};
    var cellInfo = {}; // "r,c" -> {cell, rs}
    for (var rr = 0; rr < R; rr++) {
      var kids = trs[rr].children, col = 0;
      for (var ki = 0; ki < kids.length; ki++) {
        while (col < C && occupied[rr + ',' + col]) col++;
        if (col >= C) break;
        var el = kids[ki];
        var rs = parseInt(el.getAttribute('rowspan') || '1', 10) || 1;
        var cs = parseInt(el.getAttribute('colspan') || '1', 10) || 1;
        grid[rr][col] = el;
        cellInfo[rr + ',' + col] = { cell: el, rs: rs };
        for (var dr = 0; dr < rs; dr++)
          for (var dc = 0; dc < cs; dc++) occupied[(rr + dr) + ',' + (col + dc)] = true;
        col += cs;
      }
    }

    /* --- 找星期表头行，建立 列->星期 --- */
    var colDay = {}, headerRow = -1;
    for (var hr = 0; hr < R; hr++) {
      var found = 0;
      for (var hc = 0; hc < C; hc++) {
        var cellH = grid[hr][hc];
        if (!cellH) continue;
        var dh = headerDay(cellH.textContent);
        if (dh && colDay[hc] == null) { colDay[hc] = dh; found++; }
      }
      if (found >= 5) { headerRow = hr; break; }
    }
    if (headerRow < 0) return null;

    /* --- 计算每一行的默认节次（兼容"第X大节"和"第X-Y节"） --- */
    var rowPeriod = {};
    for (var pr = headerRow + 1; pr < R; pr++) {
      var first = grid[pr][0];
      var txt = first ? norm(first.innerText || first.textContent) : '';
      var pp = parseRowPeriod(txt);
      if (pp) rowPeriod[pr] = pp;
    }

    /* --- 遍历数据格 --- */
    var courses = [], seen = {};
    for (var dr2 = headerRow + 1; dr2 < R; dr2++) {
      for (var dc2 = 1; dc2 < C; dc2++) {
        var info = cellInfo[dr2 + ',' + dc2];
        if (!info) continue;                    // 该位置不是单元格左上角（被合并覆盖）
        var day = colDay[dc2];
        if (!day) continue;                      // 不是星期列
        var td = info.cell;
        if (!td || (td.tagName || '').toUpperCase() !== 'TD') continue;
        var text = norm(td.innerText || td.textContent);
        if (!text || text.length < 1) continue;

        var course = parseCell(td.innerHTML);
        if (!course) continue;
        course.day = day;

        // 节次：优先单元格自带，其次行首"第X节"，再次默认上延
        var rp = rowPeriod[dr2];
        if (course.startPeriod == null) {
          if (rp) { course.startPeriod = rp.start; course.endPeriod = rp.end; }
          else { course.startPeriod = 1; course.endPeriod = 1; }
        }
        if (info.rs >= 2) {
          // 跨行：结束节次 = 最后跨越行的节次
          var lastRow = dr2 + info.rs - 1;
          var rLast = rowPeriod[lastRow] || rowPeriod[dr2];
          if (rLast) course.endPeriod = rLast.end;
          else course.endPeriod = course.startPeriod + info.rs - 1;
        }
        if (course.endPeriod == null || course.endPeriod < course.startPeriod) course.endPeriod = course.startPeriod;

        var key = course.name + '|' + course.day + '|' + course.startPeriod;
        if (seen[key]) continue;
        seen[key] = true;
        courses.push(course);
      }
    }

    /* 备注行：整周实习/课程设计（如"专业实习 蒋小川,张颖 9周"），day=0 表示全周 */
    var remark = '';
    for (var rr = 0; rr < trs.length; rr++) {
      var kids2 = trs[rr].children;
      if (!kids2.length) continue;
      var firstTxt = norm(kids2[0].textContent);
      if (!/^备注/.test(firstTxt)) continue;
      for (var cc = 0; cc < kids2.length; cc++) {
        var t2 = norm(kids2[cc].textContent).replace(/^备注\s*[:：]?/, '');
        if (t2) remark += t2 + ' ';
      }
      break;
    }
    var entries = remark.split(/[;；]/);
    for (var ei = 0; ei < entries.length; ei++) {
      var parts = norm(entries[ei]).split(/\s+/).filter(function (x) { return x; });
      if (parts.length < 2) continue;
      var weeks2 = parseWeeks(parts[parts.length - 1]);
      if (!weeks2) continue;
      var tTok = parts[parts.length - 2];
      var teachers2 = /^[一-龥]+([,，、][一-龥]+)*$/.test(tTok) ? tTok : '';
      var name2 = (teachers2 ? parts.slice(0, parts.length - 2) : parts.slice(0, parts.length - 1)).join(' ');
      if (!name2) continue;
      var key2 = name2 + '|0|1';
      if (seen[key2]) continue;
      seen[key2] = true;
      courses.push({
        name: name2, teacher: teachers2, location: '', day: 0,
        startPeriod: 1, endPeriod: 11, weeks: weeks2, startTime: '', endTime: ''
      });
    }

    // 从页面上尽力提取学期名
    var term = '';
    var bodyText = (doc.body ? doc.body.innerText : '') || '';
    var tm = bodyText.match(/\d{4}\s*[-—]\s*\d{4}\s*学年\s*第?\s*[12]\s*学期?/);
    if (tm) term = norm(tm[0]);
    if (!term) {
      var opts = doc.querySelectorAll('select option:checked');
      for (var o = 0; o < opts.length; o++) {
        var ot = norm(opts[o].textContent);
        // 南林强智新版：选项文本形如 "2026-2027-1"（与 JwxtParser.kt 保持同步）
        if (/\d{4}/.test(ot) && (/学期/.test(ot) || /^\d{4}-\d{4}-[12]$/.test(ot))) { term = ot; break; }
      }
    }

    return { term: term, courses: courses };
  }

  /**
   * 从个人中心课表（framework/mainV_index_loadkb.htmlx）HTML 提取 课名→学分。
   * 该页悬停课程卡片显示的浮层就是 div.item-box，其中带「学分：X」。
   * 与 JwxtParser.kt 的 parseCredits 保持同步（改一处必须同步另一处）。
   */
  function parseCredits(root) {
    var doc = typeof root === 'string' ? new DOMParser().parseFromString(root, 'text/html') : root;
    var out = {};
    var m;
    var creditRe = /学分[：:]\s*([0-9]+(?:\.[0-9]+)?)/;
    var boxes = doc.querySelectorAll('div.item-box');
    for (var i = 0; i < boxes.length; i++) {
      var box = boxes[i];
      var p = box.querySelector('p');
      var name = p ? p.textContent.trim() : '';
      if (!name) continue;
      var spans = box.querySelectorAll('span');
      for (var s = 0; s < spans.length; s++) {
        var mt = creditRe.exec(spans[s].textContent || '');
        if (mt) { out[name] = mt[1]; break; }
      }
    }
    return out;
  }

  var api = {
    parseCourseTable: parseCourseTable,
    parseCredits: parseCredits,
    parseWeeks: parseWeeks,
    parsePeriods: parsePeriods,
    parseRowPeriod: parseRowPeriod,
    parsePeriodList: parsePeriodList,
    parseCell: parseCell,
    norm: norm
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  global.NJFUScheduleParser = api;
})(typeof window !== 'undefined' ? window : this);
