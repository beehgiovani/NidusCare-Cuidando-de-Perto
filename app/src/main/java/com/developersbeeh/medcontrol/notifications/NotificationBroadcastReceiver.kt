// src/main/java/com/developersbeeh/medcontrol/notifications/NotificationBroadcastReceiver.kt
package com.developersbeeh.medcontrol.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.navigation.NavDeepLinkBuilder
import com.developersbeeh.medcontrol.MainActivity
import com.developersbeeh.medcontrol.R
import com.developersbeeh.medcontrol.data.UserPreferences
import com.developersbeeh.medcontrol.data.model.Dependente
import com.developersbeeh.medcontrol.data.model.Medicamento
import com.developersbeeh.medcontrol.di.NotificationReceiverEntryPoint
import com.developersbeeh.medcontrol.data.repository.MedicationRepository
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@AndroidEntryPoint
class NotificationBroadcastReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_MEDICAMENTO_ID = "medicamento_id"
        const val EXTRA_DEPENDENT_ID = "dependent_id"
        const val EXTRA_DEPENDENT_NAME = "dependent_name"
        const val EXTRA_MEDICAMENTO_NOME = "medicamento_nome"
        const val EXTRA_MEDICAMENTO_DOSAGEM = "medicamento_dosagem"

        const val NOTIFICATION_CHANNEL_ID = "medcontrol_channel"
        const val NOTIFICATION_CHANNEL_NAME = "Lembretes de Medicamentos"
        private const val TAG = "NotificationReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Broadcast recebido!")
        val medicamentoId = intent.getStringExtra(EXTRA_MEDICAMENTO_ID)
        val dependentId = intent.getStringExtra(EXTRA_DEPENDENT_ID)

        if (medicamentoId == null || dependentId == null) {
            Log.e(TAG, "Erro: IDs não encontrados no Intent.")
            return
        }

        val fallbackMedName = intent.getStringExtra(EXTRA_MEDICAMENTO_NOME) ?: "Medicamento"
        val fallbackMedDosage = intent.getStringExtra(EXTRA_MEDICAMENTO_DOSAGEM) ?: ""
        val dependentName = intent.getStringExtra(EXTRA_DEPENDENT_NAME) ?: ""

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val hiltEntryPoint = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    NotificationReceiverEntryPoint::class.java
                )
                val firestoreRepository = hiltEntryPoint.getFirestoreRepository()
                val medicationRepository = hiltEntryPoint.getMedicationRepository()

                val medicamento = medicationRepository.getMedicamento(dependentId, medicamentoId)
                val dependente = firestoreRepository.getDependente(dependentId)

                if (medicamento != null && dependente != null) {
                    Log.d(TAG, "Dados do Firestore carregados para '${medicamento.nome}'.")
                    showNotification(context, medicamento, dependente)
                } else {
                    Log.w(TAG, "Não foi possível carregar dados do Firestore. Usando dados de fallback.")
                    val fallbackMedicamento = Medicamento(id = medicamentoId, nome = fallbackMedName, dosagem = fallbackMedDosage)
                    val fallbackDependente = Dependente(id = dependentId, nome = dependentName)
                    showNotification(context, fallbackMedicamento, fallbackDependente)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro inesperado no BroadcastReceiver: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showNotification(
        context: Context,
        medicamento: Medicamento,
        dependente: Dependente
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val userPreferences = UserPreferences(context)
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Canal para lembretes de medicamentos"
                enableVibration(true)
                vibrationPattern = longArrayOf(1000, 1000, 1000, 1000, 1000)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
                setSound(alarmSound, audioAttributes)
                setBypassDnd(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notificationId = medicamento.id.hashCode()

        val markAsTakenRequestCode = (medicamento.id + "_taken").hashCode()
        val snooze15RequestCode = (medicamento.id + "_snooze15").hashCode()
        val snooze30RequestCode = (medicamento.id + "_snooze30").hashCode()

        val markAsTakenIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_MARK_AS_TAKEN
            putExtra(NotificationActionReceiver.EXTRA_MEDICAMENTO_ID, medicamento.id)
            putExtra(NotificationActionReceiver.EXTRA_DEPENDENT_ID, dependente.id)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(NotificationActionReceiver.EXTRA_DEPENDENT_NAME, dependente.nome)
        }
        val markAsTakenPendingIntent = PendingIntent.getBroadcast(context, markAsTakenRequestCode, markAsTakenIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val snooze15minIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_SNOOZE
            putExtra(NotificationActionReceiver.EXTRA_SNOOZE_MINUTES, 15)
            putExtra(NotificationActionReceiver.EXTRA_MEDICAMENTO_ID, medicamento.id)
            putExtra(NotificationActionReceiver.EXTRA_DEPENDENT_ID, dependente.id)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(NotificationActionReceiver.EXTRA_DEPENDENT_NAME, dependente.nome)
        }
        val snooze15minPendingIntent = PendingIntent.getBroadcast(context, snooze15RequestCode, snooze15minIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val snooze30minIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_SNOOZE
            putExtra(NotificationActionReceiver.EXTRA_SNOOZE_MINUTES, 30)
            putExtra(NotificationActionReceiver.EXTRA_MEDICAMENTO_ID, medicamento.id)
            putExtra(NotificationActionReceiver.EXTRA_DEPENDENT_ID, dependente.id)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(NotificationActionReceiver.EXTRA_DEPENDENT_NAME, dependente.nome)
        }
        val snooze30minPendingIntent = PendingIntent.getBroadcast(context, snooze30RequestCode, snooze30minIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val notificationTitle = "Hora do Medicamento: ${dependente.nome}"
        val notificationText = "É hora de tomar ${medicamento.nome}."

        val bigText = StringBuilder()
        bigText.append("Nome: ${medicamento.nome}\n")
        bigText.append("Dosagem: ${medicamento.dosagem}\n")
        if (!medicamento.anotacoes.isNullOrBlank()) {
            bigText.append("Anotações: ${medicamento.anotacoes}\n")
        }

        val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(notificationTitle)
            .setContentText(notificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText.toString()))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(0, "Tomar Agora", markAsTakenPendingIntent)
            .addAction(0, "Adiar 15 min", snooze15minPendingIntent)
            .addAction(0, "Adiar 30 min", snooze30minPendingIntent)

        if ((dependente.isSelfCareProfile || !userPreferences.getIsCaregiver()) && dependente.usaAlarmeTelaCheia) {
            val alarmIntent = Intent(context, AlarmActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra(AlarmActivity.EXTRA_DEPENDENT_NAME, dependente.nome)
                putExtra(AlarmActivity.EXTRA_MEDICATION_INFO, "É hora de tomar ${medicamento.nome} ${medicamento.dosagem}")
            }
            val fullScreenPendingIntent = PendingIntent.getActivity(context, notificationId + 3, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.setFullScreenIntent(fullScreenPendingIntent, true)
            builder.setCategory(NotificationCompat.CATEGORY_ALARM)
        } else {
            val args = Bundle().apply {
                putString("dependentId", dependente.id)
                putString("dependentName", dependente.nome)
                putBoolean("isCaregiver", userPreferences.getIsCaregiver())
            }
            val pendingLaunchIntent = NavDeepLinkBuilder(context)
                .setComponentName(MainActivity::class.java)
                .setGraph(R.navigation.nav_graph)
                .setDestination(R.id.listMedicamentosFragment)
                .setArguments(args)
                .createPendingIntent()

            builder.setContentIntent(pendingLaunchIntent)
            builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
        }

        notificationManager.notify(notificationId, builder.build())
    }
}