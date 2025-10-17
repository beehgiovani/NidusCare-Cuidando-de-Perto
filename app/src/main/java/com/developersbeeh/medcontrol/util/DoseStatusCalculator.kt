package com.developersbeeh.medcontrol.util

import android.util.Log
import com.developersbeeh.medcontrol.data.model.DoseHistory
import com.developersbeeh.medcontrol.data.model.Medicamento
import java.time.Duration
import java.time.LocalDateTime

// ✅ CORREÇÃO: Enum renomeado para representar seu propósito (status calculado).
enum class CalculatedDoseStatus {
    ON_TIME,
    TOO_EARLY,
    TOO_LATE,
    EXTRA,
    SPORADIC
}

object DoseStatusCalculator {

    private const val ON_TIME_WINDOW_MINUTES = 30L
    private const val MAX_MATCH_MINUTES = 180L

    fun calculateDoseStatus(
        currentDose: DoseHistory,
        medicamento: Medicamento,
        allDosesForMedication: List<DoseHistory>
    ): CalculatedDoseStatus { // ✅ CORREÇÃO: Retorna o novo tipo de enum
        if (medicamento.isUsoEsporadico) {
            return CalculatedDoseStatus.SPORADIC
        }

        val doseDateTime = currentDose.timestamp
        val doseDate = doseDateTime.toLocalDate()

        if (!DoseTimeCalculator.isMedicationDay(medicamento, doseDate)) {
            Log.d("DoseStatusCalculator", "Dose de '${medicamento.nome}' em dia não agendado. Classificada como EXTRA.")
            return CalculatedDoseStatus.EXTRA
        }

        val scheduledTimesToday = medicamento.horarios.sorted()
        if (scheduledTimesToday.isEmpty()) {
            Log.d("DoseStatusCalculator", "Medicamento '${medicamento.nome}' não possui horários. Classificada como EXTRA.")
            return CalculatedDoseStatus.EXTRA
        }

        var closestScheduledDateTime: LocalDateTime? = null
        var minDifference = Long.MAX_VALUE

        for (scheduledTime in scheduledTimesToday) {
            val scheduledDateTime = LocalDateTime.of(doseDate, scheduledTime)
            val difference = Duration.between(scheduledDateTime, doseDateTime).abs().toMinutes()
            if (difference < minDifference) {
                minDifference = difference
                closestScheduledDateTime = scheduledDateTime
            }
        }

        if (closestScheduledDateTime == null || minDifference > MAX_MATCH_MINUTES) {
            Log.d("DoseStatusCalculator", "Nenhum horário agendado próximo para a dose de '${medicamento.nome}'. Classificada como EXTRA.")
            return CalculatedDoseStatus.EXTRA
        }

        val otherDosesOnSameDay = allDosesForMedication.filter {
            it.id != currentDose.id && it.timestamp.toLocalDate() == doseDate
        }

        for (otherDose in otherDosesOnSameDay) {
            val otherDifference = Duration.between(closestScheduledDateTime, otherDose.timestamp).abs().toMinutes()
            if (otherDifference < minDifference) {
                Log.d("DoseStatusCalculator", "Outra dose é uma correspondência melhor para '${medicamento.nome}' às '$closestScheduledDateTime'. Classificada como EXTRA.")
                return CalculatedDoseStatus.EXTRA
            }
        }

        val signedDifference = Duration.between(closestScheduledDateTime, doseDateTime).toMinutes()

        return when {
            signedDifference < -ON_TIME_WINDOW_MINUTES -> CalculatedDoseStatus.TOO_EARLY
            signedDifference > ON_TIME_WINDOW_MINUTES -> CalculatedDoseStatus.TOO_LATE
            else -> CalculatedDoseStatus.ON_TIME
        }
    }
}