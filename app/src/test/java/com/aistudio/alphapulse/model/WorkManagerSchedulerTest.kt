package com.aistudio.alphapulse.model

import com.aistudio.alphapulse.data.model.WorkManagerScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WorkManagerScheduler unit tests (spec §35 background jobs). */
class WorkManagerSchedulerTest {

    @Test
    fun `work_names_are_namespaced_and_unique`() {
        val names = listOf(
            WorkManagerScheduler.WORK_NAME_DATA_REFRESH,
            WorkManagerScheduler.WORK_NAME_MODEL_REEVAL,
            WorkManagerScheduler.WORK_NAME_STATE_SYNC
        )
        names.forEach { name ->
            assertTrue("'$name' must live in the app work namespace", name.startsWith("com.aistudio.alphapulse.work."))
        }
        assertEquals("Unique work names must not collide", names.size, names.toSet().size)
    }

    @Test
    fun `work_scheduler_job_intervals`() {
        assertEquals(15L, WorkManagerScheduler.DATA_REFRESH_INTERVAL_MINUTES)
        assertEquals(240L, WorkManagerScheduler.MODEL_REEVAL_INTERVAL_MINUTES)
        assertEquals(1440L, WorkManagerScheduler.STATE_SYNC_INTERVAL_MINUTES)
    }

    @Test
    fun `periodic_intervals_respect_workmanager_minimum`() {
        // WorkManager rejects periodic work under 15 minutes.
        listOf(
            WorkManagerScheduler.DATA_REFRESH_INTERVAL_MINUTES,
            WorkManagerScheduler.MODEL_REEVAL_INTERVAL_MINUTES,
            WorkManagerScheduler.STATE_SYNC_INTERVAL_MINUTES
        ).forEach { minutes ->
            assertTrue("Interval $minutes must be at least 15 minutes", minutes >= 15L)
        }
    }
}
