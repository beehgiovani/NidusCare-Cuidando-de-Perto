package com.developersbeeh.medcontrol.data.model

import com.google.firebase.firestore.Exclude
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

// ✅ CORREÇÃO: Enum renomeado para evitar conflito com o enum de cálculo.
enum class RecordedDoseStatus {
    TAKEN, // Dose foi tomada
    SKIPPED // Dose foi intencionalmente pulada
}

data class DoseHistory(
    var id: String = UUID.randomUUID().toString(),
    var medicamentoId: String = "",
    var userId: String = "",
    var localDeAplicacao: String? = null,
    var quantidadeAdministrada: Double? = null,
    var notas: String? = null,
    var timestampString: String = "",
    // ✅ CORREÇÃO: O campo agora usa o enum renomeado.
    var status: RecordedDoseStatus = RecordedDoseStatus.TAKEN
) {
    // Construtor vazio para o Firestore
    constructor() : this(id = UUID.randomUUID().toString())

    @get:Exclude
    @set:Exclude
    var timestamp: LocalDateTime
        get() = if (timestampString.isNotBlank()) LocalDateTime.parse(timestampString) else LocalDateTime.now()
        set(value) {
            timestampString = value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        }
}