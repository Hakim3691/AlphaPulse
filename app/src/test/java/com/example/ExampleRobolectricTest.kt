package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.quant.QuantMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("AlphaPulse", appName)
  }

  @Test
  fun `test QuantMath calculations`() {
    val closes = listOf(10.0, 11.0, 12.0, 11.5, 13.0, 14.0, 13.5, 15.0, 16.0, 15.5, 17.0, 18.0, 17.5, 19.0, 20.0, 21.0)
    val rsi = QuantMath.computeRSI(closes)
    assertTrue("RSI should be between 0 and 100", rsi in 0.0..100.0)

    val ema = QuantMath.computeEMA(closes, 9)
    assertEquals(closes.size, ema.size)
  }
}
