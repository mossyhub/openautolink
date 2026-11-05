package com.openautolink.app.ui.testing

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.runner.Description
import org.junit.runners.model.MultipleFailureException
import org.junit.runners.model.Statement
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Launch the real Compose host in JVM tests of either build variant.
 * ui-test-manifest is debug-only and must not add a test Activity to production
 * release manifests. Register that same Activity only in this test's shadow PM,
 * before ActivityScenario runs; rendering and all assertions remain unchanged.
 */
fun createOalComposeRule(): ComposeContentTestRule {
    val compose = createComposeRule()
    return object : ComposeContentTestRule by compose {
        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate() {
                val application = RuntimeEnvironment.getApplication()
                val activity = shadowOf(application.packageManager).addActivityIfNotPresent(
                    ComponentName(application, ComponentActivity::class.java),
                )
                activity.theme = android.R.style.Theme_Material_Light_NoActionBar
                activity.exported = true
                val failures = mutableListOf<Throwable>()
                try {
                    compose.apply(base, description).evaluate()
                } catch (failure: Throwable) {
                    failures.add(failure)
                } finally {
                    // Fallback after composition/Activity disposal, before Robolectric resets
                    // this sandbox. Explicit creators also retire before removing their mocks.
                    try {
                        closeCarPlayCallControllerTestProcessAndAwait()
                    } catch (failure: Throwable) {
                        failures.add(failure)
                    }
                }
                // Preserve both the original test exception and any teardown failure.
                MultipleFailureException.assertEmpty(failures)
            }
        }
    }
}
