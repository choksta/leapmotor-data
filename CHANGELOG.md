# 更新日志

## 0.2.0 — 2026-11-04

首个公开版本。在零跑 B01 2025 款（650 激光雷达版）上跑通。

### 功能

- 手机号 + 短信验证码登录，自动换取车端凭证，凭证过期自动续期
- 车辆页：112 个信号分 9 个区块，关键指标图形化（四轮胎压环形仪表、车窗开度条、续航实时/CLTC 双条对比、挡位盘）
- 趋势页：近 30 天逐日里程柱状图、近六周百公里能耗折线图、能耗三档横向对比、本地行程列表
- 控制页：车门、车窗四档、遮阳帘、后备箱、寻车、空调全参数、一键速调、充电上限、健康充电、哨兵
- 前台自适应轮询（行驶 6 秒 / 充电 30 秒 / 驻车 30 秒），`signal_time` 去重，落 SQLite 并按状态翻转切分行程
- 车辆位置支持跳转高德，地址用系统 Geocoder 逆地理编码

### 修复

开发过程中改掉的主要问题，都是实测才暴露的：

**登录**
- POST 登录接口参数全在 query、请求体为空，OkHttp 不允许 `method("POST", null)`，请求根本发不出去
- `signParam` 的 r2/r3 是标准 base64（含 `/`），用 URL-safe 解码器会抛 `Illegal base64 character 2f`。改成先归一化字符再解码
- 车辆列表响应的数组字段有 `bindcars` / `sharedcars` / `cars` / `list` / `vehicleList` 五种形态，VIN 字段有 `vin` / `carVin` / `carvin`，原来只认前两种

**状态同步**
- 轮询拉回来的数据只写进数据库，没推给界面，导致界面永远停在最后一次手动刷新
- 指令后的观察期用「`signal_time` 前进」作为停止条件，但车机收到指令会先上报一个中间帧，据此 break 会让界面停在旧状态
- 加了乐观更新：点完按钮 1 秒内先改界面，后台跑满 30 秒观察期再交还真实状态，失败回滚

**车控**
- 车门/后备箱/寻车的 `value` 传了整数，服务端 500 `java.lang.Integer cannot be cast to java.lang.String`，必须传字符串（`"lock"` / `"unlock"` / `"true"`）
- 「车窗全开」传的是 `2`，而 `2` 是微开。`WINDOW_LEVELS = {close:0, vent:2, half:5, open:10}`，全开应该是 `10`
- 健康充电不能走 cmdId 480，服务端回「请求成功」但车端不执行，必须走专用接口 `/v3/api/healthyCharging/control`
- 改充电上限是整段覆盖不是合并，只发 `chargesoc` 会清掉预约时段。改成先读 `commonConfig` 再原样回写全部字段，读不到就不下发

**采样**
- `LifecycleObserver` 注册时 Activity 已经是 STARTED，不会补发 `ON_START`，轮询压根没启动
- 轮询跑在 `viewModelScope`（主线程）里，同步网络请求抛 `NetworkOnMainThreadException`，又被 `runCatching` 吞掉——界面显示「前台采集中」，实际一条数据没入

**界面**
- `innerPadding` 当成容器 padding 用，整块内容被压到系统栏内侧，底部露出一条平色带。官方要求交给列表的 `contentPadding`，才能让内容从系统栏下方穿过
- Snackbar 贴在 Scaffold 底部，被悬浮胶囊挡住。按实测像素把间距调到 14dp
- 「常用」四个方块塞进 `HorizontalFloatingToolbar`，容器自带圆角、子元素也自带圆角，成了圆套方。撤掉外层容器
- 58 处 `FontFamily.Monospace` 换掉，改用 `fontFeatureSettings = "tnum"`。字形回到系统字体，数字仍然等宽不跳动
- 系统字号放大到 1.3 倍时，四宫格里「2.53 bar」的单位被折行裁掉

### 安全

- 凭证原来以明文写进 logcat（`HEADERS` 含完整 JWT，`RAW` 是签名原文，车控时含加密后的操作密码）。改成只记字段数量
- 会话与车控 PIN 原来明文存：`session.json` 1531 字节可直接读出 token。改用 Android Keystore 的 AES-256-GCM 加密到 `session.bin`，首次读取旧明文文件后自动迁移并删除，用户无需重新登录
- token 失效时原来直接把用户踢回登录页。改成强制续期后重试一次，重试仍失败才判定登录失效

### 工程

- 开启 R8 + 资源压缩：**87MB → 2.4MB**。之前 `material-icons-extended` 未裁剪，dex 单独占 56MB
- 补 `proguard-rules.pro`（原来文件不存在，开 R8 直接构建失败）
- 删除 14MB 死素材与 `Car3DView`（全项目无调用）
- 补齐 M3 规范：定义 `Typography` 与 `Shapes` 令牌，字号全部落到官方刻度（原来有 8/13/15/18/34/38sp 等非标值，8sp 低于最小 11sp），38 处硬编码圆角改为令牌引用
- 软键盘 `adjustResize` + `imePadding`，登录页可滚动
- 底栏改用 `selectable + Role.Tab`，TalkBack 会播报「已选中」
- Canvas 图表补语义摘要，分组标题加 `heading()`
- 宽屏适配：≥600dp 用 `NavigationRail`，内容居中限宽

### 已知问题

- App 不在前台不采样，行程会断段
- 18 个信号仍未破解
- 3D 车模未实现（WebView 视口高度为 0，画布不渲染）
- 只实测过 B01 2025 款

---

## 0.1.0

内部初版。三页 UI、车况看板、车控、轮询落库跑通，没有登录页（靠 adb 推送会话文件），没有图标，没有退出登录。
