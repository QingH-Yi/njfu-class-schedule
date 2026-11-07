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
      .replace(/<\/(div|p|tr|td|li|span|font|a|b|strong|i|em)>/gi, '\n')
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

  // 规范化教室：教学楼 + 教室 合成一个干净地点，去掉中间的破折号。
  // 在线课只留教室文本；教学楼为空/是"其他"只留教室；其余 "教学楼 教室"。
  // 与 JwxtParser.kt 的 normalizeLocation 同步。
  function normalizeLocation(building, room) {
    var b = (building || '').trim().replace(/^[【\[]|[】\]]$/g, '').trim();
    var r = (room || '').trim();
    if (!r) return b;
    if (r.indexOf('在线') >= 0) return r;
    // 教室文本统一去掉中间的破折号（"教五楼-50320" → "教五楼 50320"）；教学楼为空/是"其他"的分支也要走
    var rClean = r.replace(/-/g, ' ').replace(/　/g, ' ').replace(/\s+/g, ' ').trim();
    if (!b || b === '其他') return rClean;
    if (rClean.indexOf(b) >= 0 || rClean.charAt(0) === '教') return rClean;
    return b + ' ' + rClean;
  }

  // 按 <font title='...'> 精确解析一个课程块（可产出多门课）。
  // 一个块里可能塞多门课（如"动物与人类"+补课的 PLC）：无 title 的课名字体开启新课组，
  // 其后的 title 字体（教师/教室/周次）归该组——四位老师只归自己的课，不会并到同格另一门课。
  // 同名多组（同格的显示层+悬停浮层两套呈现）合并互补：教师/节次以浮层为准。
  // 与 JwxtParser.kt 的 parseCellByTitleMulti 保持同步（改一处必须同步另一处）。
  function parseCellByTitleMulti(html) {
    // <br> 转空格：与 Kotlin Jsoup text() 行为一致——名字跨行时"体育(5)<br/>(舞龙)"要拼成"体育(5) (舞龙)"
    var doc = new DOMParser().parseFromString('<div>' + html.replace(/<br\s*\/?>/gi, ' ') + '</div>', 'text/html');
    var fonts = doc.querySelectorAll('font');
    var hasTitle = false;
    for (var t0 = 0; t0 < fonts.length; t0++) if (fonts[t0].hasAttribute('title')) { hasTitle = true; break; }
    if (!hasTitle) return [];

    var groups = [], cur = null;
    for (var i = 0; i < fonts.length; i++) {
      var f = fonts[i];
      var title = (f.getAttribute('title') || '').trim();
      var text = (f.textContent || '').replace(/[　\s]+/g, ' ').trim();
      if (!text) continue;
      if (!f.hasAttribute('title')) {
        // 课名字体：第一段开新课；再出现 = 同块还有另一门课。
        // "(讲课:33)" 这类无 title 括号注释不算课名
        if (/^\(.*\)$/.test(text)) continue;
        if (!cur || cur.name) { cur = newCourse(); groups.push(cur); }
        if (!cur.name) cur.name = text;
        continue;
      }
      if (!cur) { cur = newCourse(); groups.push(cur); }
      if (title === '周次(节次)' || title === '周次') {
        var pl = parsePeriodList(text);
        if (pl && !cur.periodList) {
          cur.periodList = pl;
          var wk = parseWeeks(text.replace(/\[.*?\]/g, ' '));
          if (wk && !cur.weeks) cur.weeks = wk;
        } else if (isWeekLine(text) && !cur.weeks) {
          cur.weeks = parseWeeks(text);
        }
      } else if (title === '教室') { if (cur.rooms.indexOf(text) < 0) cur.rooms.push(text); }
      else if (title === '教学楼') { if (!cur.building) cur.building = text; }
      else if (title === '教师') { if (cur.teachers.indexOf(text) < 0) cur.teachers.push(text); }
    }

    // 同名组：同格的显示层+浮层是同一门课，字段互补；老师/周次/节次任一不同 = 真两门课
    var merged = [], byName = {};
    for (var g = 0; g < groups.length; g++) {
      var grp = groups[g];
      if (!grp.name) continue;
      var prev = byName[grp.name];
      if (!prev) { byName[grp.name] = grp; merged.push(grp); continue; }
      if (isTwoCourses(prev, grp)) { byName[grp.name + '#' + merged.length] = grp; merged.push(grp); continue; }
      if (!prev.teachers.length) prev.teachers = grp.teachers.slice();
      if (!prev.rooms.length) prev.rooms = grp.rooms.slice();
      if (!prev.building) prev.building = grp.building;
      if (!prev.weeks) prev.weeks = grp.weeks;
      if (!prev.periodList) prev.periodList = grp.periodList;
    }
    var out = [];
    for (var m = 0; m < merged.length; m++) {
      var c = merged[m];
      if (!c.name) continue;
      // 合并后重算字符串字段（按行回退路径的字段直接在字符串上，集合为空时保留原值）
      if (c.teachers.length) c.teacher = c.teachers.join(',');
      if (c.rooms.length || c.building) c.location = normalizeLocation(c.building, c.rooms.join('；'));
      if (c.periodList) { c.startPeriod = c.periodList[0]; c.endPeriod = c.periodList[c.periodList.length - 1]; }
      // 注意：teachers/rooms/periodList 集合字段保留——上层的 mergeLayerGroups 跨层合并还要用
      out.push(c);
    }
    return out;
  }

  function newCourse() {
    return { name: '', teacher: '', location: '', weeks: null, startPeriod: null, endPeriod: null,
             startTime: '', endTime: '', building: '', teachers: [], rooms: [], periodList: null };
  }

  /** 同名两组是否真是两门课（老师/周次/节次任一都齐全且不同） */
  function isTwoCourses(prev, grp) {
    var pT = (prev.teachers || []).join(','), cT = (grp.teachers || []).join(',');
    var pW = (prev.weeks || []).join(','), cW = (grp.weeks || []).join(',');
    var pP = prev.periodList, cP = grp.periodList;
    return (pT && cT && pT !== cT) || (pW && cW && pW !== cW) ||
           (pP && cP && (pP[0] !== cP[0] || pP[pP.length - 1] !== cP[cP.length - 1]));
  }

  /** 兼容入口：单课块（取第一组） */
  function parseCellByTitle(html) {
    var all = parseCellByTitleMulti(html);
    return all.length ? all[0] : null;
  }

  /* ---------- 单元格解析（兼容旧版 + 新版正方门户） ---------- */
  function parseCell(html) {
    var byTitle = parseCellByTitle(html);
    if (byTitle) return byTitle;
    return parseCellByLines(html, true)[0] || null;
  }

  /** 单块解析（可产出多门课）：先按 title 分组精确解析；解析不出且 allowFallback 才按行猜。
   *  与 JwxtParser.kt 的 parseCellBlocks 保持同步（改一处必须同步另一处）。 */
  function parseCellBlocks(html, allowFallback) {
    var byTitle = parseCellByTitleMulti(html);
    if (byTitle.length) return byTitle;
    return parseCellByLines(html, allowFallback);
  }

  /** 老回退：无 title 标注的页面按行猜字段（loadkb 预览等非标准页才走到）。
   *  allowFallback=false（标准课表格）时绝不猜：猜会把教室猜成老师（字段错位根因）。
   *  与 JwxtParser.kt 的 parseCellByLines 保持同步（改一处必须同步另一处）。 */
  function parseCellByLines(html, allowFallback) {
    if (!allowFallback) return [];
    var lines = innerText(html).filter(function (l) {
      // 强智格子里的"通知单编号/班级/备注/学分/群号/链接"管理元数据、横线分隔行、
      // "(讲课:16)"/"(舞龙)"等括号备注不是课程字段
      return !/^(通知单编号|班级|备注|学分|群号|链接)/.test(l) &&
             !/^[-—=_]{3,}/.test(l) &&
             !/^\(.*\)$/.test(l) &&
             // 个人中心课表页的卡片名会被服务端截断成 "电气控制与..."，丢弃这种残名
             !/[.．…]{2,}$/.test(l);
    });
    if (!lines.length) return [];

    var course = newCourse();
    var used = {};
    var periodList = null;

    for (var i = 0; i < lines.length; i++) {
      var l = lines[i];

      // 1) 节次 "01-02-03小节"（新版）——可能同一行也含周次，两者都要取
      var pl = parsePeriodList(l);
      if (pl && !periodList) {
        periodList = pl;
        if (course.startPeriod == null) { course.startPeriod = pl[0]; course.endPeriod = pl[pl.length - 1]; }
        // 该行可能同时带周次；先剥掉 "01~02~03小节" 再取周次，避免小节号混进周次。
        // 但只有该行确实含"周"时才取，否则纯节次行 "01~02~03节" 会被误当成周次 [1,2,3]
        if (/周/.test(l)) {
          var wkSame = parseWeeks(String(l).replace(/\d+(?:\s*[-~—–]\s*\d+)+\s*小节/g, ' '));
          if (wkSame && !course.weeks) course.weeks = wkSame;
        }
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
      if (p && /节/.test(l) && !used[i]) { if (course.startPeriod == null) { course.startPeriod = p.start; course.endPeriod = p.end; } used[i] = true; continue; }
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
        // 在线教学类文本是"地点"（放教室栏），绝不当老师
        if (line.indexOf('在线') >= 0) { if (!course.location) course.location = line; continue; }
        var looksTeacher = /(老师|教授|副教授|讲师|博士)/.test(line) || /^[一-龥·、,\s]{2,12}$/.test(line);
        if (!course.location && looksPlace && !looksTeacher) { course.location = normalizeLocation('', line); }
        // 地点样式的行永远不进老师栏（宁空勿错）
        else if (!course.teacher && looksTeacher && !looksPlace) { course.teacher = line; }
        else if (!course.location && !course.teacher && !looksPlace) { course.location = normalizeLocation('', line); }
        else if (course.location && !course.teacher && !looksPlace) { course.teacher = line; }
      }
    }
    if (!course.name) return [];
    // 与 Kotlin parseCellByLines 返回形状统一：[[course, [起节, 止节]]]（行路径的
    // periods 由网格行兜底时可能为 null）。此前返回 [course] 与 title 路径不一致，
    // 导致门户/个人中心课表（loadkb）解析在配对层全军覆没。
    return [[course, [course.startPeriod, course.endPeriod]]];
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
        cellInfo[rr + ',' + col] = { cell: el, rs: rs };
        /* 把 span 区域在网格里铺满（同一 Element 指向多格）。
           关键：表头若用 colspan 合并（如周六跨两列），必须让被覆盖列也拿到该 Element，
           否则 colDay 里那列为空 → 该列下的课整列丢失（"漏某天一两节课"的根因）。 */
        for (var dr = 0; dr < rs; dr++)
          for (var dc = 0; dc < cs; dc++) {
            var gr2 = rr + dr, gc2 = col + dc;
            if (gr2 < R && gc2 < C) { grid[gr2][gc2] = el; occupied[gr2 + ',' + gc2] = true; }
          }
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
        var td = info.cell;
        if (!td || (td.tagName || '').toUpperCase() !== 'TD') continue;
        var text = norm(td.innerText || td.textContent);
        if (!text || text.length < 1) continue;
        // 星期列优先取格子自带隐藏 input 的精确坐标；取不到再退回表头网格推断。
        // 这条比网格推断可靠：表头 colspan 合并时不会串天（与 JwxtParser.kt 保持同步）。
        var day = cellDayFromInput(td) || colDay[dc2];
        if (!day) continue;
        // 一格多课：逐门解析，各自算节次/周次，分别入表（与 JwxtParser.kt 保持同步）
        var parsedList = parseCellMulti(td);
        for (var pi = 0; pi < parsedList.length; pi++) {
          var course = parsedList[pi];
          if (!course || !course.name) continue;
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
          // 去重键含结束节/周次/教师：既能去掉"同课跨大节重复格"，
          // 又不会把"同名同天同起始节但周次/教师不同"的两门课误合成一门
          var wk = (course.weeks || []).slice().sort(function (a, b) { return a - b; }).join(',');
          var key = course.name + '|' + course.day + '|' + course.startPeriod + '|' + course.endPeriod + '|' + wk + '|' + (course.teacher || '');
          if (seen[key]) continue;
          seen[key] = true;
          courses.push(course);
        }
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
        startPeriod: 1, endPeriod: 11, weeks: weeks2, startTime: '', endTime: '',
        // 区分实习/实验：名字带"实验"→实验（第11节下方一行）；否则当实习（周一到周五大卡片）
        kind: /实验/.test(name2) ? 'lab' : 'intern'
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

    // 门户/个人中心课表补全（与 JwxtParser.kt 的补全保持同步）：
    // ① 截断课名 → 用同页完整名补全；多候选时按教师签名消歧
    // ② 缺教师的课 → 用同页同名课的教师补全；③ 缺教室同理
    var needFix = false;
    for (var fi = 0; fi < courses.length; fi++) {
      var nm = courses[fi].name || '';
      if (/\.\.$/.test(nm) || /…$/.test(nm) || !(courses[fi].teacher) || !(courses[fi].location)) { needFix = true; break; }
    }
    if (needFix) {
      var fullNames = [];
      for (var fi2 = 0; fi2 < courses.length; fi2++) {
        var n2 = courses[fi2].name || '';
        if (!/\.\.$/.test(n2) && !/…$/.test(n2) && fullNames.indexOf(n2) < 0) fullNames.push(n2);
      }
      var teachersOf = {};
      for (var fi3 = 0; fi3 < fullNames.length; fi3++) {
        var set = {};
        for (var fi4 = 0; fi4 < courses.length; fi4++) {
          var cc4 = courses[fi4];
          if (cc4.name === fullNames[fi3] && cc4.teacher) set[cc4.teacher] = 1;
        }
        teachersOf[fullNames[fi3]] = Object.keys(set);
      }
      for (var fi5 = 0; fi5 < courses.length; fi5++) {
        var c5 = courses[fi5];
        var n5 = c5.name || '';
        if (/\.\.$/.test(n5) || /…$/.test(n5)) {
          var stem = n5.replace(/\.{2,}$/, '').replace(/…$/, '');
          var cands = [];
          for (var fi6 = 0; fi6 < fullNames.length; fi6++) {
            if (fullNames[fi6].indexOf(stem) === 0) cands.push(fullNames[fi6]);
          }
          if (cands.length > 1 && c5.teacher) {
            var mine = c5.teacher.split(',');
            var byT = [];
            for (var fi7 = 0; fi7 < cands.length; fi7++) {
              var tset = teachersOf[cands[fi7]] || [];
              for (var fi8 = 0; fi8 < tset.length; fi8++) {
                if (tset[fi8].split(',') === mine || tset[fi8].split(',').join('|') === mine.join('|')) { byT.push(cands[fi7]); break; }
              }
            }
            if (byT.length === 1) cands = byT;
          }
          if (cands.length === 1) c5.name = cands[0];
        }
        if (!(c5.teacher)) {
          for (var fi9 = 0; fi9 < courses.length; fi9++) {
            if (courses[fi9].name === c5.name && courses[fi9].teacher) { c5.teacher = courses[fi9].teacher; break; }
          }
        }
        if (!(c5.location)) {
          for (var fi10 = 0; fi10 < courses.length; fi10++) {
            if (courses[fi10].name === c5.name && courses[fi10].location) { c5.location = courses[fi10].location; break; }
          }
        }
      }
    }

    return { term: term, courses: courses };
  }

  /**
   * 解析整个 td，可能含多门课（正方/强智会把单周课+双周课、分组课等塞进同一格）。
   * 优先按 div.item-box（个人中心页，每门课一个）逐门解析，其次按 div.kbcontent1（标准课表页）逐门解析，
   * 都取不到则退回整格单课解析。与 JwxtParser.kt 的 parseCellMulti 保持同步（改一处必须同步另一处）。
   */
  function parseCellMulti(td) {
    // 门户/个人中心课表：span.box（可见卡：截断名+教师：X+小节周次）+ div.item-box
    //（浮层：完整课名/学分/节次/教室/周次，可含多门、内外层重复）。浮层按 <p> 分段逐门解析、
    // 签名去重；卡片教师按"截断名前缀+周次一致"匹配给条目；对不上条目的卡片直接建课
    //（截断名，页面级补全会用同页完整名替换）。卡片教师带".."是页面自身截断，不可信。
    // 标准页 div.kbcontent1（显示层）+ div.kbcontent（悬停完整层）按课名合并互补。
    // 与 JwxtParser.kt 的 parseCellMulti 保持同步（改一处必须同步另一处）。
    var hasTitles = td.querySelectorAll('font[title]').length > 0;
    var noGuess = !hasTitles;
    var allBoxes = td.querySelectorAll('span.box');
    var boxes = [];
    for (var bi = 0; bi < allBoxes.length; bi++) {
      if (allBoxes[bi].querySelectorAll('p').length > 0) boxes.push(allBoxes[bi]);
    }
    var itemBoxes = td.querySelectorAll('div.item-box');
    if (boxes.length > 0 && itemBoxes.length > 0) {
      var entries = {}, order = [];
      for (var a = 0; a < itemBoxes.length; a++) {
        var chunks = itemBoxes[a].innerHTML.split(/(?=<p[\s>])/);
        for (var ci = 0; ci < chunks.length; ci++) {
          if (chunks[ci].indexOf('</p>') < 0) continue;
          // parseCellBlocks 两种返回形状：title 路径=[[course,periods],...]，
          // 行路径=[course]（不带 periods，periods 由网格行补）——归一成 pair
          var pr = parseCellBlocks(chunks[ci], noGuess);
          if (!pr || !pr.length) continue;
          var first = pr[0];
          var course0 = (first && first.constructor === Array) ? first[0] : first;
          var periods0 = (first && first.constructor === Array) ? first[1] : [null, null];
          if (!course0 || !course0.name) continue;
          var sig = course0.name + '|' + periods0[0] + '|' + periods0[1] + '|' +
                    (course0.location || '') + '|' + (course0.weeks || []).join(',');
          if (!(sig in entries)) {
            // 注意：JS 侧 parseCellMulti 的契约是返回【课程对象数组】（节次缺省时
            // 由网格行 rowPeriod 兜底），与 Kotlin 的 pair 形状不同，别照抄。
            course0.startPeriod = periods0[0];
            course0.endPeriod = periods0[1];
            entries[sig] = course0;
            order.push(sig);
          }
        }
      }
      if (order.length) {
        var used = {};
        var tmRe = /^(?:教师|老师|任课)\s*[:：]\s*(.+)$/;
        var xjRe = /\d{1,2}(?:[~～\-—–]\d{1,2})*\s*小节/g;
        for (var bk = 0; bk < boxes.length; bk++) {
          var b = boxes[bk];
          var lines = [];
          var bps = b.querySelectorAll('p');
          for (var bi2 = 0; bi2 < bps.length; bi2++) {
            var ln = (bps[bi2].textContent || '').replace(/[　\s]+/g, ' ').trim();
            if (ln) lines.push(ln);
          }
          if (!lines.length) continue;
          var name = lines[0];
          var teachers = [];
          for (var li = 0; li < lines.length; li++) {
            var tm = lines[li].match(tmRe);
            if (tm) {
              var t = tm[1].trim();
              if (t && !/\.\.$/.test(t) && !/…$/.test(t)) teachers.push(t);
            }
          }
          var boxText = (function () {
            var st = b.querySelector('span.text');
            return (st ? st.textContent : b.textContent || '').replace(/[　\s]+/g, ' ').trim();
          })();
          var boxWeeks = parseWeeks(boxText.replace(xjRe, ' '));
          var stem = name.replace(/\.{2,}$/, '').replace(/…$/, '');
          var pool = [];
          for (var oi = 0; oi < order.length; oi++) {
            var e = entries[order[oi]];
            if (e.teacher || e.name.indexOf(stem) !== 0 || (used[order[oi]])) continue;
            pool.push(order[oi]);
          }
          var hit = null;
          for (var pi = 0; pi < pool.length; pi++) {
            if (boxWeeks.length && (entries[pool[pi]].weeks || []).join(',') === boxWeeks.join(',')) { hit = pool[pi]; break; }
          }
          if (!hit && !boxWeeks.length && pool.length === 1) hit = pool[0];
          if (hit && teachers.length) {
            entries[hit].teacher = teachers.join(',');
            used[hit] = 1;
          } else if (!hit) {
            var cc = newCourse();
            cc.name = name;
            if (teachers.length) cc.teacher = teachers.join(',');
            var pl = parsePeriodList(boxText.replace(/第[^节]{1,20}周/g, ' '));
            if (name && (pl || boxWeeks.length)) {
              cc.weeks = boxWeeks;
              if (pl) { cc.startPeriod = pl[0]; cc.endPeriod = pl[pl.length - 1]; }
              order.push('BOX|' + name);
              entries['BOX|' + name] = cc;
            }
          }
        }
        var result = [];
        for (var ri = 0; ri < order.length; ri++) result.push(entries[order[ri]]);
        return result;
      }
    }
    var itemBoxes2 = td.querySelectorAll('div.item-box');
    if (itemBoxes2.length >= 1) {
      var out = [];
      for (var a2 = 0; a2 < itemBoxes2.length; a2++) {
        out = out.concat(parseCellBlocks(itemBoxes2[a2].innerHTML, noGuess));
      }
      if (out.length) return mergeLayerGroups(out);
    }
    var layers = hasTitles ? td.querySelectorAll('div.kbcontent, div.kbcontent1') : [];
    if (layers.length >= 1) {
      var out2 = [];
      for (var b = 0; b < layers.length; b++) {
        out2 = out2.concat(parseCellBlocks(layers[b].innerHTML, noGuess));
      }
      if (out2.length) return mergeLayerGroups(out2);
    }
    return parseCellBlocks(td.innerHTML, noGuess);
  }

  /** 跨层同名合并（与 JwxtParser.kt 的 mergeLayerGroups 保持同步） */
  function mergeLayerGroups(all) {
    var merged = [], byName = {};
    for (var i = 0; i < all.length; i++) {
      var c = all[i];
      var prev = byName[c.name];
      if (!prev) { byName[c.name] = c; merged.push(c); continue; }
      if (isTwoCourses(prev, c)) { byName[c.name + '#' + merged.length] = c; merged.push(c); continue; }
      // 按行回退产出的组没有集合字段（undefined），同样要能并入浮层组的字段
      if ((!prev.teachers || !prev.teachers.length) && c.teachers) prev.teachers = c.teachers.slice();
      if ((!prev.rooms || !prev.rooms.length) && c.rooms) prev.rooms = c.rooms.slice();
      if (!prev.building && c.building) prev.building = c.building;
      if (!prev.weeks && c.weeks) prev.weeks = c.weeks;
      if (!prev.periodList && c.periodList) prev.periodList = c.periodList;
    }
    for (var m = 0; m < merged.length; m++) {
      var g = merged[m];
      if (g.teachers && g.teachers.length) g.teacher = g.teachers.join(',');
      if ((g.rooms && g.rooms.length) || g.building) g.location = normalizeLocation(g.building || '', (g.rooms || []).join('；'));
      if (g.periodList) { g.startPeriod = g.periodList[0]; g.endPeriod = g.periodList[g.periodList.length - 1]; }
      delete g.teachers; delete g.rooms; delete g.building; delete g.periodList;
    }
    return merged;
  }
  /**
   * 从格子里 jsxsd 的隐藏 input（name=jx0415zbdiv_<小节>，value="HASH-<星期列>-<组号>"）
   * 读出星期列。取不到返回 0，调用方退回表头网格推断。
   * 与 JwxtParser.kt 的 cellDayFromInput 保持同步（改一处必须同步另一处）。
   */
  function cellDayFromInput(td) {
    if (!td || !td.querySelectorAll) return 0;
    var inps = td.querySelectorAll('input[name^=jx0415zbdiv_]');
    for (var i = 0; i < inps.length; i++) {
      var v = (inps[i].getAttribute('value') || '').trim();
      var m = v.match(/-(\d+)-(\d+)$/);
      if (!m) continue;
      var d = parseInt(m[1], 10);
      if (d >= 1 && d <= 7) return d;
    }
    return 0;
  }
  /**
   * 从个人中心课表（framework/mainV_index_loadkb.htmlx）HTML 提取 课名→学分。
   * 该页悬停课程卡片显示的浮层就是 div.item-box，其中带「学分：X」。
   * 与 JwxtParser.kt 的 parseCredits 保持同步（改一处必须同步另一处）。
   */
  function parseCredits(root) {
    var doc = typeof root === 'string' ? new DOMParser().parseFromString(root, 'text/html') : root;
    var out = {};
    var creditRe = /学分[：:]\s*([0-9]+(?:\.[0-9]+)?)/;
    var boxes = doc.querySelectorAll('div.item-box');
    for (var i = 0; i < boxes.length; i++) {
      // 同一格塞两门课时，一个 item-box 里会有两组「<p>课名</p>…学分：X」——
      // 按文档顺序遍历，每个学分归它前面最近的课名（与 JwxtParser.kt 的 parseCredits 保持同步）
      var box = boxes[i];
      var name = '';
      var kids = box.querySelectorAll('p, span');
      for (var s = 0; s < kids.length; s++) {
        var el = kids[s];
        var txt = (el.textContent || '').trim();
        if (!txt) continue;
        if (el.tagName === 'P') { name = txt; continue; }
        var mt = creditRe.exec(txt);
        if (mt && name) { out[name] = mt[1]; name = ''; }
      }
    }
    return out;
  }

  var api = {
    parseCourseTable: parseCourseTable,
    parseCellByTitleMulti: parseCellByTitleMulti,
    parseCellBlocks: parseCellBlocks,
    mergeLayerGroups: mergeLayerGroups,
    parseCredits: parseCredits,
    parseWeeks: parseWeeks,
    parsePeriods: parsePeriods,
    parseRowPeriod: parseRowPeriod,
    parsePeriodList: parsePeriodList,
    parseCell: parseCell,
    parseCellMulti: parseCellMulti,
    cellDayFromInput: cellDayFromInput
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  global.NJFUScheduleParser = api;
})(typeof window !== 'undefined' ? window : this);

