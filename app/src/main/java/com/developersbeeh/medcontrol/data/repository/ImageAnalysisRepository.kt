package com.developersbeeh.medcontrol.data.repository

import android.net.Uri
import android.util.Log
import com.developersbeeh.medcontrol.data.model.FrequenciaTipo
import com.developersbeeh.medcontrol.data.model.Medicamento
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ImageAnalysisRepository"

@Singleton
class ImageAnalysisRepository @Inject constructor(
    private val functions: FirebaseFunctions,
    private val auth: FirebaseAuth,
    private val storage: FirebaseStorage,
    private val firestoreRepository: FirestoreRepository,
    private val medicationRepository: MedicationRepository
) {
    suspend fun analyzePrescription(
        dependentId: String,
        imageUri: Uri
    ): Result<List<Medicamento>> {
        return try {
            val currentUser = auth.currentUser
                ?: return Result.failure(Exception("Usuário não autenticado. Faça login novamente."))

            val dependente = firestoreRepository.getDependente(dependentId)
            val medicamentosAtuais = medicationRepository.getMedicamentos(dependentId).first()

            // A lógica de upload continua igual e é necessária
            val uniqueFileName = "${UUID.randomUUID()}-${imageUri.lastPathSegment}"
            val storageRef = storage.reference.child("prescription_scans/${currentUser.uid}/$uniqueFileName")
            storageRef.putFile(imageUri).await()
            val downloadUrl = storageRef.downloadUrl.await().toString()

            // ✅ 3. O CORPO DA REQUISIÇÃO AGORA É UM HASHMAP
            val data = hashMapOf(
                "imageUri" to downloadUrl,
                "alergiasConhecidas" to (dependente?.alergias ?: "Nenhuma informada"),
                "condicoesPreexistentes" to (dependente?.condicoesPreexistentes ?: "Nenhuma informada"),
                "medicamentosAtuais" to medicamentosAtuais.map { it.nome }
            )

            Log.d(TAG, "Chamando a Cloud Function 'analisarReceita'...")
            // ✅ 4. CHAMADA FEITA COM O SDK DO FIREBASE FUNCTIONS
            val result = functions
                .getHttpsCallable("analisarReceita")
                .call(data)
                .await()

            // ✅ 5. PROCESSANDO A RESPOSTA DA FUNÇÃO
            @Suppress("UNCHECKED_CAST")
            val resultMap = result.data as? Map<String, Any>
            val medicationsList = resultMap?.get("medications") as? List<Map<String, Any>> ?: emptyList()

            // Converte a lista de mapas para a sua lista de objetos Medicamento
            val medications = medicationsList.map { medMap ->
                val posologia = medMap["posologia"] as? String ?: ""
                val avisos = (medMap["avisos"] as? List<String>) ?: emptyList()

                val (frequenciaTipo, frequenciaValor, duracaoDias) = parsePosologia(posologia)
                Medicamento(
                    nome = medMap["nome"] as? String ?: "Nome não encontrado",
                    dosagem = medMap["dosagem"] as? String ?: "Dosagem não encontrada",
                    frequenciaTipo = frequenciaTipo,
                    frequenciaValor = frequenciaValor,
                    duracaoDias = duracaoDias,
                    isUsoContinuo = duracaoDias == 0,
                    horarios = emptyList(),
                    posologia = posologia,
                    anotacoes = if (avisos.isNotEmpty()) "Avisos da Análise:\n- ${avisos.joinToString("\n- ")}" else null
                )
            }

            Log.i(TAG, "Análise da receita concluída com sucesso. ${medications.size} medicamentos encontrados.")
            Result.success(medications)

        } catch (e: Exception) {
            Log.e(TAG, "Falha ao analisar a receita: ${e.message}", e)
            Result.failure(e)
        }
    }


    private fun parsePosologia(posologia: String): Triple<FrequenciaTipo, Int, Int> {
        val lowerCasePosologia = posologia.lowercase()

        var frequenciaTipo = FrequenciaTipo.DIARIA
        var frequenciaValor = 1
        var duracaoDias = 0

        // 1. Tentar encontrar a duração em dias primeiro
        val duracaoMatch = Regex("por\\s+(\\d+)\\s+dias?").find(lowerCasePosologia)
        duracaoMatch?.let {
            duracaoDias = it.groupValues[1].toIntOrNull() ?: 0
        }

        // 2. Tentar encontrar a frequência mais específica: "a cada X horas" ou "de X/X horas"
        val horasMatch = Regex("a\\s+cada\\s+(\\d+)\\s+horas?|de\\s*(\\d+)[/]\\s*(\\d+)\\s*h?").find(lowerCasePosologia)
        val intervaloEmHoras = horasMatch?.groupValues?.get(1)?.toIntOrNull()
            ?: horasMatch?.groupValues?.get(2)?.toIntOrNull()

        if (intervaloEmHoras != null && intervaloEmHoras > 0) {
            frequenciaTipo = FrequenciaTipo.DIARIA
            frequenciaValor = 24 / intervaloEmHoras
            return Triple(frequenciaTipo, frequenciaValor, duracaoDias)
        }

        // 3. Tentar encontrar frequência: "X vezes ao dia" ou "X/dia"
        val vezesDiaMatch = Regex("(\\d+)\\s*([x]|vezes)?\\s*(ao\\s+dia|/dia)").find(lowerCasePosologia)
        if (vezesDiaMatch != null) {
            frequenciaTipo = FrequenciaTipo.DIARIA
            frequenciaValor = vezesDiaMatch.groupValues[1].toIntOrNull() ?: 1
            return Triple(frequenciaTipo, frequenciaValor, duracaoDias)
        }

        // 4. Tentar encontrar abreviações médicas comuns
        val abreviacaoMatch = when {
            lowerCasePosologia.contains("q.i.d.") -> 4
            lowerCasePosologia.contains("t.i.d.") -> 3
            lowerCasePosologia.contains("b.i.d.") -> 2
            lowerCasePosologia.contains("q.d.") -> 1
            else -> null
        }
        if (abreviacaoMatch != null) {
            frequenciaTipo = FrequenciaTipo.DIARIA
            frequenciaValor = abreviacaoMatch
            return Triple(frequenciaTipo, frequenciaValor, duracaoDias)
        }

        // 5. Tentar encontrar frequências nominais como "uma vez ao dia"
        if (lowerCasePosologia.contains("uma vez ao dia")) {
            frequenciaTipo = FrequenciaTipo.DIARIA
            frequenciaValor = 1
            return Triple(frequenciaTipo, frequenciaValor, duracaoDias)
        }

        // 6. Tentar encontrar "quando necessário" ou "se necessário"
        if (lowerCasePosologia.contains("quando necessário") || lowerCasePosologia.contains("se necessário")) {
            frequenciaTipo = FrequenciaTipo.INTERVALO_DIAS
            frequenciaValor = 0 // Indica que é sob demanda
            return Triple(frequenciaTipo, frequenciaValor, duracaoDias)
        }

        // 7. Caso de fallback
        return Triple(frequenciaTipo, frequenciaValor, duracaoDias)
    }
}