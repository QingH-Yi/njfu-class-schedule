# 南林课程表 🐤

南京林业大学课程表安卓应用——自动导入教务系统（强智）课表，支持三校区作息、教学周自动计算。

**下载安装**：前往 [Releases](https://github.com/QingH-Yi/njfu-class-schedule/releases) 下载最新 APK 直接安装。

## 功能

- 🗓 **经典 7×11 课程表网格**：星期×节次，课程卡片按名称自动分配颜色，今日列高亮
- 🎨 **课程自定义颜色**：点进课程详情 → 课程颜色，12 色调色盘即选即改，同名课共用
- 🎨 **6 套色系 + 自定义色相**：马卡龙 / 海盐 / 樱花 / 抹茶 / 薰衣草 / 珊瑚
- ↔️ **左右滑动切换周次**：顶部全年日期条可拖动跳转，点某天直接跳到那一周
- 👆 **双击打开详情**：完整信息、备忘录、学分（自动从教务爬取，可手动修正）
- ➕ **点空白格手动添加课程**：每周 / 单周 / 双周 / 仅本周
- 📤 **导出 Excel 课表 / 一键分享**：生成 xlsx 后直接弹系统分享面板
- 📆 **导出日历 ICS**：按周次和三校区作息展开成日历事件
- 🔄 **自动同步**：保存账号密码后，打开 App 自动登录教务并更新课表
- ⬇️ **检查更新**：打开时后台访问 GitHub Releases，发现新版顶部横条一键下载
- 🏫 **三校区作息**：新庄(本部)/淮安 08:00 起课，白马 08:30 起课
- 🌙 **深色模式**：浅色 / 深色 / 跟随系统

## 数据与隐私

- 账号密码只保存在本机（SharedPreferences），**不上传任何服务器**
- 网络请求只发往教务系统 `jwxt.njfu.edu.cn` 与统一认证 `uia.njfu.edu.cn`，以及检查更新时的 `api.github.com`

## 构建

JDK 17 + Android SDK 35：

```powershell
cd apk
gradle assembleDebug     # 产物 app/build/outputs/apk/debug/
gradle assembleRelease   # 需自备签名密钥（不入库），在 app/build.gradle 配置
```

## 更新日志

见 [CHANGELOG.md](CHANGELOG.md) 或 [Releases](https://github.com/QingH-Yi/njfu-class-schedule/releases)。
