// src/main/java/com/developersbeeh/medcontrol/data/model/DailyCycleLog.kt
package com.developersbeeh.medcontrol.data.model

import com.google.firebase.firestore.DocumentId
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class FlowIntensity(val displayName: String) {
    NONE("Nenhum"),
    SPOTTING("Gotas"),
    LIGHT("Leve"),
    MEDIUM("Médio"),
    HEAVY("Intenso")
}

enum class Symptom(val displayName: String) {
    CRAMPS("Cólicas"),
    HEADACHE("Dor de Cabeça"),
    BLOATING("Inchaço"),
    FATIGUE("Fadiga"),
    MOOD_SWINGS("Alterações de Humor"),
    ACNE("Acne"),
    TENDER_BREASTS("Sensibilidade nos Seios"),
    BACK_PAIN("Dor nas Costas"),
    NAUSEA("Náusea"),
    CRAVINGS("Desejos Alimentares")
}

enum class Mood(val displayName: String) {
    HAPPY("Feliz"),
    CALM("Calma"),
    SAD("Triste"),
    IRRITABLE("Irritada"),
    ANXIOUS("Ansiosa")
}

enum class CervicalMucus(val displayName: String) {
    DRY("Seco"),
    STICKY("Pegajoso"),
    CREAMY("Cremoso"),
    EGG_WHITE("Clara de Ovo")
}

data class DailyCycleLog(
    @DocumentId
    val dateString: String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),

    val dependentId: String = "",
    val flow: FlowIntensity = FlowIntensity.NONE,
    val symptoms: List<Symptom> = emptyList(), // ✅ CORRIGIDO: de Set para List
    val mood: Mood? = null,
    val hadSexualActivity: Boolean = false,
    val wasProtected: Boolean? = null,
    val cervicalMucus: CervicalMucus? = null,
    val basalBodyTemperature: Double? = null,
    val notes: String? = null
) {
    fun getDate(): LocalDate = LocalDate.parse(dateString)
}