package com.developersbeeh.medcontrol.ui.listmedicamentos

import com.developersbeeh.medcontrol.data.model.Medicamento

data class MedicamentoUiState(
    val medicamento: Medicamento,
    val status: MedicamentoStatus,
    val statusText: String,
    val dosesTomadasHoje: Int,
    val dosesEsperadasHoje: Int,
    val adherenceStatus: AdherenceStatus,
    // --- NOVOS CAMPOS PARA EXIBIÇÃO NA UI ---
    val ultimoLocalAplicado: String?,
    val proximoLocalSugerido: String?
)