// src/main/java/com/developersbeeh/medcontrol/ui/weight/WeightTrackerViewModel.kt
package com.developersbeeh.medcontrol.ui.weight

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.developersbeeh.medcontrol.data.model.Dependente
import com.developersbeeh.medcontrol.data.model.HealthNote
import com.developersbeeh.medcontrol.data.model.HealthNoteType
import com.developersbeeh.medcontrol.data.repository.FirestoreRepository
import com.developersbeeh.medcontrol.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WeightScreenState(
    val dependent: Dependente,
    val history: List<HealthNote>
)

@HiltViewModel
class WeightTrackerViewModel @Inject constructor(
    private val firestoreRepository: FirestoreRepository
) : ViewModel() {

    private val _uiState = MutableLiveData<UiState<WeightScreenState>>()
    val uiState: LiveData<UiState<WeightScreenState>> = _uiState

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

            firestoreRepository.getHealthNotes(dependentId)
                .collectLatest { allNotes ->
                    val weightNotes = allNotes
                        .filter { it.type == HealthNoteType.WEIGHT && it.values["weight"]?.isNotBlank() == true }
                        .sortedBy { it.timestamp }
                    _uiState.postValue(UiState.Success(WeightScreenState(dependent, weightNotes)))
                }
        }
    }
}