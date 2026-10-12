/* 课程表 Web App —— 主逻辑：状态、存储、课表格子渲染、增删改 */
(function () {
  'use strict';

  var STORAGE_KEY = 'njfu-schedule-v1';
  var PARSER = window.NJFUScheduleParser;

  /* ===== 南林定制：2026-2027学年第一学期校历 ===== */
  var NJFU_CALENDAR = {
    term: '2026-2027-1',
    start: '2026-08-31',          // 第一周周一（8月31日正式上课）
    weeks: 20,                    // 理论教学周
    winter: '2027-01-18 ~ 2027-02-21',
    notes: [
      '全校学生 8月29、30日注册报到，8月31日正式上课。',
      '每周星期三下午第 7-8 节课学校集中安排活动时段。',
      '国庆节、中秋节、元旦放假见校办通知。',
      '寒假：2027年元月18日 ～ 2月21日（共5周）。'
    ]
  };

  /* ===== 三校区作息时间表 ===== */
  var CAMPUS_TIMES = {
    xz: {
      label: '新庄校区(本部)',
      times: {
        1: ['08:00', '08:45'], 2: ['08:55', '09:40'], 3: ['10:00', '10:45'], 4: ['10:55', '11:40'],
        5: ['14:00', '14:45'], 6: ['14:50', '15:35'], 7: ['15:55', '16:40'], 8: ['16:45', '17:30'],
        9: ['18:30', '19:15'], 10: ['19:20', '20:05'], 11: ['20:10', '20:55']
      }
    },
    ha: {
      label: '淮安校区',
      times: {
        1: ['08:00', '08:45'], 2: ['08:55', '09:40'], 3: ['10:00', '10:45'], 4: ['10:55', '11:40'],
        5: ['14:00', '14:45'], 6: ['14:50', '15:35'], 7: ['15:55', '16:40'], 8: ['16:45', '17:30'],
        9: ['18:30', '19:15'], 10: ['19:20', '20:05'], 11: ['20:10', '20:55']
      }
    },
    bm: {
      label: '白马校区',
      times: {
        1: ['08:30', '09:15'], 2: ['09:20', '10:05'], 3: ['10:25', '11:10'], 4: ['11:15', '12:00'],
        5: ['14:00', '14:45'], 6: ['14:50', '15:35'], 7: ['15:55', '16:40'], 8: ['16:45', '17:30'],
        9: ['18:30', '19:15'], 10: ['19:20', '20:05'], 11: ['20:10', '20:55']
      }
    }
  };
  var MAX_PERIOD = 11;

  function getTimes() {
    var key = (state && state.meta && state.meta.campus) || 'xz';
    return (CAMPUS_TIMES[key] || CAMPUS_TIMES.xz).times;
  }

  var DAY_NAMES = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
  var PALETTE = ['#4f8cff', '#ff7a59', '#22c55e', '#a855f7', '#f59e0b',
    '#06b6d4', '#ec4899', '#8b5cf6', '#10b981', '#ef4444', '#3b82f6', '#eab308'];

  /* ---------- 节假日 / 调休 ----------
   * kind: 'off' = 放假不上课 ; 'make' = 调休补课(补星期几的课)
   * date : 'YYYY-MM-DD'
   */
  var DEFAULT_HOLIDAYS = [
    { date: '2026-10-01', kind: 'off', name: '国庆节' },
    { date: '2026-10-02', kind: 'off', name: '国庆节' },
    { date: '2026-10-03', kind: 'off', name: '国庆节/中秋' },
    { date: '2026-10-04', kind: 'off', name: '国庆节/中秋' },
    { date: '2026-10-05', kind: 'off', name: '国庆节/中秋' },
    { date: '2026-10-06', kind: 'off', name: '国庆节' },
    { date: '2026-10-07', kind: 'off', name: '国庆节' },
    { date: '2026-09-25', kind: 'off', name: '中秋节' },
    { date: '2027-01-01', kind: 'off', name: '元旦' }
  ];

  function holidayOn(dateStr) {
    var list = (state.meta && state.meta.holidays) || [];
    for (var i = 0; i < list.length; i++) if (list[i].date === dateStr) return list[i];
    return null;
  }
  // 某周的周一日期
  function mondayOf(week) {
    var start = parseDate(state.meta.termStartMonday || NJFU_CALENDAR.start);
    if (!start) return null;
    var d = new Date(start.getTime());
    d.setDate(d.getDate() + (week - 1) * 7);
    return d;
  }
  function fmt(d) { return d ? (d.getMonth() + 1) + '/' + d.getDate() : ''; }
  function ymd(d) {
    if (!d) return '';
    var m = ('0' + (d.getMonth() + 1)).slice(-2), dd = ('0' + d.getDate()).slice(-2);
    return d.getFullYear() + '-' + m + '-' + dd;
  }
  // 今天属于第几周；再算"今天"这一周里每天的日期，判断是否放假
  function weekDates(week) {
    var mon = mondayOf(week); if (!mon) return null;
    var arr = [];
    for (var i = 0; i < 7; i++) { var d = new Date(mon.getTime()); d.setDate(d.getDate() + i); arr.push(d); }
    return arr;
  }

  /* ---------- 状态 ---------- */
  var state = load();

  function defaults() {
    return {
      meta: {
        term: NJFU_CALENDAR.term,
        termStartMonday: NJFU_CALENDAR.start,
        campus: 'xz',
        weekOverride: null,
        holidays: DEFAULT_HOLIDAYS.slice()
      },
      courses: []
    };
  }
  function load() {
    try {
      var raw = localStorage.getItem(STORAGE_KEY);
      var d = defaults();
      if (!raw) return d;
      var s = JSON.parse(raw);
      s.meta = Object.assign(d.meta, s.meta || {});
      if (!s.meta.holidays || !s.meta.holidays.length) s.meta.holidays = d.meta.holidays;
      s.courses = s.courses || [];
      return s;
    } catch (e) { return defaults(); }
  }
  function save() {
    state.meta.updatedAt = new Date().toISOString();
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  }
  function uid() { return 'c' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6); }

  /* ---------- 周次计算 ---------- */
  function parseDate(str) {
    if (!str) return null;
    var p = str.split('-');
    return new Date(+p[0], +p[1] - 1, +p[2]);
  }
  function currentWeek() {
    if (state.meta.weekOverride) return +state.meta.weekOverride;
    var start = parseDate(state.meta.termStartMonday);
    if (!start) return null;
    var today = new Date(); today.setHours(0, 0, 0, 0);
    var diff = Math.floor((today - start) / 86400000);
    if (diff < 0) return 1;
    return Math.floor(diff / 7) + 1;
  }

  /* ---------- 渲染：课表格子 ---------- */
  function render() {
    renderHeader();
    renderGrid();
  }

  function renderHeader() {
    var wk = currentWeek();
    var el = document.getElementById('weekInfo');
    var camp = CAMPUS_TIMES[state.meta.campus || 'xz'].label;
    if (wk == null) {
      el.innerHTML = '尚未设置学期第一周 · ' + escapeHtml(camp);
    } else {
      var wd = weekDates(wk);
      var rangeTxt = wd ? '（' + fmt(wd[0]) + ' – ' + fmt(wd[6]) + '）' : '';
      el.innerHTML = '第 <b>' + wk + '</b> 周' + rangeTxt +
        ' · ' + escapeHtml(state.meta.term || '') + ' · ' + escapeHtml(camp);
    }
    var sel = document.getElementById('weekSel');
    sel.value = wk == null ? '' : wk;
  }

  // 判断当前显示周的某天(1-7)是否放假；返回 null 或 holiday 对象
  function offToday(day, wk) {
    var wd = weekDates(wk);
    if (!wd) return null;
    return holidayOn(ymd(wd[day - 1]));
  }

  function renderGrid() {
    var wrap = document.getElementById('grid');
    var filterWeek = document.getElementById('weekSel').value;
    var wk = filterWeek ? +filterWeek : currentWeek();
    var times = getTimes();

    // 决定显示多少节（至少到用到的最大节次）
    var maxP = 11;
    state.courses.forEach(function (c) { if (c.endPeriod > maxP) maxP = Math.min(c.endPeriod, MAX_PERIOD); });

    // 卡片映射
    var occupied = {};
    var cardsByCell = {};
    state.courses.forEach(function (c) {
      if (!c.weeks || !c.weeks.length || !wk || c.weeks.indexOf(wk) >= 0) {
        var key = c.day + '-' + c.startPeriod;
        cardsByCell[key] = c;
        for (var p = c.startPeriod; p <= c.endPeriod; p++) occupied[c.day + '-' + p] = true;
      }
    });

    // 用【显式 grid-row / grid-column】摆放每个格子，避免跨行卡片打乱列对齐
    var html = '<div class="corner" style="grid-column:1;grid-row:1">节次 / 时间</div>';
    for (var d = 1; d <= 7; d++) {
      var off = offToday(d, wk);
      html += '<div class="dayhead' + (isToday(d, wk) ? ' today' : '') + (off ? ' offf' : '') +
        '" style="grid-column:' + (d + 1) + ';grid-row:1">' +
        DAY_NAMES[d - 1] +
        (off ? '<span class="offbadge">' + escapeHtml(off.name || '放假') + '</span>' : '') +
        '</div>';
    }

    for (var p2 = 1; p2 <= maxP; p2++) {
      var grow = p2 + 1; // 数据行从第2行开始
      html += '<div class="period" style="grid-column:1;grid-row:' + grow + '">' +
        '<div class="pnum">第 ' + p2 + ' 节</div>' +
        '<div class="ptime">' + (times[p2] ? times[p2][0] + '-' + times[p2][1] : '') + '</div>' +
        '</div>';
      for (var d2 = 1; d2 <= 7; d2++) {
        var ckey = d2 + '-' + p2;
        var card = cardsByCell[ckey];
        var dayOff = offToday(d2, wk);
        var gcol = d2 + 1;
        var wedAct = (d2 === 4 && (p2 === 7 || p2 === 8));
        if (card) {
          var span = card.endPeriod - card.startPeriod + 1;
          var cls = 'card' + (isToday(d2, wk) ? ' today' : '') + (dayOff ? ' onoff' : '');
          // 卡片：只显示 课程名 + 教室
          html += '<div class="' + cls + '" style="grid-column:' + gcol + ';grid-row:' + grow + ' / span ' + span + ';--c:' + (card.color || PALETTE[0]) + '" ' +
            'data-id="' + card.id + '" title="点击查看课程详情">' +
            '<div class="cname">' + escapeHtml(card.name) + (dayOff ? ' ·休' : '') + '</div>' +
            (card.location ? '<div class="cloc">📍' + escapeHtml(card.location) + '</div>' : '') +
            (wedAct ? '<span class="act-tag">活动</span>' : '') +
            '</div>';
        } else if (occupied[ckey]) {
          // 被某张跨行卡片覆盖，不渲染（保持空）
        } else {
          var cellCls = 'cell' + (dayOff ? ' onoff' : '') + (wedAct ? ' activity' : '');
          html += '<div class="' + cellCls + '" style="grid-column:' + gcol + ';grid-row:' + grow + '" data-day="' + d2 + '" data-period="' + p2 + '">' +
            (wedAct ? '<span class="act-hint">集中活动</span>' : '') + '</div>';
        }
      }
    }
    wrap.innerHTML = html;
  }

  // 今天（或所选周的这天）是否高亮
  function isToday(day, wk) {
    var selected = document.getElementById('weekSel').value;
    // 若手动选了某周，则不按"今天"高亮
    if (selected) return false;
    var d = new Date().getDay();
    var mapped = d === 0 ? 7 : d;
    return mapped === day;
  }
  function cardDetail(c) {
    return c.name + '\n' + (c.teacher || '') + '\n' + (c.location || '') + '\n' + weekLabel(c.weeks);
  }
  function weekLabel(weeks) {
    if (!weeks || !weeks.length) return '全学期';
    // 压缩成区间
    var w = weeks.slice().sort(function (a, b) { return a - b; });
    var parts = [], s = w[0], prev = w[0];
    for (var i = 1; i <= w.length; i++) {
      if (i === w.length || w[i] !== prev + 1) {
        parts.push(s === prev ? '' + s : s + '-' + prev);
        if (i < w.length) { s = w[i]; }
        prev = w[i];
      } else { prev = w[i]; }
    }
    return parts.join(',') + '周';
  }

  function escapeHtml(s) {
    return (s || '').replace(/[&<>"']/g, function (m) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[m];
    });
  }

  /* ---------- 模态框 ---------- */
  function openModal(id) { document.getElementById(id).classList.add('show'); }
  function closeModal(id) { document.getElementById(id).classList.remove('show'); }

  /* ---------- 课程详情弹窗 ---------- */
  var detailId = null;
  function openCourseDetail(course) {
    detailId = course.id;
    var times = getTimes();
    var spanTxt = course.startPeriod === course.endPeriod
      ? '第' + course.startPeriod + '节'
      : '第' + course.startPeriod + '-' + course.endPeriod + '节';
    var timeTxt = '';
    var t1 = course.startTime || (times[course.startPeriod] || [])[0];
    var t2 = course.endTime || (times[course.endPeriod] || [])[1];
    if (t1 && t2) timeTxt = t1 + ' - ' + t2;

    document.getElementById('detailTitle').textContent = course.name;
    document.getElementById('detailColor').style.background = course.color || PALETTE[0];

    var rows = [
      ['📚 课程名称', course.name],
      ['👨‍🏫 任课老师', course.teacher || '—'],
      ['📍 上课教室', course.location || '—'],
      ['🗓 上课星期', DAY_NAMES[course.day - 1]],
      ['⏰ 上课节次', spanTxt + (timeTxt ? '（' + timeTxt + '）' : '')],
      ['✅ 上课周次', weekLabel(course.weeks)]
    ];
    document.getElementById('detailBody').innerHTML = rows.map(function (r) {
      return '<div class="drow"><span class="dk">' + escapeHtml(r[0]) + '</span>' +
        '<span class="dv">' + escapeHtml(r[1]) + '</span></div>';
    }).join('');

    openModal('detailModal');
  }

  /* ---------- 课程编辑 ---------- */
  var editingId = null;
  function openCourseEditor(course) {
    editingId = course ? course.id : null;
    var f = document.getElementById('courseForm');
    f.name.value = course ? course.name : '';
    f.teacher.value = course ? (course.teacher || '') : '';
    f.location.value = course ? (course.location || '') : '';
    f.day.value = course ? course.day : 1;
    f.startPeriod.value = course ? course.startPeriod : 1;
    f.endPeriod.value = course ? course.endPeriod : 2;
    f.startTime.value = course ? (course.startTime || '') : '';
    f.endTime.value = course ? (course.endTime || '') : '';
    var weeks = course ? (course.weeks || []) : [];
    buildWeekPicker(weeks);
    document.getElementById('courseModalTitle').textContent = course ? '编辑课程' : '添加课程';
    openModal('courseModal');
  }

  function buildWeekPicker(selected) {
    var box = document.getElementById('weekPicker');
    box.innerHTML = '';
    for (var w = 1; w <= 20; w++) {
      var lab = document.createElement('label');
      lab.className = 'wk' + (selected.indexOf(w) >= 0 ? ' on' : '');
      lab.innerHTML = '<input type="checkbox" value="' + w + '"' + (selected.indexOf(w) >= 0 ? ' checked' : '') + '><span>' + w + '</span>';
      box.appendChild(lab);
    }
  }
  function selectedWeeks() {
    return Array.prototype.map.call(
      document.querySelectorAll('#weekPicker input:checked'),
      function (i) { return +i.value; }
    ).sort(function (a, b) { return a - b; });
  }

  function saveCourse(e) {
    e.preventDefault();
    var f = document.getElementById('courseForm');
    var weeks = selectedWeeks();
    var start = +f.startPeriod.value, end = +f.endPeriod.value;
    if (end < start) { var t = start; start = end; end = t; }
    var data = {
      name: f.name.value.trim(),
      teacher: f.teacher.value.trim(),
      location: f.location.value.trim(),
      day: +f.day.value,
      startPeriod: start,
      endPeriod: end,
      startTime: f.startTime.value,
      endTime: f.endTime.value,
      weeks: weeks
    };
    if (!data.name) { alert('请填写课程名称'); return; }
    if (editingId) {
      var c = state.courses.find(function (x) { return x.id === editingId; });
      Object.assign(c, data);
    } else {
      data.id = uid();
      data.color = pickColor(data.name);
      state.courses.push(data);
    }
    save(); render(); closeModal('courseModal');
  }

  function pickColor(name) {
    var h = 0; for (var i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0;
    return PALETTE[h % PALETTE.length];
  }

  function deleteCourse() {
    if (!editingId) return;
    if (!confirm('确定删除这门课吗？')) return;
    state.courses = state.courses.filter(function (x) { return x.id !== editingId; });
    save(); render(); closeModal('courseModal');
  }
  function deleteFromDetail() {
    if (!detailId) return;
    if (!confirm('确定删除这门课吗？')) return;
    state.courses = state.courses.filter(function (x) { return x.id !== detailId; });
    save(); render(); closeModal('detailModal');
  }

  /* ---------- 校历弹窗 ---------- */
  function openCalendar() {
    var body = document.getElementById('calBody');
    var rows = '';
    for (var w = 1; w <= NJFU_CALENDAR.weeks; w++) {
      var wd = weekDates(w);
      var isCur = (currentWeek() === w);
      var offDays = '';
      if (wd) {
        for (var i = 0; i < 7; i++) {
          var h = holidayOn(ymd(wd[i]));
          if (h) offDays += '<span class="caloff">' + DAY_NAMES[i].slice(1) + ':' + escapeHtml(h.name || '休') + '</span>';
        }
      }
      rows += '<div class="calrow' + (isCur ? ' cur' : '') + '">' +
        '<span class="calwk">第' + w + '周</span>' +
        '<span class="caldate">' + (wd ? fmt(wd[0]) + ' – ' + fmt(wd[6]) : '—') + '</span>' +
        '<span class="caloff-list">' + offDays + '</span>' +
        '</div>';
    }
    body.innerHTML = rows +
      '<div class="calnote"><b>寒假：</b>' + NJFU_CALENDAR.winter + '</div>' +
      '<div class="calnote"><b>说明：</b><ul>' +
      NJFU_CALENDAR.notes.map(function (n) { return '<li>' + escapeHtml(n) + '</li>'; }).join('') +
      '</ul></div>';
    openModal('calModal');
  }

  /* ---------- 导入 / 导出 ---------- */
  function exportJson() {
    var blob = new Blob([JSON.stringify(state, null, 2)], { type: 'application/json' });
    var a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = '课程表备份.json';
    a.click();
    URL.revokeObjectURL(a.href);
  }
  function importJson(text) {
    try {
      var s = JSON.parse(text);
      if (!s.courses) throw new Error('格式不对');
      state = { meta: s.meta || defaults().meta, courses: s.courses };
      save(); render();
      return true;
    } catch (e) { alert('导入失败：' + e.message); return false; }
  }

  // 从教务系统抓回的数据 -> 合并进 state
  function importFromJwgl(result) {
    if (!result || !result.courses) { alert('没解析到课程数据'); return; }
    var replace = confirm('抓到 ' + result.courses.length + ' 门课。\n点"确定"=覆盖现有课表；点"取消"=合并去重保留现有。');
    if (replace) state.courses = [];
    var existing = {};
    state.courses.forEach(function (c) { existing[keyOf(c)] = c.id; });

    var added = 0, merged = 0;
    result.courses.forEach(function (raw) {
      var c = {
        id: uid(), name: raw.name, teacher: raw.teacher || '', location: raw.location || '',
        day: raw.day, startPeriod: raw.startPeriod || 1, endPeriod: raw.endPeriod || (raw.startPeriod || 1),
        startTime: raw.startTime || '', endTime: raw.endTime || '',
        weeks: raw.weeks || null, color: pickColor(raw.name)
      };
      var k = keyOf(c);
      if (existing[k]) {
        // 合并周次
        var old = state.courses.find(function (x) { return x.id === existing[k]; });
        var set = {}; (old.weeks || []).forEach(function (w) { set[w] = 1; });
        (c.weeks || []).forEach(function (w) { set[w] = 1; });
        old.weeks = Object.keys(set).map(Number).sort(function (a, b) { return a - b; });
        if (!old.location && c.location) old.location = c.location;
        if (!old.teacher && c.teacher) old.teacher = c.teacher;
        merged++;
      } else {
        state.courses.push(c); existing[k] = c.id; added++;
      }
    });
    if (result.term && !state.meta.term) state.meta.term = result.term;
    save(); render();
    alert('导入完成：新增 ' + added + ' 门，合并 ' + merged + ' 门。');
  }
  function keyOf(c) { return c.name + '|' + c.day + '|' + c.startPeriod; }

  /* ---------- 事件绑定 ---------- */
  function bind() {
    document.addEventListener('click', function (e) {
      var t = e.target;
      var act = t.getAttribute && t.getAttribute('data-act');
      if (act === 'settings') return openSettings();
      if (act === 'add') return openCourseEditor(null);
      if (act === 'import') return openImport();
      if (act === 'export') return exportJson();
      if (act === 'importJsonFile') return document.getElementById('jsonFile').click();
      if (act === 'print') return window.print();
      if (act === 'closeCourse') return closeModal('courseModal');
      if (act === 'saveCourse') return;
      if (act === 'closeImport') return closeModal('importModal');
      if (act === 'closeSettings') return closeModal('settingsModal');
      if (act === 'saveSettings') return saveSettings();
      if (act === 'quickWeeks') return buildWeekPicker(weekRange(1, 20));
      if (act === 'quickOdd') return buildWeekPicker(rangeFilter(1, 20, 1));
      if (act === 'quickEven') return buildWeekPicker(rangeFilter(1, 20, 0));
      if (act === 'clearWeeks') return buildWeekPicker([]);
      if (act === 'delCourse') return deleteCourse();
      if (act === 'closeDetail') return closeModal('detailModal');
      if (act === 'delFromDetail') return deleteFromDetail();
      if (act === 'editFromDetail') {
        var dc = state.courses.find(function (x) { return x.id === detailId; });
        closeModal('detailModal');
        if (dc) openCourseEditor(dc);
        return;
      }
      if (act === 'campus') {
        state.meta.campus = t.getAttribute('data-campus');
        save(); render();
        document.getElementById('campusSel').value = state.meta.campus;
        return;
      }
      if (act === 'openCalendar') return openCalendar();
      if (act === 'closeCal') return closeModal('calModal');
      if (act === 'closeDrawer') return closeImportDrawer();
      if (act === 'openPaste') return openModal('importModal');

      // 点课程卡片 -> 查看详情
      if (t.classList && t.classList.contains('card')) {
        var id = t.getAttribute('data-id');
        var c = state.courses.find(function (x) { return x.id === id; });
        if (c) openCourseDetail(c);
        return;
      }
      // 点空格子 -> 快速添加
      if (t.classList && t.classList.contains('cell')) {
        var day = +t.getAttribute('data-day'), per = +t.getAttribute('data-period');
        var dup = state.courses.find(function (x) { return x.day === day && x.startPeriod === per; });
        if (dup) return openCourseDetail(dup);
        return openCourseEditorAt(day, per);
      }
    });

    document.getElementById('courseForm').addEventListener('submit', saveCourse);
    // 下拉菜单展开/收起
    var menuBtn = document.querySelector('[data-menu]');
    if (menuBtn) {
      menuBtn.addEventListener('click', function (e) {
        e.stopPropagation();
        menuBtn.parentElement.classList.toggle('open');
      });
      document.addEventListener('click', function () { menuBtn.parentElement.classList.remove('open'); });
    }

    document.getElementById('weekSel').addEventListener('change', renderGrid);
    var campSel = document.getElementById('campusSel');
    if (campSel) {
      campSel.value = state.meta.campus || 'xz';
      campSel.addEventListener('change', function () {
        state.meta.campus = campSel.value; save(); render();
      });
    }
    document.getElementById('jsonFile').addEventListener('change', function (e) {
      var file = e.target.files[0]; if (!file) return;
      var fr = new FileReader();
      fr.onload = function () { importJson(fr.result); };
      fr.readAsText(file);
      e.target.value = '';
    });
    document.getElementById('pasteImportBtn').addEventListener('click', doPasteImport);

    // 点击模态背景关闭
    Array.prototype.forEach.call(document.querySelectorAll('.modal'), function (m) {
      m.addEventListener('click', function (e) { if (e.target === m) m.classList.remove('show'); });
    });
  }

  function openCourseEditorAt(day, period) {
    var c = { id: null, name: '', day: day, startPeriod: period, endPeriod: Math.min(period + 1, MAX_PERIOD), teacher: '', location: '', weeks: [] };
    // 临时对象：用编辑器但不带 id（新增）
    editingId = null;
    document.getElementById('courseForm').name.value = '';
    document.getElementById('courseForm').teacher.value = '';
    document.getElementById('courseForm').location.value = '';
    document.getElementById('courseForm').day.value = day;
    document.getElementById('courseForm').startPeriod.value = period;
    document.getElementById('courseForm').endPeriod.value = c.endPeriod;
    document.getElementById('courseForm').startTime.value = '';
    document.getElementById('courseForm').endTime.value = '';
    buildWeekPicker([]);
    document.getElementById('courseModalTitle').textContent = '添加课程';
    openModal('courseModal');
  }

  function weekRange(a, b) { var r = []; for (var i = a; i <= b; i++) r.push(i); return r; }
  function rangeFilter(a, b, parity) { return weekRange(a, b).filter(function (w) { return w % 2 === parity; }); }

  function openSettings() {
    document.getElementById('termInput').value = state.meta.term || '';
    document.getElementById('startInput').value = state.meta.termStartMonday || '';
    document.getElementById('overrideInput').value = state.meta.weekOverride || '';
    openModal('settingsModal');
  }
  function saveSettings() {
    state.meta.term = document.getElementById('termInput').value.trim();
    state.meta.termStartMonday = document.getElementById('startInput').value;
    var ov = document.getElementById('overrideInput').value.trim();
    state.meta.weekOverride = ov ? +ov : null;
    save(); render(); closeModal('settingsModal');
  }

  /* ---------- 分屏导入抽屉 ---------- */
  // 直接加载课表查询页（会话过期时教务会带完整回跳地址跳统一认证，登录后回到本页）
  var JWGL_URL = 'https://jwxt.njfu.edu.cn/jsxsd/xskb/xskb_list.do';
  function openImport() {
    // EXE 版走弹窗(含一键全自动)；网页版走分屏抽屉
    if (window.njfu && window.njfu.isDesktop) return openModal('importModal');
    openImportDrawer();
  }
  function openImportDrawer() {
    var dr = document.getElementById('importDrawer');
    dr.classList.add('show');
    dr.setAttribute('aria-hidden', 'false');
    document.body.classList.add('drawer-open');
    var frame = document.getElementById('jwglFrame');
    if (frame.getAttribute('src') !== JWGL_URL) frame.setAttribute('src', JWGL_URL);
    setDrawerStatus('就绪。请在右侧登录教务系统并进入「学生课表查询」。', '');
  }
  function closeImportDrawer() {
    var dr = document.getElementById('importDrawer');
    dr.classList.remove('show');
    dr.setAttribute('aria-hidden', 'true');
    document.body.classList.remove('drawer-open');
  }
  function setDrawerStatus(msg, kind) {
    var el = document.getElementById('importStatus');
    if (!el) return;
    el.textContent = msg;
    el.className = 'drawer-status' + (kind ? ' ' + kind : '');
  }

  function doPasteImport() {
    var html = document.getElementById('pasteArea').value.trim();
    if (!html) { alert('请先粘贴课表页面的 HTML 源码'); return; }
    var doc = new DOMParser().parseFromString(html, 'text/html');
    var result = PARSER.parseCourseTable(doc);
    if (!result) { alert('没能从这段 HTML 里识别出课表格子。请确认粘贴的是教务系统"学生课表"整页源码。'); return; }
    importFromJwgl(result);
    setDrawerStatus('✔ 已从粘贴的源码导入 ' + result.courses.length + ' 门课。', 'ok');
  }

  /* ---------- 接收书签回传（分屏 iframe 或独立窗口） ---------- */
  function applyCredits(map) {
    var norm = function (s) { return String(s).replace(/\s|\u00A0/g, ''); };
    var tail = /[（(][^（()）]*[)）]\s*$/;
    var byNorm = {};
    Object.keys(map).forEach(function (name) {
      var cur = name;
      if (!(norm(cur) in byNorm)) byNorm[norm(cur)] = map[name];
      // 剥掉尾部分组括号逐层兜底：「体育(5)（舞龙）」可匹配课表里的「体育(5)」
      for (;;) {
        var m = tail.exec(cur);
        if (!m) break;
        cur = cur.slice(0, m.index).trim();
        if (!cur) break;
        if (!(norm(cur) in byNorm)) byNorm[norm(cur)] = map[name];
      }
    });
    var n = 0;
    state.courses.forEach(function (c) {
      var v = byNorm[norm(c.name)];
      if (v) { c.credit = v; n++; }
    });
    return n;
  }

  function listenForBookmarklet() {
    window.addEventListener('message', function (e) {
      var d = e.data;
      if (!d || d.source !== 'njfu-schedule-import') return;
      // 学分回传：三轮结果完全一致才采信（与 App 端同一约定）
      if (d.type === 'credits') {
        var rounds = (d.rounds || []).map(function (h) {
          try { return h ? PARSER.parseCredits(h) : {}; } catch (err) { return {}; }
        });
        var merged = {};
        if (rounds.length === 3 && Object.keys(rounds[0]).length) {
          Object.keys(rounds[0]).forEach(function (k) {
            var v = rounds[0][k];
            if (v && rounds[1][k] === v && rounds[2][k] === v) merged[k] = v;
          });
        }
        var nn = Object.keys(merged).length ? applyCredits(merged) : 0;
        if (nn > 0) { save(); render(); }
        if (nn > 0) setDrawerStatus('✔ 学分爬取完成：' + nn + ' 门课写入（三轮校验一致）。', 'ok');
        else setDrawerStatus('学分未爬到（三轮结果不一致或接口未开放），课表本身已导入。', 'err');
        return;
      }
      var doc = new DOMParser().parseFromString(d.html, 'text/html');
      var result = PARSER.parseCourseTable(doc);
      if (!result) {
        setDrawerStatus('已收到页面，但没解析到课表。请确认在「学生课表查询」页面点击了书签。', 'err');
        alert('已收到页面，但没解析到课表。请确认你在"学生课表查询"页面点击了书签。');
        return;
      }
      importFromJwgl(result);
      setDrawerStatus('✔ 抓取成功：解析到 ' + result.courses.length + ' 门课，正在继续爬学分…', 'ok');
    });
  }

  /* ---------- 周次选择器下拉 ---------- */
  function fillWeekSelect() {
    var sel = document.getElementById('weekSel');
    sel.innerHTML = '<option value="">当前周</option>';
    for (var w = 1; w <= 20; w++) {
      var o = document.createElement('option'); o.value = w; o.textContent = '第' + w + '周'; sel.appendChild(o);
    }
  }

  /* ---------- 初始化 ---------- */
  function init() {
    fillWeekSelect();
    bind();
    listenForBookmarklet();
    initExe();
    render();
  }

  /* ---------- EXE 版：一键全自动导入 ---------- */
  function initExe() {
    var njfu = window.njfu;
    if (!njfu || !njfu.isDesktop) return; // 普通网页版不显示这块

    var sec = document.getElementById('exeImport');
    if (sec) sec.hidden = false;

    // 载入已存账号（密码不回填，只提示是否已存）
    njfu.getConfig().then(function (cfg) {
      var u = document.getElementById('exeUser');
      if (u && cfg.user) u.value = cfg.user;
      var s = document.getElementById('exeStatus');
      if (s && cfg.hasPass) s.textContent = '✔ 已保存账号 ' + (cfg.user || '') + '，点"一键导入课表"即可。';
    });

    var saveBtn = document.getElementById('exeSaveCred');
    var importBtn = document.getElementById('exeImportBtn');
    if (saveBtn) saveBtn.addEventListener('click', function () {
      var u = document.getElementById('exeUser').value.trim();
      var p = document.getElementById('exePass').value;
      if (!u || !p) { alert('请填写学号和密码'); return; }
      njfu.setConfig({ user: u, pass: p }).then(function () {
        document.getElementById('exePass').value = '';
        document.getElementById('exeStatus').textContent = '✔ 账号已保存到本机。';
      });
    });
    if (importBtn) importBtn.addEventListener('click', function () {
      var u = document.getElementById('exeUser').value.trim();
      var p = document.getElementById('exePass').value;
      if (u && p) {
        njfu.setConfig({ user: u, pass: p }).then(function () { njfu.openJwgl(); });
      } else {
        njfu.openJwgl();
      }
      document.getElementById('exeStatus').textContent =
        '已打开教务窗口并尝试自动登录。请进入「学生课表查询」选好学期，再点窗口右上角「⬇ 抓取课表」。';
    });

    // 收到教务窗口抓回的页面 -> 解析导入
    if (njfu.onScraped) njfu.onScraped(function (data) {
      if (!data || !data.html) return;
      var doc = new DOMParser().parseFromString(data.html, 'text/html');
      var result = PARSER.parseCourseTable(doc);
      if (!result) { alert('已抓取页面，但没识别出课表格子。请确认在"学生课表查询"页面点击抓取。'); return; }
      importFromJwgl(result);
      closeModal('importModal');
    });
    if (njfu.onError) njfu.onError(function (msg) { alert('抓取出错：' + msg); });
  }
  document.addEventListener('DOMContentLoaded', init);

  // 暴露给 importer.js
  window.NJFUScheduleApp = { importFromJwgl: importFromJwgl, state: function () { return state; } };
})();
