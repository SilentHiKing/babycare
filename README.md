./gradlew updateAllLanguages
./gradlew generateLanguageJson
./gradlew fetchInternationalLanguageList
conda activate test

## 计时逻辑说明

### 统一组件
- 使用 `RecordView` 作为计时器展示，三态：INIT → RECORDING → PAUSE。
- 时间输入使用 `TimeEditText`，输入 4 位数字自动格式化为 `MM-dd HH:mm:ss`，并做跨天与未来时间校验。

### 喂奶/睡眠（FeedingRecord/SleepRecord）
- INIT → RECORDING：若已填写开始时间，用当前时间与开始时间计算偏移；清空结束时间；启动后回填开始时间，并更新结束时间参考时间戳（用于跨天判断）。
- RECORDING → PAUSE：结束时间自动填当前时间，记录 `pausedEndTimestamp`。
- PAUSE → RECORDING：若结束时间被手动修改过，弹确认；确认后清空结束时间并继续计时。
- 手动改开始时间：清空结束时间、重置计时器、更新结束时间参考时间戳。
- 手动改结束时间：强制暂停计时器，校验时间区间并显示时长。
- 时长显示：通过 `RecordView.showDurationWithoutTimer()` 显示“手动时间差”。
- 进行中状态：喂奶/睡眠使用 `OngoingRecordManager` 记录进行中状态并支持恢复。

### 事件记录（EventRecord，带时长事件）
- 布局与喂奶/睡眠一致：`RecordView` + `TimeEditText` 的开始/结束时间输入。
- INIT → RECORDING：按已填开始时间计算偏移，清空结束时间，回填开始时间并更新参考时间戳。
- RECORDING → PAUSE：结束时间填当前时间，记录 `pausedEndTimestamp`，并显示时长。
- PAUSE → RECORDING：若结束时间被手动修改过，弹确认；确认后清空结束时间并继续计时。
- 手动改开始/结束时间：逻辑与喂奶/睡眠一致（重置计时器、校验时间区间、更新时长）。
