// src/main/java/com/developersbeeh/medcontrol/ui/activities/PhysicalActivityTrackerViewModel.kt
package com.developersbeeh.medcontrol.ui.activities

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.developersbeeh.medcontrol.data.model.AtividadeFisica
import com.developersbeeh.medcontrol.data.model.Dependente
import com.developersbeeh.medcontrol.data.repository.FirestoreRepository
import com.developersbeeh.medcontrol.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class ActivityScreenState(
    val dependent: Dependente,
    val history: List<AtividadeFisica>
)

@HiltViewModel
class PhysicalActivityTrackerViewModel @Inject constructor(
    private val firestoreRepository: FirestoreRepository
) : ViewModel() {

    private val _uiState = MutableLiveData<UiState<ActivityScreenState>>()
    val uiState: LiveData<UiState<ActivityScreenState>> = _uiState

    fun initialize(dependentId: String) {
        if (dependentId.isBlank()) {
            _uiState.value = UiState.Error("ID do dependente inválido.")
            return
        }
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            val dependent = firestoreRepository.getDependente(dependentId)
            if (dependent == null) {
                _uiState.postValue(UiState.Error("Dependente não encontrado."))
                return@launch
            }

            val thirtyDaysAgo = LocalDate.now().minusDays(29)
            val today = LocalDate.now()

            // ✅ CORREÇÃO: Chamando a função correta com os parâmetros de início e fim.
            firestoreRepository.getAtividadeFisicaHistory(dependentId, thirtyDaysAgo, today)
                .collectLatest { records ->
                    _uiState.postValue(UiState.Success(ActivityScreenState(dependent, records)))
                }
        }
    }
}