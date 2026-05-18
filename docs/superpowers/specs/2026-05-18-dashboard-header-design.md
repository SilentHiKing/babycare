# 首页头部与区块标题优化设计

## 背景

首页当前使用居中 `BaseToolbar` 标题和左侧菜单按钮，视觉上更接近普通页面导航栏；但首页的第一任务是让父母快速判断宝宝当前状态、查看预测、完成记录。截图中头部、`下次预测`、`快速记录` 三处标题层级偏高，会抢走内容本身的注意力。

本次设计参考竞品的“宝宝身份型头部”，但只借鉴首页标题组织方式，不引入订阅卡、底部导航、运营卡片或新的业务入口。

## 目标

1. 首页头部从“页面标题”改为“宝宝身份”：头像圆底、宝宝名、下拉箭头。
2. 去掉左侧侧边栏按钮，侧边栏通过左边缘右滑打开。
3. `下次预测` 与 `快速记录` 从外置大标题改为卡片内部小标题，让用户优先关注预测结果和快捷入口。
4. 认真控制标题的颜色、字号、字重和间距，使首页整体与 Soft Utility 2.0 的低噪音工具感一致。

## 非目标

1. 不新增宝宝头像字段，不改 Room schema。
2. 不重做首页信息架构，不新增底部导航或竞品式运营内容。
3. 不改变预测、快速记录、记录入口的业务逻辑。
4. 不新增第三套主题或中性主题。

## 设计方案

### 头部结构

首页继续复用 `BaseToolbar` 的状态栏适配和统一高度，但新增一种“身份标题”配置能力：

- 左侧显示 40dp 圆形头像承托面，背景使用 `?attr/colorSurfaceVariant` 或男孩 / 女孩主题柔和背景。
- 头像内使用现有 `com.zero.common.R.drawable.ic_baby_default`，图标颜色使用 `?attr/iconColorBrand`。
- 宝宝名显示在头像右侧，使用 `TextAppearance.BabyCare.Heading2`（18sp medium），颜色使用 `?attr/colorTextPrimary`。
- 下拉箭头使用新增线性 `ic_chevron_down`，24dp viewport、约 2dp stroke、round cap / join，颜色使用 `?attr/iconColorSecondary`。
- 整个身份标题区域可点击，点击后进入所有宝宝页，用于切换宝宝。
- 右侧不显示菜单图标；若后续需要设置入口，另行设计，不在本次范围内临时堆到首页头部。

无宝宝状态时，身份标题显示 `no_baby_yet`，点击进入创建宝宝流程。

### 标题视觉层级

本次最重要的视觉规则是：标题只负责建立分组，不负责制造主视觉。

- 首页宝宝名：18sp medium，`?attr/colorTextPrimary`，它是当前对象识别，不做品牌色强调。
- 卡片内部主标题：14sp medium，建议使用 `TextAppearance.BabyCare.Action` 的字重思路，但颜色改为 `?attr/colorTextPrimary`。如果实现上复用 `Body2`，需要显式设置 medium 字重或新增局部样式。
- 卡片内部右侧辅助说明：12sp regular / medium，使用 `TextAppearance.BabyCare.Label`，颜色使用 `?attr/colorTextHint`。
- 预测和快速记录标题不得继续使用 `Heading1`（20sp），也不得使用品牌色或业务功能色。
- 标题与卡片内容的距离控制在 8dp 左右，避免形成新的大段留白。
- 卡片标题行和内容同属一个 surface，视觉上应像“内容说明”，而不是新的页面章节。

这样处理后，首页视觉焦点顺序应为：

1. 当前状态卡的状态和计时。
2. 喂养 / 睡眠距上次数据。
3. 预测结果与快速记录入口。
4. 卡片内部小标题。
5. 顶部宝宝身份。

### 预测卡

删除外部 `tvPredictionTitle`。在 `cardPrediction` 内部顶部新增标题行：

- 左侧：`next_prediction`
- 右侧：新增文案 `based_on_recent_records`，简体中文为“基于近期记录”

