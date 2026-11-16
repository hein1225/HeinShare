# 海因分享（HeinShare）· 界面预览

按 `android/app/src/main/res` 下的**真实布局、配色、字符串**还原的 HTML 界面预览，
并附逐屏截图。用于在没有真机 / 模拟器的环境下核对 UI。

## 目录

```
preview/
├── index.html            # 总览页：七屏并排（也是各屏的母版）
├── assets/
│   ├── app.css           # 样式，数值全部照抄 res/values/colors.xml
│   ├── ic_logo.png       # 应用图标（复制自 res/drawable-nodpi）
│   ├── icons/*.svg       # 32 个 VectorDrawable 转出的图标
│   └── illus/*.png       # AI 生成的扁平插画（复制自 res/drawable-nodpi）
├── screens/*.html        # 拆出的单屏页，每屏一个文件，可直接用浏览器打开
├── shots/*.png           # 逐屏截图（2x）
└── tools/
    ├── split_screens.py  # 由 index.html 拆出单屏页
    └── shoot_preview.py  # 用 CDP 逐屏截图
    └── ws_cdp.py         # 纯标准库的 WebSocket 客户端（无 pip 环境可用）
```

## 用法

### 直接看

```bash
cd preview
python3 -m http.server 8899
# 浏览器打开 http://127.0.0.1:8899/index.html
```

单屏页：`http://127.0.0.1:8899/screens/01-首页.html`

> 用 `file://` 直接打开也能显示，但浏览器可能拦截本地图片，所以推荐起个静态服务。

### 重新生成截图

前置条件：容器浏览器开着 CDP 端口 `127.0.0.1:9222`（`browser_control status` 可查），
`preview/` 目录上有静态服务。

```bash
cd preview
python3 tools/vector2svg.py ../android/app/src/main/res/drawable assets/icons   # 重新转图标
python3 tools/split_screens.py          # 重新拆单屏页
python3 tools/shoot_preview.py          # 逐屏截图到 shots/
```

## 还原规则

- **颜色**：直接取 `res/values/colors.xml` 的值，写成 CSS 变量。
- **尺寸**：1dp = 1px（预览按 412dp 宽的手机渲染，对应主流安卓机）。
- **图标**：`res/drawable/ic_*.xml`（VectorDrawable）由 `android/tools/vector2svg.py`
  转成 SVG，支持 `path` 的 fillColor / strokeColor / strokeWidth / strokeLineCap /
  strokeLineJoin / pathData，以及 `group` 的 rotation / pivot / scale / translate。
- **文案**：取自 `res/values/strings.xml`。
- **结构**：照抄 `res/layout/*.xml` 的层级与 padding / margin。

## 已知差异

| 差异 | 原因 |
| --- | --- |
| 字体渲染与真机略有出入 | 预览用系统字体，真机是 Roboto / HarmonyOS Sans |
| 长文本换行位置可能不同 | 预览按 412dp 宽，真机取决于实际屏幕密度与宽度 |
| 进度条动画、滑动等交互未实现 | 预览是静态页面 |
| 状态栏图标是静态的 | 真机由系统绘制 |

## 预览中发现并已修复的真机问题

- `ic_device.xml` 的 pathData 画到 y=29，超出 24 的 viewport，
  真机上「设备名称」那一行的图标底部会被裁掉。已把路径收进 viewport 内。
