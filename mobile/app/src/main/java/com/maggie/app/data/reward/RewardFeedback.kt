package com.maggie.app.data.reward

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.maggie.app.R
import com.maggie.app.data.model.Reward
import com.maggie.app.data.repository.TaskRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** What a task done gives besides the picture (MAG-508): a short buzz and a little sound. */
interface RewardFeedback {
    fun vibrate()

    fun playSound()
}

/** A 40 ms buzz and `maggie_bravo`, not Maggie's chime; no sound unless the ringer is on. */
class AndroidRewardFeedback(private val context: Context) : RewardFeedback {

    override fun vibrate() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    // Silent or vibrate mode: the phone was asked to keep quiet.
    override fun playSound() {
        runCatching {
            val audio = context.getSystemService(AudioManager::class.java) ?: return
            if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val player = MediaPlayer.create(context, R.raw.maggie_bravo, attributes, AudioManager.AUDIO_SESSION_ID_GENERATE) ?: return
            player.setOnCompletionListener { it.release() }
            player.start()
        }
    }

    private companion object {
        const val VIBRATION_MS = 40L
    }
}

/** A reward to show, and whether the user lets it move ([animate]); the system's own switch is the screen's to read. */
data class Celebration(
    val reward: Reward,
    val animate: Boolean,
)

/**
 * The reward of a task just ticked (MAG-508), read once the tick answered, with the buzz and the
 * sound the user's settings allow. Settings that cannot be read keep the phone quiet: a switch
 * turned off is never overridden by a network failure.
 */
class TaskRewards(
    private val taskRepository: TaskRepository,
    private val feedback: RewardFeedback,
) {
    /** The reward [taskId] earned, its feedback played; `null` when it earned none or cannot be read. */
    suspend fun celebrate(taskId: String): Celebration? = coroutineScope {
        val settings = async { taskRepository.getTaskSettings().getOrNull() }
        val reward = taskRepository.getTaskReward(taskId).getOrNull()
        val prefs = settings.await()
        if (reward == null) return@coroutineScope null
        if (prefs?.rewardVibration == true) feedback.vibrate()
        if (prefs?.rewardSound == true) feedback.playSound()
        Celebration(reward = reward, animate = prefs?.rewardAnimation ?: true)
    }
}
