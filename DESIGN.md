---
version: "alpha"
name: BabyCare
description: "Soft Care + Data Clarity design system for a baby life tracker Android app."
colors:
  primary: "#0B7185"
  on-primary: "#FFFFFF"
  primary-boy: "#2A74B8"
  primary-boy-dark: "#1F5F99"
  primary-boy-light: "#D9EAF8"
  primary-boy-bg: "#EEF7FE"
  primary-girl: "#B8526C"
  primary-girl-dark: "#94394F"
  primary-girl-light: "#FADBE1"
  primary-girl-bg: "#FFF7F8"
  background: "#F7FBFA"
  surface: "#FFFFFF"
  surface-variant: "#EEF7F5"
  control-surface: "#FFFDFC"
  control-surface-selected: "#FFF3E8"
  control-border: "#E3ECEB"
  event-icon-surface: "#FFF2D9"
  foreground: "#12323A"
  text-secondary: "#3E5B63"
  text-hint: "#7C98A1"
  border: "#CFE2E7"
  feeding: "#247BA0"
  sleep: "#6E65B7"
  diaper: "#F59E32"
  health: "#1FAE9B"
  growth: "#5FAE63"
  milestone: "#D95C75"
  error: "#DC2626"
typography:
  display:
    fontFamily: "sans-serif-medium"
    fontSize: "32px"
    fontWeight: "500"
    lineHeight: "40px"
    letterSpacing: "0em"
  headline:
    fontFamily: "sans-serif-medium"
    fontSize: "20px"
    fontWeight: "500"
    lineHeight: "28px"
    letterSpacing: "0em"
  title:
    fontFamily: "sans-serif-medium"
    fontSize: "18px"
    fontWeight: "500"
    lineHeight: "26px"
    letterSpacing: "0em"
  body:
    fontFamily: "sans-serif"
    fontSize: "16px"
    fontWeight: "400"
    lineHeight: "24px"
    letterSpacing: "0em"
  body-sm:
    fontFamily: "sans-serif"
    fontSize: "14px"
    fontWeight: "400"
    lineHeight: "22px"
    letterSpacing: "0em"
  label:
    fontFamily: "sans-serif"
    fontSize: "12px"
    fontWeight: "500"
    lineHeight: "18px"
    letterSpacing: "0em"
rounded:
  sm: "8px"
  md: "16px"
  lg: "24px"
  full: "999px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "16px"
  lg: "24px"
  xl: "32px"
components:
  app-background:
    backgroundColor: "{colors.background}"
    textColor: "{colors.foreground}"
  toolbar:
    backgroundColor: "{colors.background}"
    textColor: "{colors.foreground}"
    typography: "{typography.title}"
    height: "56px"
  card:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.foreground}"
    rounded: "{rounded.md}"
    padding: "16px"
  card-emphasis:
    backgroundColor: "{colors.surface-variant}"
    textColor: "{colors.foreground}"
    rounded: "{rounded.lg}"
    padding: "20px"
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    typography: "{typography.body}"
    rounded: "{rounded.full}"
    height: "48px"
    padding: "16px"
  chip-selected:
    backgroundColor: "{colors.control-surface}"
    textColor: "{colors.primary}"
    borderColor: "{colors.primary}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.full}"
    height: "40px"
    padding: "12px"
  chip-unselected:
    backgroundColor: "{colors.control-surface}"
    textColor: "{colors.text-secondary}"
    borderColor: "{colors.control-border}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.full}"
    height: "40px"
    padding: "12px"
  input:
    backgroundColor: "{colors.control-surface}"
    textColor: "{colors.foreground}"
    borderColor: "{colors.control-border}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.sm}"
    height: "44px"
    padding: "12px"
---

## Overview

BabyCare 的产品理念是不可改变的基础：记录婴儿生活，服务持续记录婴儿生活习惯的家长，帮助家长理解孩子的喂养、睡眠、排泄、健康与成长规律，并基于记录预测孩子后续可能的活动，从而减轻父母带娃难度，让孩子更健康地成长。

