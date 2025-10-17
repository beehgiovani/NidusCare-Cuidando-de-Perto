// src/main/java/com/developersbeeh/medcontrol/ui/dashboard/DashboardDependenteViewModel.kt
package com.developersbeeh.medcontrol.ui.dashboard

import android.util.Log
import androidx.lifecycle.*
import com.developersbeeh.medcontrol.R
import com.developersbeeh.medcontrol.data.UserPreferences
import com.developersbeeh.medcontrol.data.model.*
import com.developersbeeh.medcontrol.data.repository.*
import com.developersbeeh.medcontrol.util.AgeCalculator
import com.developersbeeh.medcontrol.util.DoseTimeCalculator
import com.developersbeeh.medcontrol.util.Event
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import javax.inject.Inject
import kotlin.math.abs

private const val TAG = "DashboardDependenteVM"

// Data classes para o estado da UI
data class ImcResult(val value: Float, val classification: String, val color: Int)
data class WeightGoalStatus(val progress: Int, val progressText: String, val color: Int)
data class WeeklySummary(
    val mediaDiariaAguaMl: Double,
    val totalMinutosAtividade: Int
)

// Constantes para ações que não são de navegação
const val ACTION_ID_VIEW_CREDENTIALS = -100
const val ACTION_ID_EMERGENCY = -101
const val ACTION_ID_TOGGLE_ALARM = -102
const val ACTION_ID_DELETE_DEPENDENT = -103
const val ACTION_ID_SHOW_ANALYSIS_DIALOG = -104

