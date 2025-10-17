package com.developersbeeh.medcontrol.util

import com.developersbeeh.medcontrol.data.model.DoseHistory
import com.developersbeeh.medcontrol.data.model.FrequenciaTipo
import com.developersbeeh.medcontrol.data.model.Medicamento
import com.developersbeeh.medcontrol.data.model.TipoMedicamento
import com.developersbeeh.medcontrol.ui.caregiver.ProximaDoseStatus
import com.developersbeeh.medcontrol.ui.listmedicamentos.AdherenceStatus
import com.developersbeeh.medcontrol.ui.listmedicamentos.MedicamentoStatus
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object DoseTimeCalculator {

    private const val LATE_THRESHOLD_MINUTES = 30L
    private const val VERY_LATE_HOURS = 2L
    private const val EARLY_THRESHOLD_MINUTES = 60L
    private const val ON_TIME_WINDOW_MINUTES = 15L

    fun isDoseVeryLate(medicamento: Medicamento): Boolean {
        val lastScheduled = getLastScheduledDoseBeforeNow(medicamento) ?: return false
        val minutesLate = Duration.between(lastScheduled, LocalDateTime.now()).toMinutes()

        if (medicamento.horarios.size < 2) {
            return minutesLate > VERY_LATE_HOURS * 60
        }

        val nextScheduled = getNextScheduledDoseAfter(medicamento, lastScheduled)
        val intervalMinutes = Duration.between(lastScheduled, nextScheduled).toMinutes()

        if (intervalMinutes > 0) {
            return minutesLate > (intervalMinutes / 2)
        }

        return minutesLate > VERY_LATE_HOURS * 60
    }

    fun getStatusForMedicamento(medicamento: Medicamento, allDoseHistory: List<DoseHistory>): Pair<MedicamentoStatus, String> {
        val today = LocalDate.now()
        val treatmentEnd = if (medicamento.isUsoContinuo || medicamento.duracaoDias <= 0) null else medicamento.dataInicioTratamento.plusDays(medicamento.duracaoDias.toLong() - 1)

        if (treatmentEnd != null && today.isAfter(treatmentEnd)) return Pair(MedicamentoStatus.FINALIZADO, "Tratamento finalizado")
        if (medicamento.isUsoEsporadico) return Pair(MedicamentoStatus.ESPORADICO, "Uso quando necessário")
        if (medicamento.isPaused) return Pair(MedicamentoStatus.PAUSADO, "Lembretes pausados")
        if (!medicamento.usaNotificacao || medicamento.horarios.isEmpty()) return Pair(MedicamentoStatus.SEM_NOTIFICACAO, "Notificações desativadas")

        val now = LocalDateTime.now()
        val lastScheduledDose = getLastScheduledDoseBeforeNow(medicamento)

        if (lastScheduledDose != null && now.isAfter(lastScheduledDose.plusMinutes(LATE_THRESHOLD_MINUTES))) {

            // ✅ CORREÇÃO: Ignora doses agendadas no passado no dia da criação do tratamento.
            val creationDateTime = medicamento.dataCriacaoLocalDateTime
            if (lastScheduledDose.toLocalDate() == creationDateTime.toLocalDate() && lastScheduledDose.isBefore(creationDateTime)) {
                // A última dose agendada foi antes da criação do medicamento hoje, então não está "atrasada".
                // A lógica continua para encontrar a próxima dose válida.
            } else {
                val doseFoiRegistrada = allDoseHistory.any {
                    it.medicamentoId == medicamento.id &&
                            Duration.between(lastScheduledDose, it.timestamp).abs().toMinutes() <= LATE_THRESHOLD_MINUTES
                }
                if (!doseFoiRegistrada) {
                    return Pair(MedicamentoStatus.ATRASADO, "Dose atrasada!")
                }
            }
        }

        val nextDoseTime = calculateNextDoseTime(medicamento, allDoseHistory)
        return when {
            nextDoseTime == null -> Pair(MedicamentoStatus.FINALIZADO, "Doses do dia concluídas")
            else -> {
                val duration = Duration.between(now, nextDoseTime)
                val hours = duration.toHours()
                val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
                val statusText = if (duration.toDays() < 1 && nextDoseTime.toLocalDate() == now.toLocalDate()) {
                    val minutes = duration.toMinutes() % 60
                    "Próxima dose em ${hours}h ${minutes}m"
                } else if (nextDoseTime.toLocalDate() == now.toLocalDate().plusDays(1)) {
                    "Próxima dose amanhã, às ${nextDoseTime.toLocalTime().format(timeFormatter)}"
                } else {
                    "Próxima dose em ${nextDoseTime.format(DateTimeFormatter.ofPattern("dd/MM"))}, às ${nextDoseTime.toLocalTime().format(timeFormatter)}"
                }
                Pair(MedicamentoStatus.PROXIMA_DOSE, statusText)
            }
        }
    }

    fun calculateNextDoseTime(medicamento: Medicamento, doseHistory: List<DoseHistory>, fromDate: LocalDate = LocalDate.now()): LocalDateTime? {
        val today = fromDate
        val treatmentEnd = if (medicamento.isUsoContinuo || medicamento.duracaoDias <= 0) null else medicamento.dataInicioTratamento.plusDays(medicamento.duracaoDias - 1L)

        if (treatmentEnd != null && today.isAfter(treatmentEnd)) return null
        if (medicamento.horarios.isEmpty()) return null

        return when (medicamento.frequenciaTipo) {
            FrequenciaTipo.DIARIA -> calculateNextDoseForDaily(medicamento, fromDate)
            FrequenciaTipo.SEMANAL -> calculateNextDoseForWeekly(medicamento, fromDate)
            FrequenciaTipo.INTERVALO_DIAS -> calculateNextDoseForInterval(medicamento, fromDate)
        }
    }

    fun getMissedDoseMedications(medicamentos: List<Medicamento>, allDoseHistory: List<DoseHistory>): List<Medicamento> {
        return medicamentos
            .filter { med -> getStatusForMedicamento(med, allDoseHistory).first == MedicamentoStatus.ATRASADO }
            .distinctBy { it.id }
    }

    fun calculateExpectedDosesForPeriod(medicamentos: List<Medicamento>, startDate: LocalDate, endDate: LocalDate): Int {
        return medicamentos.sumOf { med ->
            calculateExpectedDosesForMedicationInRange(med, startDate, endDate)
        }
    }

    fun calculateExpectedDosesForMedicationInRange(medication: Medicamento, periodStart: LocalDate, periodEnd: LocalDate): Int {
        if (medication.isUsoEsporadico || medication.horarios.isEmpty() || medication.isPaused) return 0
        val treatmentStart = medication.dataInicioTratamento
        val treatmentEnd = if (!medication.isUsoContinuo && medication.duracaoDias > 0) {
            treatmentStart.plusDays(medication.duracaoDias.toLong() - 1)
        } else null
        val effectiveStart = if (treatmentStart.isAfter(periodStart)) treatmentStart else periodStart
        val effectiveEnd = when {
            treatmentEnd == null -> periodEnd
            treatmentEnd.isBefore(periodEnd) -> treatmentEnd
            else -> periodEnd
        }
        if (effectiveStart.isAfter(effectiveEnd)) return 0
        var totalExpected = 0
        var currentDate = effectiveStart
        while (!currentDate.isAfter(effectiveEnd)) {
            if (isMedicationDay(medication, currentDate)) {
                totalExpected += medication.horarios.size
            }
            currentDate = currentDate.plusDays(1)
        }
        return totalExpected
    }

    private fun calculateNextDoseForDaily(medicamento: Medicamento, fromDate: LocalDate): LocalDateTime? {
        val now = LocalDateTime.now()
        val today = fromDate
        val sortedHorarios = medicamento.horarios.sorted()

        if (isMedicationDay(medicamento, today)) {
            val proximoHorarioHoje = sortedHorarios.firstOrNull { it.isAfter(now.toLocalTime()) || today != now.toLocalDate() }
            if (proximoHorarioHoje != null) {
                return LocalDateTime.of(today, proximoHorarioHoje)
            }
        }

        for (i in 1..365) {
            val nextDay = today.plusDays(i.toLong())
            if (isMedicationDay(medicamento, nextDay)) {
                return LocalDateTime.of(nextDay, sortedHorarios.first())
            }
        }
        return null
    }

    private fun calculateNextDoseForWeekly(medicamento: Medicamento, fromDate: LocalDate): LocalDateTime? {
        if (medicamento.diasSemana.isEmpty()) return null
        val now = LocalDateTime.now()
        val sortedHorarios = medicamento.horarios.sorted()

        for (i in 0..14) {
            val currentDate = fromDate.plusDays(i.toLong())
            val dayOfWeekValue = currentDate.dayOfWeek.value

            if (medicamento.diasSemana.contains(dayOfWeekValue)) {
                if (currentDate == now.toLocalDate()) {
                    val proximoHorarioHoje = sortedHorarios.firstOrNull { it.isAfter(now.toLocalTime()) }
                    if (proximoHorarioHoje != null) return LocalDateTime.of(currentDate, proximoHorarioHoje)
                } else if (currentDate.isAfter(now.toLocalDate())) {
                    return LocalDateTime.of(currentDate, sortedHorarios.first())
                }
            }
        }
        return null
    }

    private fun calculateNextDoseForInterval(medicamento: Medicamento, fromDate: LocalDate): LocalDateTime? {
        if (medicamento.frequenciaValor <= 0) return null
        val now = LocalDateTime.now()
        val today = fromDate
        val startDate = medicamento.dataInicioTratamento
        val sortedHorarios = medicamento.horarios.sorted()

        if (today.isBefore(startDate)) {
            return LocalDateTime.of(startDate, sortedHorarios.first())
        }

        if (isMedicationDay(medicamento, today)) {
            val proximoHorarioHoje = sortedHorarios.firstOrNull { it.isAfter(now.toLocalTime()) || today != now.toLocalDate() }
            if (proximoHorarioHoje != null) return LocalDateTime.of(today, proximoHorarioHoje)
        }

        var nextDoseDate = today.plusDays(1)
        while (true) {
            if (isMedicationDay(medicamento, nextDoseDate)) {
                return LocalDateTime.of(nextDoseDate, sortedHorarios.first())
            }
            nextDoseDate = nextDoseDate.plusDays(1)
        }
    }

    private fun getNextScheduledDoseAfter(medicamento: Medicamento, referenceDateTime: LocalDateTime): LocalDateTime {
        val nextTimeToday = medicamento.horarios.sorted().firstOrNull { it.isAfter(referenceDateTime.toLocalTime()) }
        if (nextTimeToday != null && isMedicationDay(medicamento, referenceDateTime.toLocalDate())) {
            return LocalDateTime.of(referenceDateTime.toLocalDate(), nextTimeToday)
        }
        var nextValidDay = referenceDateTime.toLocalDate().plusDays(1)
        while (!isMedicationDay(medicamento, nextValidDay)) {
            nextValidDay = nextValidDay.plusDays(1)
        }
        return LocalDateTime.of(nextValidDay, medicamento.horarios.sorted().first())
    }

    fun calcularProximaDoseGeral(medicamentos: List<Medicamento>, doses: List<DoseHistory>): Pair<String, ProximaDoseStatus> {
        val now = LocalDateTime.now()
        val dateFormatter = DateTimeFormatter.ofPattern("dd/MM")
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        val medicamentosAtivos = medicamentos.filter { !it.isPaused && it.usaNotificacao && it.horarios.isNotEmpty() && !it.isUsoEsporadico }
        if (medicamentosAtivos.isEmpty()) {
            return Pair("Nenhum medicamento ativo", ProximaDoseStatus.NENHUMA)
        }

        val missedMeds = getMissedDoseMedications(medicamentosAtivos, doses)
        if (missedMeds.isNotEmpty()) {
            return Pair("Doses atrasadas!", ProximaDoseStatus.ATRASADA)
        }

        val proximaDoseMaisCedo = medicamentosAtivos
            .asSequence()
            .mapNotNull { medicamento -> calculateNextDoseTime(medicamento, doses) }
            .minOrNull()

        if (proximaDoseMaisCedo == null) {
            return Pair("Doses do dia concluídas", ProximaDoseStatus.CONCLUIDA)
        }

        val texto = proximaDoseMaisCedo.let { time ->
            val date = when (time.toLocalDate()) {
                now.toLocalDate() -> "Hoje"
                now.toLocalDate().plusDays(1) -> "Amanhã"
                else -> time.toLocalDate().format(dateFormatter)
            }
            "Próxima dose: $date, às ${time.toLocalTime().format(timeFormatter)}"
        }
        return Pair(texto, ProximaDoseStatus.EM_DIA)
    }

    fun calcularDosesDeHoje(medicamentos: List<Medicamento>, doseHistory: List<DoseHistory>): Pair<Int, Int> {
        val today = LocalDate.now()
        val dosesTomadasHoje = doseHistory.count { it.timestamp.toLocalDate() == today }
        val dosesEsperadasHoje = calculateExpectedDosesForPeriod(medicamentos.filter { !it.isUsoEsporadico }, today, today)
        return Pair(dosesTomadasHoje, dosesEsperadasHoje)
    }

    fun calcularAderencia7dias(medicamentos: List<Medicamento>, doses: List<DoseHistory>): Int {
        val scheduledMeds = medicamentos.filter { !it.isUsoEsporadico }
        if (scheduledMeds.isEmpty()) return -1
        val today = LocalDate.now()
        val startDate = today.minusDays(6)
        val dosesNoPeriodo = doses.count {
            val doseDate = it.timestamp.toLocalDate()
            !doseDate.isBefore(startDate) && !doseDate.isAfter(today)
        }
        val dosesEsperadasNoPeriodo = calculateExpectedDosesForPeriod(scheduledMeds, startDate, today)
        return if (dosesEsperadasNoPeriodo > 0) ((dosesNoPeriodo.toDouble() / dosesEsperadasNoPeriodo) * 100).toInt().coerceAtMost(100) else -1
    }

    fun calculateAdherenceForToday(medicamento: Medicamento, allDoseHistory: List<DoseHistory>): Triple<Int, Int, AdherenceStatus> {
        if (medicamento.isUsoEsporadico) return Triple(0, 0, AdherenceStatus.NORMAL)
        val today = LocalDate.now()
        if (today.isBefore(medicamento.dataInicioTratamento) || medicamento.horarios.isEmpty()) return Triple(0, 0, AdherenceStatus.NORMAL)
        val expectedDosesToday = calculateExpectedDosesForMedicationInRange(medicamento, today, today)
        val takenDosesToday = allDoseHistory.count { it.medicamentoId == medicamento.id && it.timestamp.toLocalDate() == today }
        val status = if (takenDosesToday > expectedDosesToday && expectedDosesToday > 0) AdherenceStatus.OVERDOSE else AdherenceStatus.NORMAL
        return Triple(takenDosesToday, expectedDosesToday, status)
    }

    fun calculateLocais(medicamento: Medicamento, allDoseHistory: List<DoseHistory>): Pair<String?, String?> {
        if (medicamento.tipo == TipoMedicamento.ORAL || medicamento.locaisDeAplicacao.isEmpty()) return Pair(null, null)
        val historicoDoMedicamento = allDoseHistory.filter { it.medicamentoId == medicamento.id && it.localDeAplicacao != null }
        val ultimoLocal = historicoDoMedicamento.maxByOrNull { it.timestamp }?.localDeAplicacao
        var proximoLocal: String? = null
        if (ultimoLocal != null) {
            val ultimoIndex = medicamento.locaisDeAplicacao.indexOf(ultimoLocal)
            if (ultimoIndex != -1) {
                proximoLocal = medicamento.locaisDeAplicacao[(ultimoIndex + 1) % medicamento.locaisDeAplicacao.size]
            }
        } else {
            proximoLocal = medicamento.locaisDeAplicacao.firstOrNull()
        }
        return Pair(ultimoLocal, proximoLocal)
    }

    fun getLastScheduledDoseBeforeNow(medicamento: Medicamento): LocalDateTime? {
        if (medicamento.horarios.isEmpty()) return null
        val now = LocalDateTime.now()
        val sortedHorarios = medicamento.horarios.sortedDescending()
        for (i in 0..7) {
            val checkDate = now.toLocalDate().minusDays(i.toLong())
            if (isMedicationDay(medicamento, checkDate)) {
                val timesToCheck = if (checkDate == now.toLocalDate()) {
                    sortedHorarios.filter { it.isBefore(now.toLocalTime()) }
                } else {
                    sortedHorarios
                }
                if (timesToCheck.isNotEmpty()) return LocalDateTime.of(checkDate, timesToCheck.first())
            }
        }
        return null
    }

    fun isMedicationDay(med: Medicamento, date: LocalDate): Boolean {
        if (date.isBefore(med.dataInicioTratamento)) return false
        return when (med.frequenciaTipo) {
            FrequenciaTipo.DIARIA -> true
            FrequenciaTipo.SEMANAL -> med.diasSemana.contains(date.dayOfWeek.value)
            FrequenciaTipo.INTERVALO_DIAS -> {
                if (med.frequenciaValor <= 0) false
                else ChronoUnit.DAYS.between(med.dataInicioTratamento, date) % med.frequenciaValor.toLong() == 0L
            }
        }
    }

    fun getNextOrLastDoseTime(medicamento: Medicamento): LocalDateTime? {
        val nextDose = calculateNextDoseTime(medicamento, emptyList())
        if (nextDose != null) {
            return nextDose
        }
        return getLastScheduledDoseBeforeNow(medicamento)
    }
}