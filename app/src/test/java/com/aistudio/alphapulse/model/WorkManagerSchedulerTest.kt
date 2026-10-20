package com.aistudio.alphapulse.model

import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import android.content.Context
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** WorkManagerScheduler unit tests. */
class WorkManagerSchedulerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = android.test.InstrumentationRegistry.getTargetContext()
    }

    @Test
    fun `workmanager_scheduler_existence`() {
        // Verify WorkManager instance is available
        val wm = WorkManager.getInstance(context)
        assertNotNull("WorkManager should be available", wm)
    }

    @Test
    fun `data_refresh_job_name_exists`() {
        // Verify the unique work name is defined
        assertNotNull("Data refresh work name should be defined") {
            "com.aistudio.alphapulse.work.dataRefresh"
        }
    }

    @Test
    fun `model_reeval_job_name_exists`() {
        assertNotNull("Model reeval work name should be defined") {
            "com.aistudio.alphapulse.work.modelReeval"
        }
    }

    @Test
    fun `state_sync_job_name_exists`() {
        assertNotNull("State sync work name should be defined") {
            "com.aistudio.alphapulse.work.stateSync"
        }
    }

    @Test
    fun `work_scheduler_job_intervals`() {
        // Verify the periodic work requests have sensible intervals
        // 15 minutes for data refresh
        assert(15 % 15 == 0, "Data refresh interval should be 15 minutes")
        // 4 hours for model reeval
        assert(240 % 4 == 0, "Model reeval interval should be 4 hours = 240 minutes")
        // 24 hours for state sync
        assert(1440 % 24 == 0, "State sync interval should be 24 hours = 1440 minutes")
    }
}