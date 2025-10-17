// src/main/java/com/developersbeeh/medcontrol/ui/archive/ArchivedMedicationsViewModel.kt
package com.developersbeeh.medcontrol.ui.archive

import androidx.lifecycle.*
import com.developersbeeh.medcontrol.data.model.EstoqueLote
import com.developersbeeh.medcontrol.data.model.Medicamento
import com.developersbeeh.medcontrol.data.model.TipoAtividade
import com.developersbeeh.medcontrol.data.repository.ActivityLogRepository
import com.developersbeeh.medcontrol.data.repository.MedicationRepository
import com.developersbeeh.medcontrol.util.Event
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class ArchivedMedicationsViewModel @Inject constructor(
    private val medicationRepository: MedicationRepository,
    private val activityLogRepository: ActivityLogRepository
) : ViewModel() {

    private val _dependentId = MutableLiveData<String>()

    val actionFeedback = MutableLiveData<Event<String>>()

    // ✅ CORREÇÃO: Busca os medicamentos que estão marcados como arquivados.
    val archivedMedications: LiveData<List<Medicamento>> = _dependentId.switchMap { id ->
        if (id.isNotBlank()) {
            medicationRepository.getArchivedMedicamentos(id).asLiveData()
        } else {
            MutableLiveData(emptyList())
        }
    }

    val finishedTreatments: LiveData<List<Medicamento>> = archivedMedications.map { list ->
        list.filter {
            !it.isUsoContinuo && it.duracaoDias > 0 &&
                    LocalDate.now().isAfter(it.dataInicioTratamento.plusDays(it.duracaoDias.toLong() - 1))
        }
    }

    val zeroStockMeds: LiveData<List<Medicamento>> = archivedMedications.map { list ->
        list.filter {
            (it.lotes.isNotEmpty() || it.nivelDeAlertaEstoque > 0) && it.estoqueAtualTotal <= 0 && it.lotes.none { lote -> lote.dataValidade.isBefore(LocalDate.now()) }
        }
    }

    val expiredStockMeds: LiveData<List<Medicamento>> = archivedMedications.map { list ->
        list.filter { med ->
            med.lotes.any { it.dataValidade.isBefore(LocalDate.now()) }
        }
    }

    fun initialize(depId: String) {
        if (_dependentId.value != depId) {
            _dependentId.value = depId
        }
    }

    fun restoreMedicamento(medicamento: Medicamento) = viewModelScope.launch {
        _dependentId.value?.let { depId ->
            medicationRepository.unarchiveMedicamento(depId, medicamento.id).onSuccess {
                activityLogRepository.saveLog(depId, "restaurou o medicamento ${medicamento.nome}", TipoAtividade.TRATAMENTO_REATIVADO)
                actionFeedback.postValue(Event("'${medicamento.nome}' foi restaurado para a lista principal."))
            }.onFailure {
                actionFeedback.postValue(Event("Falha ao restaurar medicamento."))
            }
        }
    }

    fun removeExpiredLots(medicamento: Medicamento) = viewModelScope.launch {
        _dependentId.value?.let { depId ->
            val validLotes = medicamento.lotes.filter { it.dataValidade.isAfter(LocalDate.now().minusDays(1)) }
            val updatedMed = medicamento.copy(lotes = validLotes)
            medicationRepository.saveMedicamento(depId, updatedMed).onSuccess {
                activityLogRepository.saveLog(depId, "removeu lotes vencidos de ${medicamento.nome}", TipoAtividade.MEDICAMENTO_EDITADO)
                actionFeedback.postValue(Event("Lotes vencidos de ${medicamento.nome} foram removidos."))
            }
        }
    }

    fun stopTrackingStock(medicamento: Medicamento) = viewModelScope.launch {
        _dependentId.value?.let { depId ->
            val updatedMed = medicamento.copy(lotes = emptyList(), nivelDeAlertaEstoque = 0)
            medicationRepository.saveMedicamento(depId, updatedMed).onSuccess {
                actionFeedback.postValue(Event("O rastreamento de estoque para ${medicamento.nome} foi desativado."))
            }
        }
    }

    fun deleteMedicationPermanently(medicamento: Medicamento) = viewModelScope.launch {
        _dependentId.value?.let { depId ->
            // ✅ CORREÇÃO: Chamando a função correta do repositório
            medicationRepository.permanentlyDeleteMedicamento(depId, medicamento.id).onSuccess {
                activityLogRepository.saveLog(depId, "excluiu permanentemente o medicamento ${medicamento.nome}", TipoAtividade.MEDICAMENTO_EXCLUIDO)
                actionFeedback.postValue(Event("'${medicamento.nome}' foi excluído permanentemente."))
            }.onFailure {
                actionFeedback.postValue(Event("Falha ao excluir permanentemente."))
            }
        }
    }

    fun addStockLot(medicamento: Medicamento, novoLote: EstoqueLote) = viewModelScope.launch {
        _dependentId.value?.let { depId ->
            medicationRepository.addStockLot(depId, medicamento.id, novoLote).onSuccess {
                activityLogRepository.saveLog(depId, "repos o estoque de ${medicamento.nome}", TipoAtividade.MEDICAMENTO_EDITADO)
                actionFeedback.postValue(Event("Estoque de ${medicamento.nome} atualizado! O medicamento foi movido para a lista principal."))
            }.onFailure {
                actionFeedback.postValue(Event("Falha ao atualizar o estoque."))
            }
        }
    }
}