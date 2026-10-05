package com.maggie.app.screentest

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.Shadows.shadowOf

/**
 * `createComposeRule()`, usable from a unit test (MAG-242).
 *
 * The rule hosts the content in an `androidx.activity.ComponentActivity`, and
 * `ActivityScenario` will only launch an activity the package manager knows.
 * On a device that declaration comes from the `ui-test-manifest` AAR, merged into
 * the *instrumentation* manifest. A unit test has no instrumentation manifest: the
 * Android Gradle plugin points Robolectric at the app's own packaged one
 * (`test_config.properties`, `android_merged_manifest`), so an activity that only
 * exists for tests is not in it — and putting it there would ship it.
 *
 * So the activity is declared to Robolectric's package manager instead, by a rule
 * that wraps the compose rule and therefore runs before it launches anything.
 * Everything else behaves exactly like `createComposeRule()`, which is why this
 * delegates the whole interface: a test reads `compose.setContent { … }` and
 * `compose.onNodeWithTag(…)` with nothing else to know.
 *
 * ```kotlin
 * @RunWith(AndroidJUnit4::class)
 * class SomeScreenTest {
 *     @get:Rule val compose = ScreenRule()
 * }
 * ```
 *
 * `@RunWith(AndroidJUnit4::class)` is still required — it is what puts Robolectric
 * under the test at all. The API level comes from `src/test/resources/robolectric.properties`.
 */
class ScreenRule private constructor(
    private val compose: ComposeContentTestRule,
) : ComposeContentTestRule by compose {
    constructor() : this(createComposeRule())

    private val chain: TestRule = RuleChain.outerRule(DeclareHostActivity()).around(compose)

    override fun apply(base: Statement, description: Description): Statement =
        chain.apply(base, description)

    private class DeclareHostActivity : TestRule {
        override fun apply(base: Statement, description: Description): Statement =
            object : Statement() {
                override fun evaluate() {
                    val app = ApplicationProvider.getApplicationContext<Application>()
                    shadowOf(app.packageManager).addActivityIfNotPresent(
                        ComponentName(app, ComponentActivity::class.java),
                    )
                    base.evaluate()
                }
            }
    }
}
