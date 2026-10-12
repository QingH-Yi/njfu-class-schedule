# 南林课程表 🐤

南京林业大学课程表应用——自动导入教务系统（强智）课表，支持三校区作息、教学周自动计算。

**下载安装**：前往 [Releases](https://github.com/QingH-Yi/njfu-class-schedule/releases) 下载 `南林课程表-vX.X-release.apk` 直接安装。
**官网 / 介绍页**：<https://qingh-yi.github.io/njfu-class-schedule/>

## 功能

- 🗓 **经典 7×11 课程表网格**：星期×节次，10 色马卡龙课程卡片，今日列高亮、正在上的课高亮
- ↔️ **左右滑动切换周次**：顶部全年日期条可拖动跳转，点某天直接跳到那一周
- 👆 **单击选中**课程格；**双击打开详情**：完整信息、备忘录（格子右上角 📝 角标）、学分（自动从教务爬取，可手动修正）
- ➕ **点空白格手动添加课程**：每周 / 单周 / 双周 / 仅本周
- 🔄 **自动同步**：保存账号密码后，打开 App 自动登录教务并更新课表（失败不影响已保存课表）
- ⬇️ **检查更新**：打开时后台访问 GitHub Releases，发现新版顶部横条一键下载（可在设置关闭）
- 🎨 **4 套色系 + 自定义色相**、浅色/深色/跟随系统、反色、一周起点可选周日
- 🏫 **三校区作息**：新庄(本部)/淮安 08:00 起课，白马 08:30 起课
- 📖 首次打开有使用教程（设置底部可随时重看）

## 目录结构

```
class schedule/
├── apk/       安卓版（Compose 原生，主力版本）
├── web/       网页版（PWA，书签栏一键导入）
└── electron/  Windows EXE 版（代码完成，待打包验证）
```

## 数据与隐私

- 账号密码只保存在本机（SharedPreferences / localStorage），**不上传任何服务器**
- 网络请求只发往 `jwxt.njfu.edu.cn` 与统一认证 `uia.njfu.edu.cn`，以及检查更新时的 `api.github.com`
- 学期校历（第一周周一、假期）内置在代码里，可在设置中修改学期起始日

## 构建

安卓版（JDK 17 + Android SDK 35）：

```powershell
cd apk
gradle assembleDebug     # 产物 app/build/outputs/apk/debug/
gradle assembleRelease   # 需自备签名密钥（不入库），在 app/build.gradle 配置
```

解析器为三端同源：`web/js/parser.js` ⇔ `apk/.../JwxtParser.kt`（改一处必须同步另一处，跑 `gradle :app:testDebugUnitTest` + Node/jsdom 回归）。

## 更新日志

见 [CHANGELOG.md](CHANGELOG.md)，或 [Releases](https://github.com/QingH-Yi/njfu-class-schedule/releases)。
