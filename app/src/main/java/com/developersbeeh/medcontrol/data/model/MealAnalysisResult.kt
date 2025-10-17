// src/main/java/com/developersbeeh/medcontrol/data/model/MealAnalysisResult.kt
package com.developersbeeh.medcontrol.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class MealAnalysisResult(
    @Json(name = "descricao") val descricao: String = "",
    @Json(name = "calorias") val calorias: Int = 0,
    @Json(name = "beneficios") val beneficios: String = "",
    @Json(name = "dicas") val dicas: String = ""
)