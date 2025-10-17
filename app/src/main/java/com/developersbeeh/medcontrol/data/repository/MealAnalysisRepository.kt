// src/main/java/com/developersbeeh/medcontrol/data/repository/MealAnalysisRepository.kt
package com.developersbeeh.medcontrol.data.repository

import android.net.Uri
import android.util.Log
import com.developersbeeh.medcontrol.data.model.MealAnalysisResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import com.squareup.moshi.Moshi
import kotlinx.coroutines.tasks.await
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MealAnalysisRepository"

@Singleton
class MealAnalysisRepository @Inject constructor(
    private val functions: FirebaseFunctions,
    private val auth: FirebaseAuth,
    private val storage: FirebaseStorage,
    private val firestoreRepository: FirestoreRepository,
    private val moshi: Moshi
) {

    suspend fun analyzeMealPhoto(dependentId: String, imageUri: Uri): Result<MealAnalysisResult> {
        val currentUser = auth.currentUser ?: return Result.failure(Exception("Usuário não autenticado."))

        try {
            // 1. Fazer upload da imagem para o Firebase Storage
            Log.d(TAG, "Iniciando upload da imagem...")
            val uniqueFileName = "${UUID.randomUUID()}.jpg"
            val storageRef = storage.reference.child("meal_photos/${currentUser.uid}/$uniqueFileName")
            storageRef.putFile(imageUri).await()
            val downloadUrl = storageRef.downloadUrl.await().toString()
            Log.d(TAG, "Upload concluído. URL: $downloadUrl")

            // 2. Obter perfil do dependente
            val dependente = firestoreRepository.getDependente(dependentId)

            // 3. Chamar a Cloud Function com a URL da imagem
            val data = hashMapOf(
                "imageUri" to downloadUrl,
                "healthProfile" to mapOf(
                    "idade" to (dependente?.dataDeNascimento ?: ""),
                    "peso" to (dependente?.peso ?: ""),
                    "altura" to (dependente?.altura ?: ""),
                    "sexo" to (dependente?.sexo ?: "")
                )
            )

            Log.d(TAG, "Chamando a Cloud Function 'analisarRefeicao'...")
            val result = functions
                .getHttpsCallable("analisarRefeicao")
                .call(data)
                .await()

            // 4. Converter a resposta (que é um Map) para o nosso objeto de dados
            @Suppress("UNCHECKED_CAST")
            val resultMap = result.data as? Map<String, Any>
            if (resultMap != null && resultMap.containsKey("error")) {
                return Result.failure(Exception(resultMap["error"] as String))
            }

            val adapter = moshi.adapter(MealAnalysisResult::class.java)
            val analysisResult = adapter.fromJsonValue(resultMap)

            if (analysisResult != null) {
                Log.i(TAG, "Análise da refeição concluída com sucesso.")
                return Result.success(analysisResult)
            } else {
                return Result.failure(Exception("Não foi possível processar a resposta da IA."))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Falha ao analisar a refeição: ${e.message}", e)
            return Result.failure(e)
        }
    }
}