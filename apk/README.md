# 南林课程表 · 安卓 APK 版（原生 Kotlin）

- 体积小（打包后约 2~3 MB）、打开即用
- 内置简易浏览器导入教务系统课表（右上角固定「抓取课表」按钮）
- 可保存账号密码：打开 App 时后台自动登录+抓取更新（也可关闭，手动点刷新）
- 支持三校区作息（新庄/淮安/白马）、教学周自动计算、第一周周一起始日可改

## 打包步骤（需要 Android Studio）

1. 安装 [Android Studio](https://developer.android.com/studio)（免费）。
2. Android Studio → `File → Open` → 选择本 `apk` 文件夹，等待 Gradle 同步完成（首次会自动下载依赖，需联网）。
3. 菜单 `Build → Build App Bundle(s) / APK(s) → Build APK(s)`。
4. 生成的 APK 在 `app/build/outputs/apk/debug/app-debug.apk`，直接发到手机安装即可。

> 想要正式签名的 release 版：`Build → Generate Signed App Bundle / APK`，按提示创建密钥即可。
> 命令行方式（可选）：`gradlew assembleDebug`（需已装 JDK 17）。

## 使用说明

1. 首次打开点「去导入课表」→ 进入内置教务浏览器。
2. 登录教务系统（有验证码就手输一次），进入「学生课表查询」。
3. 点右上角固定按钮「⬇ 抓取课表」→ 自动解析保存，返回即显示课表。
4. 打开「设置」：填学号密码、勾选「保存密码」「打开时自动更新课表」。
   - 之后每次打开 App 会自动登录抓取一次（会话没过期时秒更）；
   - 会话过期且有验证码时，自动更新会静默失败（保持旧课表），点右上角刷新按钮手动导入即可。
5. 顶栏 📍 图标切换校区；设置里可改「第一周周一」日期或手动指定当前周。

## 技术结构

| 文件 | 作用 |
|---|---|
| `MainActivity.kt` | 主界面（Compose）：按天列表显示课程、校区切换、设置、后台自动更新 |
| `ImportActivity.kt` | 内置浏览器导入：自动填账号、固定抓取按钮、返回结果 |
| `JwxtParser.kt` | 正方教务课表解析器（Kotlin 移植，兼容旧版表格 + 新版门户格式） |
| `Models.kt` / `Repository.kt` | 数据模型 / 本地存储（SharedPreferences + Gson） |

密码仅保存在本机（SharedPreferences），不上传任何服务器。
