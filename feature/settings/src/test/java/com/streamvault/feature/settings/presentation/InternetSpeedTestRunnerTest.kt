package com.streamvault.feature.settings.presentation

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.streamvault.feature.settings.R
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test
import org.mockito.kotlin.mock

class InternetSpeedTestRunnerTest {
    private val probe = InternetDownloadSpeedProbe(OkHttpClient())

    @Test
    fun `a missing connectivity service reports a localized reason`() = runTest {
        // A plain mock returns null for getSystemService, which is how a device without the service looks.
        val runner = InternetSpeedTestRunner(mock<Context>(), probe)

        val result = runner.run()

        assertThat(result).isEqualTo(
            InternetSpeedTestResult.Error(R.string.settings_speed_test_error_network_service)
        )
    }

    @Test
    fun `the error carries a resource id so no English text can reach the screen`() {
        val error = InternetSpeedTestResult.Error(R.string.settings_speed_test_error_generic, "raw socket failure")

        assertThat(error.reasonRes).isEqualTo(R.string.settings_speed_test_error_generic)
        assertThat(error.diagnostic).isEqualTo("raw socket failure")
    }
}