**Soft Care + Data Clarity** 是服务这一理念的视觉方向，而不是产品理念本身。界面要温和、亲切、清晰、紧凑；疲劳、夜间、单手操作和快速判断只是高频使用约束，不能替代“记录生活规律、理解规律、辅助预测、促进健康成长”这个核心。

当前落地语言为 **Soft Utility 2.0**：控件更轻、颜色更暖、更活泼，但不做老式糖果填充和大面积粉蓝块。它强调“柔和承托面 + 清晰语义色 + 低噪音数据层级”，尤其用于首页快速记录、记录事件页、喂养/睡眠表单和统计图表。

产品第一层是生活规律仪表盘：喂养、睡眠、排泄、健康、成长数据必须一眼可读。第二层是成长记忆：里程碑、照片和备注可以更柔和，但仍然沿用同一套颜色、圆角、字体和间距。

主题只保留男孩与女孩两套品牌分支。未识别性别使用男孩主题兜底，不再提供默认宝宝主题或中性主题。

## Colors

默认品牌色使用冷静的青蓝色，表达可信、干净和健康。男孩主题使用可读的海洋蓝，女孩主题使用低饱和花瓣玫瑰色。主题色只影响品牌强调、主按钮、选中态和少量装饰，不改变喂养、睡眠、健康等功能色。

男女主题色必须拆分为行动色与柔和背景色：

