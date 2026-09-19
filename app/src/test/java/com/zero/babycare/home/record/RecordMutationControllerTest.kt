package com.zero.babycare.home.record

import com.zero.babycare.navigation.NavTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordMutationControllerTest {
    private val target = RecordMutationController.Target("draft-a", 41, null,
        NavTarget.SleepRecord(returnTarget = NavTarget.Dashboard), NavTarget.Dashboard)

    @Test
    fun `pending write and unconsumed completion reject duplicate submissions`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = RecordMutationController(scope)
        val release = CompletableDeferred<Unit>()
        var writes = 0
        try {
            assertTrue(controller.submit(target, RecordMutationController.Kind.SAVE, 1) {
                writes++
                release.await()
            })
            // View 销毁后新观察者读取同一控制器，写锁仍存在。
            assertTrue(controller.state.value is RecordMutationController.State.Running)
            assertFalse(controller.submit(target, RecordMutationController.Kind.SAVE, 1) { writes++ })
            release.complete(Unit)
            val result = controller.state.value as RecordMutationController.State.Finished
            assertFalse(controller.submit(target, RecordMutationController.Kind.SAVE, 1) { writes++ })
            assertEquals(1, writes)
            assertTrue(controller.consume(result))
            assertFalse(controller.consume(result))
            assertFalse(controller.isPending)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `failure can retry and old result cannot consume a new completion`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = RecordMutationController(scope)
        try {
            controller.submit(target, RecordMutationController.Kind.DELETE, 17) { error("测试写入失败") }
            val failure = controller.state.value as RecordMutationController.State.Finished
            assertEquals(17, failure.errorRes)
            assertTrue(controller.consume(failure))
            assertTrue(controller.submit(target, RecordMutationController.Kind.DELETE, 17) { error("测试重试失败") })
            val retryResult = controller.state.value as RecordMutationController.State.Finished
            assertFalse(controller.consume(failure))
            assertTrue(controller.consume(retryResult))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `navigation effect belongs only to initiating draft baby and route`() {
        assertTrue(target.belongsTo("draft-a", 41, target.source))
        assertFalse(target.belongsTo("draft-b", 41, target.source))
        assertFalse(target.belongsTo("draft-a", 42, target.source))
        assertFalse(target.belongsTo("draft-a", 41, NavTarget.Dashboard))
        assertFalse(target.belongsTo("draft-a", 41, NavTarget.SleepRecord(editRecordId = 15)))
    }
}
