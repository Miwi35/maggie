package com.maggie.app.e2e

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * Hands the JaCoCo execution data of the flow that just ended to `e2e/mobile/run.sh`
 * (« Sélection e2e par couverture », nightly only).
 *
 * Built with `-Pe2eCoverage=true` (`E2E_COVERAGE=1 e2e/mobile/build-apk.sh`), the app's
 * classes are instrumented and JaCoCo's runtime is in the APK; the counts live in memory
 * and die with the process, which every flow's `clearState` kills. So after each flow,
 * while the app still runs, run.sh sends
 *
 *     adb shell am broadcast -n com.maggie.app.e2e/com.maggie.app.e2e.CoverageDumpReceiver
 *
 * and this writes the counts to `files/e2e-coverage.ec`, *resetting* them — the next flow
 * starts from zero even if the process survives — then run.sh pulls the file with
 * `run-as`. The result data says what happened: `dumped <bytes>`, or `no-jacoco` on a
 * build without coverage, which writes nothing.
 *
 * Reflection, not a compile-time dependency: the runtime is only in the APK when
 * coverage is on, and the class must load either way. e2e flavor only, and exported
 * because the shell is the one sending.
 */
class CoverageDumpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val outcome = CoverageDump(::jacocoExecutionData).writeTo(File(context.filesDir, FILE_NAME))
        Log.i(TAG, outcome)
        setResult(if (outcome.startsWith("dumped")) 1 else 0, outcome, null)
    }

    companion object {
        const val FILE_NAME = "e2e-coverage.ec"
        private const val TAG = "E2eCoverage"

        /** JaCoCo's counts since the last call, reset; `null` when JaCoCo is not in the APK. */
        fun jacocoExecutionData(): ByteArray? {
            val rt = try {
                Class.forName("org.jacoco.agent.rt.RT")
            } catch (_: ClassNotFoundException) {
                return null
            }
            val agent = rt.getMethod("getAgent").invoke(null)
            val agentType = Class.forName("org.jacoco.agent.rt.IAgent")
            return agentType.getMethod("getExecutionData", Boolean::class.javaPrimitiveType).invoke(agent, true) as ByteArray
        }
    }
}

/** What the receiver does, apart from Android: write what the agent gives, say what happened. */
class CoverageDump(private val executionData: () -> ByteArray?) {
    fun writeTo(file: File): String {
        val data = try {
            executionData()
        } catch (error: Exception) {
            return "failed ${error::class.simpleName}: ${error.message}"
        } ?: return "no-jacoco"

        file.parentFile?.mkdirs()
        file.writeBytes(data)
        return "dumped ${data.size}"
    }
}