标题行下面继续展示喂养预测和睡眠预测。预测结果的图标、文本、剩余时间逻辑不变。

### 快速记录卡

删除外部 `tvQuickRecordTitle`。为 `rvQuickRecord` 增加一个外层 `cardQuickRecord` 容器：

- 背景沿用 `bg_card_prediction` 或统一 surface + 0.1dp stroke 的圆角卡片。
- 内部顶部标题行左侧显示 `quick_record`，右侧新增文案 `common_entry`，简体中文为“常用入口”。
- `rvQuickRecord` 保持 `baby_recyclerview` 适配器与现有点击逻辑，不把业务判断写入 Adapter。

### 侧边栏手势

`MainActivity` 继续作为侧边栏唯一持有者。方案为双层保障：

1. 初始化时明确设置 `drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.START)`，避免未来页面切换或状态恢复后侧边栏被锁定。
2. 在 `MainActivity` 对内容根容器增加左边缘右滑检测，仅当当前页面是 Dashboard 时生效：
   - 起始点必须在左侧 24dp 到 32dp 内。
   - 横向位移超过 48dp。
   - 横向位移至少为纵向位移的 1.5 倍。
   - 满足条件后调用 `openDrawer()`。

该检测只作为 DrawerLayout 边缘手势的补强，不截断普通点击和纵向滚动。若 Android 10+ 系统返回手势仍抢占左边缘，再仅对 Dashboard 的左边缘小区域设置 `systemGestureExclusionRects`。

## 代码边界

预计改动：

- `components/src/main/java/com/zero/components/widget/BaseToolbar.kt`
- `components/src/main/res/layout/layout_base_tool_bar.xml`
- `common/src/main/res/drawable/ic_chevron_down.xml`
- `app/src/main/res/layout/fragment_dashboard.xml`
- `app/src/main/java/com/zero/babycare/home/DashboardFragment.kt`
- `app/src/main/java/com/zero/babycare/MainActivity.kt`
- `tools/language/多语言对照表.xlsx` 与生成的多语言资源

模块边界：

- 通用 Toolbar 能力放在 `components`。
- 通用图标放在 `common`。
- 首页组合布局放在 `app`。
- 不让 `components` 依赖 `app` 或 `babydata`。

## 本地化

新增用户可见文案必须先写入 `tools/language/多语言对照表.xlsx` 的 `common` sheet，再运行 `updateAllLanguages`。

建议新增 key：

- `based_on_recent_records`
- `common_entry`

翻译语义：

- 简体中文：基于近期记录 / 常用入口
- English：Based on recent records / Common entries
- 繁体中文、日文、韩文需按 BabyCare 高频记录语境补齐。

## 测试与验证

实现后至少运行：

```powershell
.\gradlew.bat updateAllLanguages
.\gradlew.bat :app:assembleDebug
```

手动验证：

1. 首页头部无菜单按钮，显示头像、宝宝名和下拉箭头。
2. 点击宝宝身份区域进入所有宝宝页或创建宝宝流程。
3. 首页左边缘右滑可打开侧边栏。
4. 首页纵向滚动、卡片点击、快速记录点击不被右滑检测误伤。
5. `下次预测` 与 `快速记录` 标题在卡片内部，字号和颜色弱于内容主数据。
6. 男孩 / 女孩主题、浅色 / 深色模式下标题颜色和对比度正常。
7. 无硬编码用户可见字符串，无页面级硬编码颜色。

## 设计自检

- 模块边界清晰：通用 Toolbar 在 `components`，通用图标在 `common`，首页布局在 `app`。
- 不新增 Room 字段，不触碰备份、提醒、预测算法或 WHO 数据。
- 视觉重点从标题转回状态、数据和快捷入口。
- 标题颜色、字号和字重有明确规则，避免实现时只做简单缩小。
- 侧边栏手势方案先修根因，再使用边缘补强，避免用透明按钮覆盖页面。
