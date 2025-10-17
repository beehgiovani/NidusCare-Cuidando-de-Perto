package com.developersbeeh.medcontrol.ui.cycletracker

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.developersbeeh.medcontrol.data.model.DailyCycleLog
import com.developersbeeh.medcontrol.data.model.FlowIntensity
import com.developersbeeh.medcontrol.data.model.CycleSummary // <-- IMPORT ADICIONADO
import com.developersbeeh.medcontrol.data.repository.CycleRepository
import com.developersbeeh.medcontrol.util.Event
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject

data class CyclePrediction(
    val nextPeriodDate: LocalDate?,
    val fertileWindowStart: LocalDate?,
    val fertileWindowEnd: LocalDate?
)

data class CycleUiState(
    val periodDays: Set<LocalDate> = emptySet(),
    val predictedPeriodDays: Set<LocalDate> = emptySet(),
    val fertileWindowDays: Set<LocalDate> = emptySet(),
    val predictionText: String = "Carregando dados...",
    val currentPhase: String = ""
)

@HiltViewModel
class CycleTrackerViewModel @Inject constructor(
    private val cycleRepository: CycleRepository
) : ViewModel() {

    private val _dependentId = MutableStateFlow<String?>(null)
    private var allDailyLogs: Map<LocalDate, DailyCycleLog> = emptyMap()

    private val _actionFeedback = MutableLiveData<Event<String>>()
    val actionFeedback: LiveData<Event<String>> = _actionFeedback

    // ==================================================
    // ===== LIVE DATA PARA O HISTÓRICO ADICIONADO =====
    // ==================================================
    private val _cycleHistory = MutableLiveData<List<CycleSummary>>()
    val cycleHistory: LiveData<List<CycleSummary>> = _cycleHistory


    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: LiveData<CycleUiState> = _dependentId.filterNotNull().flatMapLatest { id ->
        Log.d("CycleTrackerVM", "🚀 ID do dependente alterado ou fluxo iniciado para: $id")
        val historyStartDate = LocalDate.now().minusMonths(12)
        cycleRepository.getDailyLogs(id, historyStartDate)
            .catch { e -> Log.e("CycleTrackerVM", "❌ Erro ao coletar logs do Firestore", e) }
            .map { logs ->
                Log.d("CycleTrackerVM", "🔄 Novos dados recebidos do Firestore. Total de logs: ${logs.size}")
                allDailyLogs = logs.associateBy { it.getDate() }

                // Agora também processamos o histórico aqui
                processCycleHistory(allDailyLogs.values.toList())

                // A função original continua a mesma
                processCyclesToState()
            }
    }.asLiveData()

    fun initialize(id: String) {
        if (id.isBlank()) {
            Log.e("CycleTrackerVM", "❌ ID do dependente está vazio na inicialização!")
            return
        }
        _dependentId.value = id
    }

    private fun processCyclesToState(): CycleUiState {
        val (completedCycles, _) = findCycles(allDailyLogs.values.toList())
        val prediction = calculatePrediction(completedCycles)
        val periodDays = allDailyLogs.values.filter { it.flow != FlowIntensity.NONE }.map { it.getDate() }.toSet()

        val predictedPeriodDays = prediction.nextPeriodDate?.let { start ->
            val avgDuration = calculateAveragePeriodDuration(completedCycles)
            (0 until avgDuration).map { start.plusDays(it) }.toSet()
        } ?: emptySet()

        val fertileWindowDays = prediction.fertileWindowStart?.let { start ->
            prediction.fertileWindowEnd?.let { end ->
                (0..ChronoUnit.DAYS.between(start, end)).map { start.plusDays(it) }.toSet()
            }
        } ?: emptySet()

        val predictionText = prediction.nextPeriodDate?.let {
            val daysUntil = ChronoUnit.DAYS.between(LocalDate.now(), it)
            when {
                daysUntil < 0 -> "A menstruação está atrasada."
                daysUntil == 0L -> "A menstruação deve começar hoje."
                daysUntil == 1L -> "A menstruação deve começar amanhã."
                else -> "Próxima menstruação em $daysUntil dias."
            }
        } ?: "São necessários pelo menos 2 ciclos completos para prever."

        val today = LocalDate.now()
        val currentPhase = when {
            periodDays.contains(today) -> "Fase Menstrual"
            fertileWindowDays.contains(today) -> "Janela Fértil"
            prediction.nextPeriodDate != null && prediction.fertileWindowEnd != null && today.isAfter(prediction.fertileWindowEnd) -> "Fase Lútea (TPM)"
            else -> "Fase Folicular"
        }

        Log.d("CycleTrackerVM", "✅ UI State processado. Período: ${periodDays.size} dias.")
        return CycleUiState(
            periodDays = periodDays,
            predictedPeriodDays = predictedPeriodDays,
            fertileWindowDays = fertileWindowDays,
            predictionText = predictionText,
            currentPhase = currentPhase
        )
    }

    // ========================================================
    // ===== NOVA FUNÇÃO PARA PROCESSAR O HISTÓRICO AQUI =====
    // ========================================================
    private fun processCycleHistory(logs: List<DailyCycleLog>) {
        val (completedCycles, _) = findCycles(logs)
        if (completedCycles.size < 2) {
            _cycleHistory.postValue(emptyList())
            return
        }

        val cycleStartDates = completedCycles.map { it.first().getDate() }

        val summaries = cycleStartDates.windowed(2).mapIndexed { index, dates ->
            val firstDayOfCycle = dates[0]
            val firstDayOfNextCycle = dates[1]

            val cycleLength = ChronoUnit.DAYS.between(firstDayOfCycle, firstDayOfNextCycle).toInt()
            val periodLength = completedCycles[index].size

            CycleSummary(
                startDate = firstDayOfCycle,
                cycleLength = cycleLength,
                periodLength = periodLength
            )
        }

        // Posta a lista de sumários (em ordem reversa, do mais recente para o mais antigo)
        _cycleHistory.postValue(summaries.reversed())
        Log.d("CycleTrackerVM", "📊 Histórico de ciclos processado. Total: ${summaries.size} ciclos completos.")
    }


    fun getLogForDate(date: LocalDate): DailyCycleLog {
        val currentId = _dependentId.value
        if (currentId.isNullOrBlank()) {
            Log.e("CycleTrackerVM", "❌ getLogForDate chamado antes da inicialização.")
            return DailyCycleLog()
        }
        return allDailyLogs[date] ?: DailyCycleLog(dateString = date.toString(), dependentId = currentId)
    }

    fun saveDailyLog(log: DailyCycleLog) {
        val currentId = _dependentId.value
        if (currentId.isNullOrBlank()) {
            _actionFeedback.postValue(Event("Erro: ID do dependente não definido."))
            return
        }
        viewModelScope.launch {
            cycleRepository.saveOrUpdateDailyLog(currentId, log).onSuccess {
                _actionFeedback.postValue(Event("Registro de ${log.getDate().format(DateTimeFormatter.ofPattern("dd/MM"))} salvo!"))
            }.onFailure {
                _actionFeedback.postValue(Event("Erro ao salvar o registro."))
            }
        }
    }

    private fun findCycles(logs: List<DailyCycleLog>): Pair<List<List<DailyCycleLog>>, List<DailyCycleLog>?> {
        val logsWithFlow = logs.filter { it.flow != FlowIntensity.NONE }.sortedBy { it.dateString }
        if (logsWithFlow.isEmpty()) return Pair(emptyList(), null)

        val cycles = mutableListOf<MutableList<DailyCycleLog>>()
        var currentCycle: MutableList<DailyCycleLog>? = null

        for (log in logsWithFlow) {
            if (currentCycle == null) {
                currentCycle = mutableListOf(log)
            } else {
                val lastDay = currentCycle.last().getDate()
                // Considera um novo ciclo se o intervalo entre sangramentos for maior que 10 dias (mais robusto)
                if (ChronoUnit.DAYS.between(lastDay, log.getDate()) > 10) {
                    cycles.add(currentCycle)
                    currentCycle = mutableListOf(log)
                } else {
                    currentCycle.add(log)
                }
            }
        }

        // Adiciona o último ciclo à lista, seja ele completo ou em andamento
        currentCycle?.let { cycles.add(it) }

        // A lógica de ciclo ativo foi removida daqui para simplificar e focar no histórico
        // A função agora retorna todos os "períodos" identificados.
        // A distinção entre completo e em andamento será feita por quem chama a função.
        return Pair(cycles, null)
    }


    private fun calculatePrediction(completedCycles: List<List<DailyCycleLog>>): CyclePrediction {
        if (completedCycles.size < 2) return CyclePrediction(null, null, null)

        val cycleStartDates = completedCycles.map { it.first().getDate() }
        val cycleLengths = cycleStartDates.windowed(2).map { (first, second) ->
            ChronoUnit.DAYS.between(first, second).toInt()
        }
        if (cycleLengths.isEmpty()) return CyclePrediction(null, null, null)

        val avgCycleLength = cycleLengths.average().toInt().coerceIn(15, 45)
        val lastCycleStartDate = cycleStartDates.last()

        val nextPeriodDate = lastCycleStartDate.plusDays(avgCycleLength.toLong())
        val ovulationDay = nextPeriodDate.minusDays(14)
        val fertileWindowStart = ovulationDay.minusDays(5)
        val fertileWindowEnd = ovulationDay.plusDays(1)

        return CyclePrediction(nextPeriodDate, fertileWindowStart, fertileWindowEnd)
    }

    private fun calculateAveragePeriodDuration(completedCycles: List<List<DailyCycleLog>>): Long {
        if (completedCycles.isEmpty()) return 5L
        return completedCycles.map { it.size.toLong() }.average().toLong().coerceIn(1, 15)
    }
}