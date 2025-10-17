package com.developersbeeh.medcontrol.ui.scan

import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.developersbeeh.medcontrol.data.model.EstoqueLote
import com.developersbeeh.medcontrol.data.model.Medicamento
import com.developersbeeh.medcontrol.data.repository.MedicationRepository
import com.developersbeeh.medcontrol.data.repository.StorageRepository
import com.developersbeeh.medcontrol.util.Event
import com.google.firebase.Firebase
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.functions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

sealed class ScanState {
    object Idle : ScanState()
    object Loading : ScanState()
    data class Success(val extractedData: ExtractedMedicationData) : ScanState()
    data class Error(val message: String) : ScanState()
}

data class ExtractedMedicationData(
    val nome: String?,
    val estoque: Double?,
    val principioAtivo: String?,
    val classeTerapeutica: String?,
    val anotacoes: String?,
    val lote: String?,
    val validade: String?
)

@HiltViewModel
class ScanAndConfirmViewModel @Inject constructor(
    private val storageRepository: StorageRepository,
    private val medicationRepository: MedicationRepository
) : ViewModel() {

    private val _scanState = MutableLiveData<ScanState>(ScanState.Idle)
    val scanState: LiveData<ScanState> = _scanState

    private val _saveStatus = MutableLiveData<Event<Boolean>>()
    val saveStatus: LiveData<Event<Boolean>> = _saveStatus

    fun analyzeImage(imageUri: Uri, userId: String) {
        viewModelScope.launch {
            _scanState.value = ScanState.Loading
            val uploadResult = storageRepository.uploadImageForScan(imageUri, userId)
            uploadResult.onSuccess { gcsUri ->
                try {
                    val data = hashMapOf("imageGcsUri" to gcsUri)
                    val result = Firebase.functions
                        .getHttpsCallable("analisarCaixaRemedio")
                        .call(data)
                        .await()

                    @Suppress("UNCHECKED_CAST")
                    val resultMap = result.data as? Map<String, Any>
                    val extractedData = ExtractedMedicationData(
                        nome = resultMap?.get("nome") as? String,
                        estoque = (resultMap?.get("estoque") as? Number)?.toDouble(),
                        principioAtivo = resultMap?.get("principioAtivo") as? String,
                        classeTerapeutica = resultMap?.get("classeTerapeutica") as? String,
                        anotacoes = resultMap?.get("anotacoes") as? String,
                        lote = resultMap?.get("lote") as? String,
                        validade = resultMap?.get("validade") as? String
                    )
                    _scanState.value = ScanState.Success(extractedData)
                } catch (e: Exception) {
                    val errorMessage = if (e is FirebaseFunctionsException) {
                        e.details?.toString() ?: e.message
                    } else {
                        e.message
                    }
                    _scanState.value = ScanState.Error(errorMessage ?: "Erro desconhecido")
                }
            }.onFailure {
                _scanState.value = ScanState.Error(it.message ?: "Falha no upload da imagem")
            }
        }
    }

    fun saveScannedMedication(
        dependentId: String?,
        nome: String,
        principioAtivo: String?,
        estoque: Double,
        validade: LocalDate,
        loteNumero: String?,
        classeTerapeutica: String?,
        anotacoes: String?
    ) {
        viewModelScope.launch {
            // ✅ CORREÇÃO: O objeto Lote é criado com todas as informações
            val lote = EstoqueLote(
                quantidade = estoque,
                quantidadeInicial = estoque,
                lote = loteNumero
            ).apply {
                dataValidade = validade
            }

            // ✅ CORREÇÃO: A lista 'lotes' do medicamento é preenchida
            val medicamento = Medicamento(
                id = UUID.randomUUID().toString(),
                nome = nome,
                isUsoEsporadico = true,
                lotes = listOf(lote), // AQUI ESTAVA FALTANDO
                principioAtivo = principioAtivo,
                classeTerapeutica = classeTerapeutica,
                anotacoes = anotacoes,
                dosagem = ""
            )

            val targetDependentId = dependentId ?: medicationRepository.getCurrentUserId() ?: ""
            if (targetDependentId.isBlank()) {
                _saveStatus.postValue(Event(false))
                return@launch
            }

            medicationRepository.saveMedicamento(targetDependentId, medicamento).onSuccess {
                _saveStatus.postValue(Event(true))
            }.onFailure {
                _saveStatus.postValue(Event(false))
            }
        }
    }
}