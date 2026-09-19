package com.zero.babycare.reminder

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.zero.common.mmkv.MMKVKeys
import com.zero.common.mmkv.MMKVStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assume.assumeTrue
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 使用真实系统广播及 WorkManager；仅限 AVD，不改变墙钟，也不写宝宝或生活记录。 */
@RunWith(AndroidJUnit4::class)
class ReminderSystemRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val workManager = WorkManager.getInstance(context)
    private var restoreState = false
    private var originallyEnabled = false
    private var originalTimeZone: String? = null
    private var createdReminderChannel = false
    private var createdOtherChannel = false

    @Before
    fun setUp() {
        assumeTrue("系统通知与时区实验只允许 API 35+ 审查 AVD", Build.VERSION.SDK_INT >= 35 &&
            (Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")))
        originallyEnabled = MMKVStore.getBoolean(MMKVKeys.SETTINGS_REMINDER_ENABLED, false)
        restoreState = true
        originalTimeZone = TimeZone.getDefault().id
        // 权限由审查设备预先授予；测试不撤销运行自身所依附的进程权限。
        assertEquals("审查 AVD 需预先授予通知权限", PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        createdReminderChannel = notifications.getNotificationChannel(REMINDER_CHANNEL) == null
        if (createdReminderChannel) notifications.createNotificationChannel(
            NotificationChannel(REMINDER_CHANNEL, "合成提醒恢复测试", NotificationManager.IMPORTANCE_DEFAULT))
        assertTrue("审查 AVD 的应用通知总开关和提醒渠道必须预先开启", ReminderNotifier.canPostNotifications(context))
        // 不启动主 Activity，防止 onResume 的既有修复掩盖后台广播入口缺失。
        automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        settleBroadcast()
        MMKVStore.put(MMKVKeys.SETTINGS_REMINDER_ENABLED, true)
    }

    @After
    fun tearDown() {
        if (!restoreState) return
        try {
            if (notifications.getNotificationChannel(REMINDER_CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
                openChannelSettings(REMINDER_CHANNEL)
                setChannelAllowed(true)
            }
            originalTimeZone?.let(::setSystemTimeZone)
            settleBroadcast()
        } finally {
            automation.dropShellPermissionIdentity()
            automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            if (createdOtherChannel) notifications.deleteNotificationChannel(OTHER_CHANNEL)
            if (createdReminderChannel) notifications.deleteNotificationChannel(REMINDER_CHANNEL)
            MMKVStore.put(MMKVKeys.SETTINGS_REMINDER_ENABLED, originallyEnabled)
            runBlocking { ReminderScheduler.ensureScheduledAndAwait(context) }
        }
    }

    @Test
    fun systemTimeZoneBroadcastReplansWithoutReturningToActivity() {
        val previousPlan = enqueueDelayedPlan()
        val changedZone = if (originalTimeZone == "Pacific/Honolulu") "Asia/Tokyo" else "Pacific/Honolulu"
        setSystemTimeZone(changedZone)
        await("真实 TIMEZONE_CHANGED 后应替换后台规划，不能继续等待旧的 1 小时延迟；现状=${plans()}") {
            plans().any { it.id != previousPlan }
        }
        assertFalse("重排后旧计划必须终止", workManager.getWorkInfoById(previousPlan)
            .get(5, TimeUnit.SECONDS)?.state == WorkInfo.State.ENQUEUED)
    }

    @Test
    fun reopeningSystemChannelRestoresPlanAndIgnoresOtherChannelAndDisabledPreference() = runBlocking {
        openChannelSettings(REMINDER_CHANNEL)
        setChannelAllowed(false)
        await("真实渠道关闭后 canPost 必须为 false") { !ReminderNotifier.canPostNotifications(context) }
        // 复用已存在的生产边界：权限关闭确实会终止全部提醒，不用伪造失败入口。
        ReminderScheduler.ensureScheduledAndAwait(context)
        assertTrue(taggedWork().all { it.state.isFinished })
        val previousPlans = plans().map { it.id }.toSet()

        setChannelAllowed(true)
        await("渠道已在系统设置重新开启；不返回 App 也必须恢复规划，原计划=$previousPlans，现状=${plans()}") {
            plans().any { it.id !in previousPlans }
        }
        assertTrue(ReminderNotifier.canPostNotifications(context))

        // 用户在应用内关闭提醒具有最终优先级，系统打开渠道不能替用户打开业务开关。
        MMKVStore.put(MMKVKeys.SETTINGS_REMINDER_ENABLED, false)
        ReminderScheduler.ensureScheduledAndAwait(context)
        val disabledPlans = plans().map { it.id }.toSet()
        setChannelAllowed(false)
        setChannelAllowed(true)
        settleBroadcast()
        assertEquals(disabledPlans, plans().map { it.id }.toSet())
        assertTrue(taggedWork().all { it.state.isFinished })

        MMKVStore.put(MMKVKeys.SETTINGS_REMINDER_ENABLED, true)
        createdOtherChannel = notifications.getNotificationChannel(OTHER_CHANNEL) == null
        assertTrue("独占合成渠道不能已存在", createdOtherChannel)
        notifications.createNotificationChannel(NotificationChannel(OTHER_CHANNEL,
            "合成无关渠道", NotificationManager.IMPORTANCE_DEFAULT))
        openChannelSettings(OTHER_CHANNEL)
        val unchangedPlan = enqueueDelayedPlan()
        setChannelAllowed(false)
        setChannelAllowed(true)
        settleBroadcast()
        assertEquals("无关渠道的系统广播不能重排照护提醒", setOf(unchangedPlan), plans().map { it.id }.toSet())
    }

    /** 安排实际生产 Worker，只延后执行以明确观察广播是否重建任务；不需要等到提醒到期。 */
    private fun enqueueDelayedPlan(): UUID {
        val request = OneTimeWorkRequest.Builder(ReminderPlanWorker::class.java)
            .setInitialDelay(1, TimeUnit.HOURS).addTag(ReminderScheduler.TAG_REMINDER).build()
        workManager.enqueueUniqueWork(PLAN_NAME, ExistingWorkPolicy.REPLACE, request)
            .result.get(5, TimeUnit.SECONDS)
        assertEquals(setOf(request.id), plans().map { it.id }.toSet())
        return request.id
    }

    private fun plans(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(PLAN_NAME)
        .get(5, TimeUnit.SECONDS)

    private fun taggedWork(): List<WorkInfo> = workManager.getWorkInfosByTag(ReminderScheduler.TAG_REMINDER)
        .get(5, TimeUnit.SECONDS)

    private fun setSystemTimeZone(zoneId: String) {
        // SET_TIME_ZONE 仅临时采用 shell 身份；finally 必定释放，不修改 SET_TIME/自动校时开关。
        automation.adoptShellPermissionIdentity(Manifest.permission.SET_TIME_ZONE)
        try {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).setTimeZone(zoneId)
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    private fun openChannelSettings(channel: String) {
        val channelName = requireNotNull(notifications.getNotificationChannel(channel)).name.toString()
        context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channel)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        await("无法打开系统通知渠道 $channel 的开关") {
            val root = automation.rootInActiveWindow
            root != null && containsText(root, channelName) && channelSwitch() != null
        }
    }

    private fun setChannelAllowed(allowed: Boolean) {
        val node = requireNotNull(channelSwitch()) { "系统渠道开关不在当前窗口" }
        if (node.isChecked != allowed) {
            // SettingsLib 的主 Switch 可能把点击交给整行父容器，不能把只读子节点误当不可操作。
            var actionNode: AccessibilityNodeInfo? = node
            while (actionNode != null && !actionNode.isClickable) actionNode = actionNode.parent
            assertTrue("系统渠道开关应可点击", actionNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        }
        await("系统渠道开关没有切换到 $allowed") { channelSwitch()?.isChecked == allowed }
    }

    /** 读取系统实际无障碍节点，不依赖语言文案，也不通过隐藏 API 修改通知数据库。 */
    private fun channelSwitch(): AccessibilityNodeInfo? {
        val root = automation.rootInActiveWindow ?: return null
        val matches = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.packageName?.toString() == "com.android.settings" && node.isVisibleToUser &&
                node.isCheckable && node.className?.toString()?.contains("Switch") == true) matches += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(root)
        // 渠道页面还可能有振动/通知圆点开关；系统总控在这些偏好之前，取可见最上方主开关。
        return matches.minByOrNull { node -> Rect().also(node::getBoundsInScreen).top }
    }

    private fun containsText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (node.text?.toString() == text) return true
        for (index in 0 until node.childCount) {
            if (node.getChild(index)?.let { containsText(it, text) } == true) return true
        }
        return false
    }

    private fun settleBroadcast() {
        // 系统广播与 WorkManager 都是异步；短暂稳定窗口只用于证明无关事件没有新任务。
        Thread.sleep(750L)
        instrumentation.waitForIdleSync()
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 8_000L
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(50L)
        }
        assertTrue(message, condition())
    }

    private companion object {
        const val PLAN_NAME = "babycare_reminder_plan"
        const val REMINDER_CHANNEL = "record_reminder"
        const val OTHER_CHANNEL = "phase8_reminder_unrelated"
    }
}
