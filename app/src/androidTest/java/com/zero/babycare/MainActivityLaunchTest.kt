package com.zero.babycare

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** 冷启动和配置重建必须先恢复 SavedStateRegistry，再创建依赖 SavedStateHandle 的 VM。 */
@RunWith(AndroidJUnit4::class)
class MainActivityLaunchTest {
    @Test
    fun launchAndRecreateRestoreTheActivityWithoutCrashing() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertNotNull(activity.findViewById<android.view.View>(R.id.flContainer))
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertNotNull(activity.findViewById<android.view.View>(R.id.flContainer))
            }
        } finally {
            scenario.close()
        }
    }
}
