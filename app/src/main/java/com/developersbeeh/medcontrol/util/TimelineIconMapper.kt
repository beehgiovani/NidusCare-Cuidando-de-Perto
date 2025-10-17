// src/main/java/com/developersbeeh/medcontrol/util/TimelineIconMapper.kt
package com.developersbeeh.medcontrol.util

import com.developersbeeh.medcontrol.R
import com.developersbeeh.medcontrol.data.model.HealthNoteType
import com.developersbeeh.medcontrol.data.model.TipoAtividade

object TimelineIconMapper {

    fun getIconRes(iconName: String): Int {
        return when (iconName) {
            "DOSE" -> R.drawable.ic_pill
            HealthNoteType.BLOOD_PRESSURE.name -> R.drawable.ic_blood_pressure
            HealthNoteType.BLOOD_SUGAR.name -> R.drawable.ic_blood_glucose
            HealthNoteType.WEIGHT.name -> R.drawable.ic_weight
            HealthNoteType.TEMPERATURE.name -> R.drawable.ic_thermostat
            HealthNoteType.MOOD.name -> R.drawable.ic_sentiment
            HealthNoteType.SYMPTOM.name -> R.drawable.ic_symptom
            HealthNoteType.GENERAL.name -> R.drawable.ic_notes
            "INSIGHT" -> R.drawable.ic_ai_analysis
            "HYDRATION" -> R.drawable.ic_water_drop
            "FITNESS" -> R.drawable.ic_fitness
            "MEAL" -> R.drawable.ic_food
            "SLEEP" -> R.drawable.ic_sleep
            TipoAtividade.DOCUMENTO_ADICIONADO.name -> R.drawable.ic_file_document
            TipoAtividade.AGENDAMENTO_CRIADO.name -> R.drawable.ic_calendar
            TipoAtividade.MEDICAMENTO_CRIADO.name -> R.drawable.ic_add_circle
            TipoAtividade.MEDICAMENTO_EDITADO.name -> R.drawable.ic_edit
            TipoAtividade.MEDICAMENTO_EXCLUIDO.name -> R.drawable.ic_delete
            TipoAtividade.TRATAMENTO_PAUSADO.name -> R.drawable.ic_pause
            TipoAtividade.TRATAMENTO_REATIVADO.name -> R.drawable.ic_play_arrow
            else -> R.drawable.ic_info
        }
    }

    fun getColorRes(type: String): Int {
        return when (type) {
            "DOSE" -> R.color.md_theme_primary
            "NOTE" -> R.color.md_theme_secondary
            "INSIGHT" -> R.color.md_theme_tertiary
            "ACTIVITY" -> R.color.info_blue
            else -> R.color.md_theme_onSurfaceVariant
        }
    }
}