- **Boy action (#2A74B8):** 主按钮、选中态、关键文字强调；pressed / dark 使用 #1F5F99。
- **Boy soft (#D9EAF8 / #EEF7FE):** 头像底、浅卡片、轻量状态背景，不承载白字。
- **Girl action (#B8526C):** 主按钮、选中态、关键文字强调；pressed / dark 使用 #94394F。
- **Girl soft (#FADBE1 / #FFF7F8):** 头像底、浅卡片、轻量状态背景，不承载白字。

行动色叠加白色文字的对比度必须不低于 4.5:1。浅色 token 只能搭配深色文字或作为背景层，不允许直接用于白字按钮、选中 Chip、Tab 选中底或保存按钮。

功能色必须稳定，使用 Soft Utility 2.0 暖活泼色板，禁止回退到老式高饱和蓝、脏粉、灰暗紫或沉闷深绿：

- **Feeding (#247BA0):** 喂养、奶量、喂奶预测，使用清爽蓝绿。
- **Sleep (#6E65B7):** 睡眠、清醒窗口、睡眠预测，使用柔和靛紫。
- **Diaper (#F59E32):** 排泄、尿布、便便结构，使用温暖琥珀。
- **Health (#1FAE9B):** 体温、用药、就医、疫苗，使用清新薄荷。
- **Growth (#5FAE63):** 身高、体重、头围和成长趋势，使用明快鼠尾草绿。
- **Milestone (#D95C75):** 成长瞬间、第一次尝试、纪念事件，使用温暖玫瑰。

记录页与快速记录必须使用专用控件底色：

- `control_surface_default (#FFFDFC)`：输入框、未选中分段控件、普通记录控件底。
- `control_surface_selected (#FFF3E8)`：按压态、轻量当前态或极低层级状态底，不再作为普通选择控件的默认选中填充。
- `control_border_default (#E3ECEB)`：输入框和未选中控件 hairline 边框，统一使用 `@dimen/surface_stroke_width`（0.1dp）。
- `event_icon_surface (#FFF2D9)`：通用事件图标承托面；具体业务可用对应 `event_*_light`。

除主按钮、保存按钮外，普通选择控件采用 **Quiet Selection**：`control_surface_default` 白底 + 0.1dp 品牌描边 + 品牌文字。业务功能色只用于图标、图表和数据语义，不再同时参与选中边框或文字，避免同一控件出现两套强调色。禁止使用大面积品牌实色块、白字浅底、底部粗色条或多层浅色填充表达选中。

深色模式必须单独定义颜色，不允许直接复用浅色灰阶。夜间主按钮和选中态仍需满足白字 4.5:1；若需要在深色背景上展示品牌文字或图标，应优先使用 light / accent token，而不是把浅色 token 用作按钮底色。正文对比度至少满足 WCAG AA。

## Typography

Android XML 使用 `TextAppearance.BabyCare.*` 作为唯一字体入口。正文最小 14sp，关键正文优先 16sp。统计数字可以使用 Display 样式，但卡片标题和表单标签不能滥用大字号。

不引入外部字体依赖。中文、英文和数字都使用系统 sans-serif，保证 Android 设备渲染稳定。

## Layout

布局采用 4dp / 8dp 间距系统。页面左右边距手机为 16dp，平板为 32dp。卡片默认内边距 16dp，信息密集卡片可用 12dp，重要状态卡可用 20dp。

首页优先级固定为：当前状态、距上次喂养/睡眠、下次预测、快速记录、时间轴入口。记录页优先级固定为：时间、类型、数量/详情、备注、保存。

底部固定按钮和快捷操作栏必须给滚动内容预留 bottom padding，避免内容被遮挡。

## Elevation & Depth

整体使用轻量层次，不做重拟物。**原则上不要给普通 View 设计阴影效果**，页面层级优先通过背景色、边框、留白、分组标题和语义色建立。普通卡片、列表项、输入区、统计模块不使用 elevation、translationZ 或伪阴影色块。

只有弹窗、底部 Sheet、系统浮层等需要从当前页面脱离的临时容器，才允许使用轻量阴影或遮罩；这类例外必须由组件层统一提供，不在页面 XML 里单独声明。

避免复杂多层阴影、强渐变、玻璃模糊和装饰性浮动元素。育儿记录是高频工具，视觉效果要服务快速判断。

## Shapes

圆角规则：

- 小控件、输入框：8dp。
- 普通卡片、列表容器：16dp。
- 当前状态卡、底部 Sheet：24dp。
- 主按钮、Chip、头像底：999dp。

同一层级内不能混用多个圆角尺度。

## Components

Toolbar 统一使用 `BaseToolbar`。列表统一使用 `baby_recyclerview` 模块。页面层不得自建通用 RecyclerView 方案。

主按钮高度 48dp，次级按钮高度不低于 44dp。图标按钮触摸区域不低于 48dp，图标本身建议 20dp 或 24dp。

记录表单中的 RadioButton / Chip 必须复用公共样式：选中态使用 `control_surface_default` 白底、品牌细描边和品牌文字，未选中态使用 `control_surface_default` 与 `control_border_default`。事件图标可以继续使用业务语义色，但选中状态本身只使用品牌 tint。错误和提醒使用语义色而不是主题色。只有主按钮、保存按钮可以使用品牌实色底。

统计卡片优先展示洞察，不堆砌装饰。所有图表必须有文字说明，不能只靠颜色表达含义。

## Do's and Don'ts

Do:

- 使用 `?attr/colorBrand`、`?attr/colorTextPrimary`、`?attr/colorSurface` 等语义 token。
- 用户可见文本全部放入 `strings.xml`。
- 记录页保持单手可操作，保存按钮固定在底部并避开导航栏。
- 所有长任务给出加载、成功或失败反馈。
- 深色模式逐屏验证，不从浅色模式推断。
- 使用 24dp viewport、2dp stroke、round linecap/linejoin 的线性图标体系；婴儿生活图标优先表达记录类型，不做卡通化装饰。
- 首页快速记录与记录事件页图标使用浅色圆形承托面，图标本体用业务语义色，不用整段高饱和彩色文字制造强调。

Don't:

- 不再新增或恢复 Neutral / 中性主题。
- 不在 `app/` 中重复定义业务通用颜色、style 或 theme。
- 不用硬编码 `#RRGGBB`、硬编码用户可见文本或随意 `dp` 间距。
- 不用 emoji 作为结构性图标。
- 不给普通 View、卡片、列表项、输入框、统计模块单独加阴影。
- 不把记录页选中态做成白字实色块、底部粗色条或旧蓝紫描边。
- 不让 Adapter 承担业务判断。
