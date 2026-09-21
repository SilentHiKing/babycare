# 公共计时器规则与数据模型

日期：2026-09-20。状态：按确认方案实施，验证结果见文末。

## 1. 最终选定方案

统一计时规则，复用现有 Feeding_Records、Sleep_Records、Event_Records 三张业务表，不新增计时会话表或片段表。原业务记录自身承担会话身份，片段通过结构化 JSON 存在同一行中。

这样既能记录暂停、换侧和夜醒，又不引入独立会话与最终业务记录之间的同步、复制和清理流程。数据库仍为 8 张业务/辅助表，版本由 9 升至 10；“不新增表”并不等于“不改变结构”。项目尚未上线，按用户要求仅对版本 1–9 定向重建，不构造历史片段；未来版本没有迁移时必须报错。

## 2. 调研证据与适用边界

| 一手资料 | 能支持的结论 | 不能据此推断的内容 |
|---|---|---|
| [Huckleberry：是否记录每次夜醒](https://huckleberry.zendesk.com/hc/en-us/articles/360025521194-Do-I-need-to-track-every-night-waking) | 不强迫家长逐次记录短暂夜醒；用户希望详细回看时可以记录 | 不存在由此确立的通用夜醒分钟阈值 |
| [Huckleberry：夜间亲喂但未完全清醒](https://huckleberry.zendesk.com/hc/en-us/articles/360025667093-If-my-child-nurses-throughout-the-night-but-isn-t-fully-awake-do-I-count-those-periods-as-night-wakings) | 喂养与睡眠可以重叠，不必自动拆成新的睡眠记录 | 不能从一次喂养自动推定宝宝醒了 |
| [Huckleberry：睡眠起点](https://huckleberry.zendesk.com/hc/en-us/articles/360025667113-Do-I-count-the-start-of-a-sleep-session-when-I-put-my-child-down-to-sleep-or-when-he-actually-falls-asleep) | 记录用户估计的实际入睡时间，而非强制以放床时间为起点 | 手工记录不具有传感器级精度 |
| [Huckleberry：补记及迟开始计时](https://huckleberry.zendesk.com/hc/en-us/articles/360025521614-How-do-I-log-a-sleep-session-in-the-past-or-one-that-started-10-minutes-ago) | 完整补记和从过去起点继续计时都是合理入口 | 不能把点击按钮的时间覆盖为业务实际起点 |
| [Baby Daybook：喂养记录能力](https://babydaybook.app/blog/the-signs-baby-is-getting-enough-milk/) | 产品区分亲喂侧别、时长、暂停，以及瓶喂时间和奶量 | 本文仅参考功能，不采用该营销文章的健康判断或医学阈值 |
| [Baby Tracker 官方 FAQ](https://nighp.com/babytracker/babytracker_faq.html) | 离开亲喂页面后仍可继续计时，并进行其他记录 | 产品所谓后台计时不证明必须运行常驻服务 |
| [CHI 2025：Understanding Temporality of Reflection in Baby Tracking](https://www.cs.pomona.edu/~apapoutsaki/papers/chi2025understanding.pdf) | 对 20 位美国家长的访谈显示，即时检查、稍后回忆和累计回看有不同需求；支持把当前状态与历史统计分开表达 | 小样本访谈不构成全球行业标准或医学规则 |

以上资料支持低负担记录、可补记、状态可回看等方向，但没有证明所有产品采用相同的暂停算法。下述状态机和数据库设计是针对 BabyCare 的工程与产品建议。

[2026-05-02 的项目设计](E:/Code/Android/babycare/docs/superpowers/specs/2026-05-02-record-timer-design.md)曾明确选择“睡眠与活动按完整区间，暂停不扣除”。因此，现有睡眠算法符合旧设计，不能把它本身称为实现错误。新方案建议调整这个产品规则：**未记录的夜醒不猜测；明确记录的清醒必须扣除。** 实施时需同步更新旧设计文档、UI 规范和对应测试，不能留下互相冲突的要求。

## 3. 统一规则

- 开始：插入一条未完成业务记录及首个片段；同宝宝同类型已有未完成记录时打开原记录。
- 暂停：关闭当前片段，停止累计；继续从当前时间新增片段，暂停间隔不补回。
- 结束：先独立提交 STOPPED。运行态关闭末段；已暂停时保留最后的停止时刻。
- 保存：仅把原行变为 COMPLETED，与幂等回执在同一事务提交。失败保留 STOPPED，重试不新建记录、不延长结束时间。
- 返回、换宝宝、锁屏或进程退出不删除计时。明确“放弃计时”才删除未完成行。
- 母乳支持左侧、右侧、未区分侧别；换侧产生片段，总时长与左右汇总从片段派生。手填左右总量不用于推测先后顺序。
- 睡眠主操作为“醒了并保存”；可选“夜间醒了／再次入睡”表示同一次睡眠中的清醒间隔，不强迫记录短暂夜醒，也不从喂养自动推断夜醒。
- 活动及护理中的六种时长记录使用相同机制，按具体事件子类型保持独立槽位。
- 非亲喂记录允许只记录发生时间和数量，标记 UNMEASURED；结束时间为 0，时长未测量不能解释为观察到零时长。
- 纯手工区间标记 MANUAL_INTERVAL，只有累计量且未知过程的数据标记 MANUAL_TOTAL，真实过程标记 SEGMENTED。没有已知过程时不伪造片段。

## 4. 数据结构

| 字段 | 含义 |
|---|---|
| timerStatus | COMPLETED=0、RUNNING=1、PAUSED=2、STOPPED=3；普通手工记录默认完成 |
| timerSessionId | 本次计时的稳定 UUID，不随编辑日期变化 |
| timerRevision | 每次变化递增，拒绝旧页面覆盖新状态 |
| timerSlot | 同宝宝未完成槽位；喂养、睡眠固定 1，活动按子类型；完成后为 NULL |
| timerData | 经统一 Codec 校验的结构化 JSON：来源、片段、时钟锚点、核对标记 |

EventRecord 另加 activeDurationMs 汇总字段。现有 feedingDuration、sleepDuration、左右时长和 activeDurationMs 是从片段派生的缓存，同事务更新，不是第二套可编辑事实。

TimerSegment 保存开始、结束、有效毫秒数、侧别/类别、来源。关闭段不重叠且顺序明确，只有末段可以开放。JSON 有长度、数量和结构深度限制，读取、保存、备份使用相同校验。唯一索引保证一个宝宝的同一槽位只存在一条未完成记录。

## 5. 分层与恢复

`RecordTimerRepository` 负责事务、身份/版本验证及业务转换，`TimerTransitions` 负责纯状态规则；ViewModel 的 `PersistentTimerEditor` 串行执行命令并发布 State。

`RecordTimerController` 采集输入并渲染数据库快照。`RecordView` 不在点击时抢先修改状态，显示用 `TimerCounter` 只插值和格式化；页面不可见时不持续刷新。`OngoingRecordManager` 只提供 Room 的只读投影，不再写 MMKV 会话。

同次开机使用 elapsedRealtime 计算经过时间，墙钟用于日期。重启、时钟变动造成的不确定性保留为 ESTIMATED/待核对；停止事实仍持久化。用户核对边界后才能完成记录，不以静默移动全部历史片段制造精确结果。

输入“12”等未完成时间不能被开始按钮解释为“现在”。日期编辑只调整首段/末段，不删除已知暂停；重复确认同一秒的结束时间不重算累计时长。重建时，未完成原始输入独立于持久计时保存，不能被旧快照覆盖。

## 6. 统计、预测、提醒与备份

- 正式列表、统计、预测和导出只读取 COMPLETED。首页另查询未完成行，区分正在进行、暂停和等待保存。
- 跨日时长逐片段与当天范围求交；记录次数仅归属于原记录的开始日，暂停不会增加次数。
- 睡眠因子使用有效 sleepDuration；预测来源锚点使用最后有实际时长的结束片段。未测时长喂养用发生时间作为锚点。
- 待处理计时仍抑制同类型的旧预测提醒，界面说明其状态，不把暂停显示为正在吃奶或睡觉。
- 备份版本为 2。导出结构化 timing 与有效汇总，不携带设备时钟锚点、未完成状态或本地会话锁。
- 导入复用统一校验；去重包含片段顺序和来源，不因总时长相同而合并不同过程。旧备份不作为本次兼容目标。

## 7. 验证重点

验证开始→暂停→继续→结束、换侧、六种活动恢复、跨午夜及整日暂停、手动时间编辑、未测时长记录编辑、旧版本定向重建、冷恢复不重复保存、停止后完成失败重试、备份往返与片段差异去重。

不引入传感器检测、常驻前台服务、云同步或医学判断。本次不会声称能推断未记录的夜醒，也不会对设备关机期间的时长承诺观测精度。

### 执行结果（2026-09-20）

- Debug APK、应用与数据层测试 APK 构建通过；应用层 186 项、数据层 40 项 JVM 测试全部通过。
- Android 16 / API 36 模拟器完成 55 项相关仪器测试：应用层 46 项，数据层 9 项。覆盖真实页面重建、停止失败重试、冷恢复防重复、六种活动子类型、分段日期预览与保存一致、版本冲突后的读取失败、统计提醒与备份恢复。
- 旧测试中 JUnit 初始化返回类型及缺少时长来源的种子数据已修正；失败用例所在的两组共 12 项复跑全部通过。没有通过删除断言跳过失败。
- 公共面板在男孩／女孩、浅色／深色、窄宽布局及大字体下完成布局检查；四种主题组合的喂养页面已截图检查。ADB 实际执行换侧、暂停、强制关闭、恢复、继续和保存；暂停时长保持不变，保存后仅生成原来的同一条记录。
- 最新应用 APK 已自动安装到 MI6（API 28）。该设备锁屏且 MIUI 拒绝测试 APK，故不声称完成真机 UI 或仪器回归；上述交互及仪器验证在模拟器执行，全程无需用户手动确认。
- Room 导出 schema 为版本 10，仍为 8 张表；多语言由 Excel 生成，差异格式检查通过。原有 `.idea/deploymentTargetSelector.xml` 改动保持不动。

本地完整构建日志、仪器测试结果和界面截图存放于忽略目录 `build/timer-verification/`。本轮没有新增依赖、前台服务或独立计时表。