@HiltViewModel
class DashboardDependenteViewModel @Inject constructor(
    private val firestoreRepository: FirestoreRepository,
    private val userRepository: UserRepository,
    private val medicationRepository: MedicationRepository,
    private val scheduleRepository: ScheduleRepository,
    private val reminderRepository: ReminderRepository,
    private val userPreferences: UserPreferences
) : ViewModel() {

    private val _dependentId = MutableLiveData<String>()
    val dependente = MutableLiveData<Dependente?>()
    private val _imcResult = MutableLiveData<ImcResult?>()
    val imcResult: LiveData<ImcResult?> = _imcResult
    private val _weightGoalStatus = MutableLiveData<WeightGoalStatus?>()
    val weightGoalStatus: LiveData<WeightGoalStatus?> = _weightGoalStatus
    private val _summaryText = MutableLiveData<String>()
    val summaryText: LiveData<String> = _summaryText
    private val _weeklySummaryState = MutableLiveData<WeeklySummary?>()
    val weeklySummaryState: LiveData<WeeklySummary?> = _weeklySummaryState
    private val _careManagementCategories = MutableLiveData<List<DashboardCategory>>()
    val careManagementCategories: LiveData<List<DashboardCategory>> = _careManagementCategories
    private val _healthDataCategories = MutableLiveData<List<DashboardCategory>>()
    val healthDataCategories: LiveData<List<DashboardCategory>> = _healthDataCategories
    private val _profileManagementCategories = MutableLiveData<List<DashboardCategory>>()
    val profileManagementCategories: LiveData<List<DashboardCategory>> = _profileManagementCategories
    private val _advancedToolsCategories = MutableLiveData<List<DashboardCategory>>()
    val advancedToolsCategories: LiveData<List<DashboardCategory>> = _advancedToolsCategories
    private val _actionFeedback = MutableLiveData<Event<String>>()
    val actionFeedback: LiveData<Event<String>> = _actionFeedback
    val showAnalysisPromptDialog = MutableLiveData<Event<Unit>>()
    private val _showCredentials = MutableLiveData<Event<Pair<String, String>>>()
    val showCredentials: LiveData<Event<Pair<String, String>>> = _showCredentials
    private val _showDeleteConfirmation = MutableLiveData<Event<Unit>>()
    val showDeleteConfirmation: LiveData<Event<Unit>> = _showDeleteConfirmation
    private val _showEmergencyConfirmation = MutableLiveData<Event<Unit>>()
    val showEmergencyConfirmation: LiveData<Event<Unit>> = _showEmergencyConfirmation
    private val _remindersSummary = MutableLiveData<List<Reminder>>()
    val remindersSummary: LiveData<List<Reminder>> = _remindersSummary
    private val _missedDoseAlert = MutableLiveData<List<String>>()
    val missedDoseAlert: LiveData<List<String>> = _missedDoseAlert

    private val _showLoading = MutableLiveData<Event<String>>()
    val showLoading: LiveData<Event<String>> = _showLoading

    private val _hideLoading = MutableLiveData<Event<Unit>>()
    val hideLoading: LiveData<Event<Unit>> = _hideLoading

    fun initialize(id: String) {
        if (_dependentId.value == id) return
        _dependentId.value = id

        viewModelScope.launch {
            firestoreRepository.listenToDependentProfile(id).collectLatest { dep ->
                if (dep != null) {
                    dependente.postValue(dep)
                    buildAllCategories(dep)
                    calculateImc(dep.peso, dep.altura)
                    loadDynamicData(dep)
                } else {
                    Log.e(TAG, "Dependente com ID $id não foi encontrado.")
                }
            }
        }
    }

    private fun loadDynamicData(dep: Dependente) {
        viewModelScope.launch {
            val today = LocalDate.now()
            val sevenDaysAgo = today.minusDays(6)

            @Suppress("UNCHECKED_CAST")
            val flows = listOf(
                medicationRepository.getMedicamentos(dep.id),
                medicationRepository.getDoseHistory(dep.id),
                scheduleRepository.getSchedules(dep.id),
                firestoreRepository.getHidratacaoHistory(dep.id, sevenDaysAgo,  sevenDaysAgo),
                firestoreRepository.getAtividadeFisicaHistory(dep.id, sevenDaysAgo, sevenDaysAgo),
                firestoreRepository.getHealthNotes(dep.id),
                reminderRepository.getReminders(dep.id)
            )

            combine(flows) { dataArray ->
                val meds = dataArray[0] as? List<Medicamento> ?: emptyList()
                val doses = dataArray[1] as? List<DoseHistory> ?: emptyList()
                val schedules = dataArray[2] as? List<AgendamentoSaude> ?: emptyList()
                val hydration = dataArray[3] as? List<Hidratacao> ?: emptyList()
                val activities = dataArray[4] as? List<AtividadeFisica> ?: emptyList()
                val healthNotes = dataArray[5] as? List<HealthNote> ?: emptyList()
                val reminders = dataArray[6] as? List<Reminder> ?: emptyList()

                val missedMeds = DoseTimeCalculator.getMissedDoseMedications(meds, doses)
                _missedDoseAlert.postValue(missedMeds.map { it.nome })

                processAllData(dep, meds, doses, schedules, hydration, activities, healthNotes, reminders)
            }.catch { e ->
                Log.e(TAG, "Erro ao combinar fluxos de dados dinâmicos: ", e)
            }.collect()
        }
    }

    private fun processAllData(
        dep: Dependente,
        meds: List<Medicamento>,
        doses: List<DoseHistory>,
        schedules: List<AgendamentoSaude>,
        hidratacao: List<Hidratacao>,
        atividades: List<AtividadeFisica>,
        healthNotes: List<HealthNote>,
        reminders: List<Reminder>
    ) {
        calculateWeightGoalStatus(dep, healthNotes)
        createDailySummary(dep.nome, meds, doses, schedules)
        createWeeklySummary(hidratacao, atividades)

        val activeReminders = reminders.filter { it.isActive }.sortedBy { it.time }
        _remindersSummary.postValue(activeReminders)
    }

    private fun createDailySummary(dependentName: String, medicamentos: List<Medicamento>, doses: List<DoseHistory>, schedules: List<AgendamentoSaude>) {
        val now = LocalDateTime.now()
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        val nextDose = medicamentos
            .filter { !it.isPaused && !it.isUsoEsporadico && it.horarios.isNotEmpty() }
            .mapNotNull { med -> DoseTimeCalculator.calculateNextDoseTime(med, doses) }
            .minOrNull()

        val nextSchedule = schedules
            .filter { it.timestamp.isAfter(now) }
            .minByOrNull { it.timestamp }

        var summaryText = "Tenha um ótimo dia, $dependentName!"
        var nextEventTime: LocalDateTime? = null

        if (nextDose != null) {
            nextEventTime = nextDose
            summaryText = "Próximo medicamento às ${nextDose.format(timeFormatter)}."
        }

        if (nextSchedule != null) {
            if (nextEventTime == null || nextSchedule.timestamp.isBefore(nextEventTime)) {
                summaryText = "Próximo compromisso: ${nextSchedule.titulo} às ${nextSchedule.timestamp.format(timeFormatter)}."
            }
        }
        _summaryText.postValue(summaryText)
    }

    private fun createWeeklySummary(hydrationRecords: List<Hidratacao>, activityRecords: List<AtividadeFisica>) {
        if (hydrationRecords.isEmpty() && activityRecords.isEmpty()) {
            _weeklySummaryState.postValue(null)
            return
        }
        val totalMl = hydrationRecords.sumOf { it.quantidadeMl }
        val mediaDiariaMl = if (hydrationRecords.isNotEmpty()) totalMl.toDouble() / 7.0 else 0.0
        val totalMinutos = activityRecords.sumOf { it.duracaoMinutos }
        _weeklySummaryState.postValue(WeeklySummary(mediaDiariaMl, totalMinutos))
    }

    private fun buildAllCategories(dependente: Dependente) {
        _careManagementCategories.value = buildCareManagementCategories(dependente)
        _healthDataCategories.value = buildHealthDataCategories(dependente)
        _profileManagementCategories.value = buildProfileManagementCategories(dependente)
        _advancedToolsCategories.value = buildAdvancedToolsCategories()
    }

    private fun buildCareManagementCategories(dependente: Dependente): List<DashboardCategory> {
        val list = mutableListOf<DashboardCategory>()
        if (!userPreferences.getIsCaregiver()) {
            list.add(DashboardCategory("Emergência", R.drawable.ic_sos, ACTION_ID_EMERGENCY))
        }
        list.add(DashboardCategory("Medicamentos", R.drawable.ic_medicamentoss, R.id.listMedicamentosFragment))
        list.add(DashboardCategory("Farmácia Express", R.drawable.ic_local_pharmacy, R.id.action_global_to_pharmacySelectionFragment))
        list.add(DashboardCategory("Diário de Bem-Estar", R.drawable.ic_bem_estar, R.id.wellbeingDiaryFragment))
        list.add(DashboardCategory("Agenda", R.drawable.ic_agenda, R.id.healthScheduleFragment))
        list.add(DashboardCategory("Anotações", R.drawable.ic_anocacoes, R.id.healthNotesFragment))
        list.add(DashboardCategory("Lembretes", R.drawable.ic_alarm, R.id.action_global_to_reminders))
        list.add(DashboardCategory("Caixa de Remédios", R.drawable.ic_farmacinha, R.id.farmacinhaFragment))
        return list.distinctBy { it.actionId }
    }

    private fun buildHealthDataCategories(dependente: Dependente): List<DashboardCategory> {
        val list = mutableListOf<DashboardCategory>()
        list.add(DashboardCategory("Linha do Tempo", R.drawable.ic_timlineic, R.id.timelineFragment))
        list.add(DashboardCategory("Histórico de Doses", R.drawable.ic_historico_de_doses, R.id.action_global_to_doseHistoryFragment))
        list.add(DashboardCategory("Documentos", R.drawable.ic_documentosg, R.id.healthDocumentsFragment))
        list.add(DashboardCategory("Central Educativa", R.drawable.ic_educacao, R.id.action_global_to_educationCenterFragment))
        list.add(DashboardCategory("Conquistas", R.drawable.ic_conquista, R.id.action_global_to_achievementsFragment))
        val age = AgeCalculator.calculateAge(dependente.dataDeNascimento)
        if (age != null && age <= 18) {
            list.add(DashboardCategory("Vacinas", R.drawable.ic_vacinacao, R.id.action_global_to_vaccinationCardFragment))
        }
        if (age != null && age >= 60) {
            list.add(DashboardCategory("Cuidado Idoso", R.drawable.ic_seniorcuidado, R.id.action_global_to_geriatricCareFragment))
        }
        if (dependente.sexo == Sexo.FEMININO.name) {
            list.add(DashboardCategory("Ciclo", R.drawable.ic_menstrual, R.id.action_global_to_cycleTrackerFragment))
        }
        return list.distinctBy { it.actionId }
    }

    private fun buildAdvancedToolsCategories(): List<DashboardCategory> {
        val list = mutableListOf<DashboardCategory>()
        val isCaregiver = userPreferences.getIsCaregiver()
        val isPremium = userPreferences.isPremium()
        if (isCaregiver) {
            if (isPremium) {
                list.add(DashboardCategory("Chat IA", R.drawable.ic_conversa, R.id.action_global_to_chatFragment))
                list.add(DashboardCategory("Análise Preditiva", R.drawable.ic_cerebro, ACTION_ID_SHOW_ANALYSIS_DIALOG))
                list.add(DashboardCategory("Escanear Receita", R.drawable.ic_scanear, R.id.action_global_to_prescriptionScannerFragment))
                list.add(DashboardCategory("Relatórios", R.drawable.ic_relatorios, R.id.reportsFragment))
            } else {
                list.add(DashboardCategory("Funções Premium", R.drawable.ic_premiumc, R.id.action_global_to_premiumPlansFragment))
                list.add(DashboardCategory("Relatórios", R.drawable.ic_relatorios, R.id.reportsFragment))
            }
        }
        return list
    }

    private fun buildProfileManagementCategories(dependente: Dependente): List<DashboardCategory> {
        val list = mutableListOf<DashboardCategory>()
        if (userPreferences.getIsCaregiver()) {
            list.add(DashboardCategory("Metas", R.drawable.ic_metas, R.id.action_global_to_healthGoalsFragment))
            list.add(DashboardCategory("Editar Perfil", R.drawable.ic_editarperfil, R.id.action_global_to_addEditDependentFragment))
            list.add(DashboardCategory("Cuidadores", R.drawable.ic_convdependente, R.id.action_global_to_manageCaregiversFragment))
            list.add(DashboardCategory("Credenciais", R.drawable.ic_credenciais, ACTION_ID_VIEW_CREDENTIALS))
            list.add(DashboardCategory("Gerenciar Arq.", R.drawable.ic_gerenciar, R.id.action_global_to_archivedMedicationsFragment))
            if (!dependente.isSelfCareProfile) {
                list.add(DashboardCategory("Excluir Perfil", R.drawable.ic_delete_red, ACTION_ID_DELETE_DEPENDENT))
            }
        }
        return list
    }

    fun saveAnalysisToHistory(prompt: String, analysisResult: String) {
        viewModelScope.launch {
            val currentDependentId = _dependentId.value ?: return@launch
            val currentUserId = userRepository.getCurrentUser()?.uid ?: "caregiver_user"
            val historyEntry = AnalysisHistory(
                dependentId = currentDependentId,
                userId = currentUserId,
                symptomsPrompt = prompt,
                analysisResult = analysisResult
            )
            val result = firestoreRepository.saveAnalysisHistory(historyEntry)
            if (result.isFailure) {
                _actionFeedback.postValue(Event("Não foi possível salvar a análise no histórico."))
            }
        }
    }

    fun onViewCredentialsClicked() {
        dependente.value?.let { _showCredentials.value = Event(Pair(it.codigoDeVinculo, it.senha)) }
    }

    fun onToggleAlarmClicked() {
        dependente.value?.let {
            val newStatus = !it.usaAlarmeTelaCheia
            viewModelScope.launch {
                firestoreRepository.updateUsaAlarmeTelaCheia(it.id, newStatus).onSuccess {
                    val statusText = if (newStatus) "ativado" else "desativado"
                    _actionFeedback.postValue(Event("Alarme de tela cheia $statusText."))
                }
            }
        }
    }

    fun onDeleteDependentClicked() {
        if (dependente.value?.isSelfCareProfile == true) {
            _actionFeedback.value = Event("Não é possível excluir seu próprio perfil de autocuidado a partir daqui.")
        } else {
            _showDeleteConfirmation.value = Event(Unit)
        }
    }

    fun confirmDeleteDependent() {
        dependente.value?.id?.let { dependentId ->
            viewModelScope.launch {
                firestoreRepository.deleteDependentAndAllData(dependentId).onSuccess {
                    _actionFeedback.postValue(Event("Dependente excluído com sucesso."))
                }.onFailure {
                    _actionFeedback.postValue(Event("Erro ao excluir dependente: ${it.message}"))
                }
            }
        }
    }

    fun onEmergencyClicked() {
        _showEmergencyConfirmation.postValue(Event(Unit))
    }

    fun confirmEmergencyAlert() {
        viewModelScope.launch {
            val currentDependentId = _dependentId.value ?: return@launch
            firestoreRepository.triggerEmergencyAlert(currentDependentId).onSuccess {
                _actionFeedback.postValue(Event("Alerta de emergência enviado aos seus cuidadores!"))
                saveLog(currentDependentId, "acionou o botão de emergência", TipoAtividade.ANOTACAO_CRIADA)
            }.onFailure {
                _actionFeedback.postValue(Event("Falha ao enviar alerta. Verifique sua conexão."))
            }
        }
    }

    suspend fun getPredictiveAnalysis(
        symptoms: String, startDate: LocalDate, endDate: LocalDate,
        includeDoseHistory: Boolean, includeHealthNotes: Boolean, includeContinuousMeds: Boolean
    ): Result<String> {
        _showLoading.postValue(Event("Analisando..."))
        try {
            val currentDependentId = _dependentId.value ?: return Result.failure(Exception("ID do dependente não encontrado."))
            return firestoreRepository.getPredictiveAnalysis(
                currentDependentId, symptoms, startDate, endDate,
                includeDoseHistory, includeHealthNotes, includeContinuousMeds, dependente.value
            )
        } finally {
            _hideLoading.postValue(Event(Unit))
        }
    }

    private fun calculateImc(weightStr: String, heightStr: String) {
        val weight = weightStr.replace(',', '.').toFloatOrNull()
        val height = heightStr.replace(',', '.').toFloatOrNull()
        if (weight != null && height != null && height > 0) {
            val heightInMeters = height / 100
            val imc = weight / (heightInMeters * heightInMeters)
            val (classification, color) = when {
                imc < 18.5 -> "Abaixo do peso" to R.color.warning_orange
                imc < 25 -> "Peso normal" to R.color.success_green
                imc < 30 -> "Sobrepeso" to R.color.warning_orange
                else -> "Obesidade" to R.color.error_red
            }
            _imcResult.postValue(ImcResult(imc, classification, color))
        } else {
            _imcResult.postValue(null)
        }
    }

    private fun calculateWeightGoalStatus(dependente: Dependente, healthNotes: List<HealthNote>) {
        val currentWeight = dependente.peso.replace(',', '.').toFloatOrNull()
        val targetWeight = dependente.pesoMeta.replace(',', '.').toFloatOrNull()
        if (currentWeight == null || targetWeight == null || targetWeight <= 0) {
            _weightGoalStatus.postValue(null)
            return
        }
        val initialWeightRecord = healthNotes
            .filter { it.type == HealthNoteType.WEIGHT && it.values["weight"]?.isNotBlank() == true }
            .minByOrNull { it.timestamp }
        val initialWeight = initialWeightRecord?.values?.get("weight")?.replace(',', '.')?.toFloatOrNull()
        if (initialWeight == null) {
            _weightGoalStatus.postValue(null)
            return
        }
        val totalDistance = abs(initialWeight - targetWeight)
        val distanceCovered = abs(initialWeight - currentWeight)
        val progress = if (totalDistance > 0.1f) ((distanceCovered / totalDistance) * 100).toInt().coerceIn(0, 100) else 100
        val difference = currentWeight - targetWeight
        val (progressText, color) = when {
            abs(difference) <= 0.5 -> "Meta atingida!" to R.color.success_green
            currentWeight > targetWeight -> "Faltam ${String.format(Locale.getDefault(), "%.1f", difference)} kg para sua meta" to R.color.md_theme_primary
            else -> "Faltam ${String.format(Locale.getDefault(), "%.1f", abs(difference))} kg para sua meta" to R.color.md_theme_secondary
        }
        _weightGoalStatus.postValue(WeightGoalStatus(progress, progressText, color))
    }

    private suspend fun saveLog(dependentId: String, descricao: String, tipo: TipoAtividade) {
        val autorNome = userPreferences.getUserName()
        val autorId = if (userPreferences.getIsCaregiver()) userRepository.getCurrentUser()?.uid ?: "" else "dependent_user"
        val log = Atividade(
            descricao = "$autorNome $descricao",
            tipo = tipo,
            autorId = autorId,
            autorNome = autorNome
        )
        firestoreRepository.saveActivityLog(dependentId, log)
    }
}