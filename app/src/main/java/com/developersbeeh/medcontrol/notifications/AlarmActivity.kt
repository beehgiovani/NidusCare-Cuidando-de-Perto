// src/main/java/com/developersbeeh/medcontrol/notifications/AlarmActivity.kt
package com.developersbeeh.medcontrol.notifications

import android.app.KeyguardManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.developersbeeh.medcontrol.databinding.ActivityAlarmBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class AlarmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmBinding
    private var mediaPlayer: MediaPlayer? = null

    // ✅ COMPANION OBJECT ADICIONADO COM AS CONSTANTES QUE FALTAVAM
    companion object {
        const val EXTRA_DEPENDENT_NAME = "dependent_name"
        const val EXTRA_MEDICATION_INFO = "medication_info"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Configura a tela para aparecer sobre a tela de bloqueio e acender
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        }

        // Usa as constantes para receber os dados do Intent
        val dependentName = intent.getStringExtra(EXTRA_DEPENDENT_NAME) ?: "Lembrete"
        val medicationInfo = intent.getStringExtra(EXTRA_MEDICATION_INFO) ?: "Verifique seus medicamentos."

        binding.textViewDependentName.text = dependentName
        binding.textViewMedicationInfo.text = medicationInfo

        playSound()

        binding.buttonDismiss.setOnClickListener {
            stopSound()
            finish()
        }
    }

    private fun playSound() {
        try {
            val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            mediaPlayer = MediaPlayer.create(applicationContext, alarmSound)
            mediaPlayer?.isLooping = true
            mediaPlayer?.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopSound() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSound()
    }
}