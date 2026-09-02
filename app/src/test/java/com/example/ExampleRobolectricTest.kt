package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ui.AgentViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    assertEquals("AgentForge", appName)
  }

  @Test
  fun `agent view model can be instantiated with application context`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = AgentViewModel(app)
    assertNotNull(viewModel)
    assertNotNull(viewModel.database)
    assertNotNull(viewModel.repository)
  }
}
