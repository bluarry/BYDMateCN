package com.bydmatecn.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmatecn.app.helper.HelperBinderProtocol
import com.bydmatecn.app.service.UpdateChecker
import com.bydmatecn.app.util.AppStrings
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AppIdentityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Test fun applicationAndHelperHaveIndependentIdentity() {
        assertEquals("com.bydmatecn.app", BuildConfig.APPLICATION_ID)
        assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
        assertEquals("BYDMateCN", context.getString(R.string.app_name))
        assertEquals("bydmatecn_helper", HelperBinderProtocol.SERVICE_NAME)
        assertEquals("com.bydmatecn.app.helper.IHelper", HelperBinderProtocol.DESCRIPTOR)
    }
    @Test fun donorUpdateSourceCannotBeUsedEvenWhenPreferenceIsEnabled() = runBlocking {
        UpdateChecker.setAutoCheckEnabled(context, true)
        assertFalse(UpdateChecker.isAutoCheckEnabled(context))
        // Strict mock: any HTTP call would fail this test.
        val checker = UpdateChecker(mockk<OkHttpClient>(), mockk<AppStrings>())
        assertNull(checker.checkForUpdate(context))
        val failure = runCatching { checker.checkForUpdate(context, forceCheck = true) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("未配置更新源"))
    }
}
