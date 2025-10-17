package com.developersbeeh.medcontrol.ui.timeline

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import com.developersbeeh.medcontrol.data.model.HealthNoteType
import com.developersbeeh.medcontrol.data.model.TimelineEvent
import com.developersbeeh.medcontrol.data.repository.FirestoreRepository
import com.developersbeeh.medcontrol.util.TimelineIconMapper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import javax.inject.Inject

private const val TAG = "TimelineViewModel"

enum class TimelineFilter {
    ALL, DOSE, NOTE, ACTIVITY, INSIGHT
}

@HiltViewModel
class TimelineViewModel @Inject constructor(
    private val firestoreRepository: FirestoreRepository
) : ViewModel() {

    private val _filter = MutableStateFlow(TimelineFilter.ALL)
    private val _dependentId = MutableStateFlow<String?>(null)

    // ✅ ALTERAÇÃO: A UI agora consome um Flow de PagingData
    @OptIn(ExperimentalCoroutinesApi::class)
    val timelinePagerFlow: Flow<PagingData<TimelineListItem>> = _dependentId.filterNotNull().flatMapLatest { id ->
        firestoreRepository.getTimelinePager(id).flow
    }.map { pagingData ->
        pagingData.map { event ->
            // Mapeia o TimelineEvent bruto para o nosso item de UI
            event.toTimelineLogItem()
        }
    }.map { pagingData ->
        // Insere os separadores (cabeçalhos de data)
        pagingData.insertSeparators { before, after ->
            if (after == null) {
                // Fim da lista
                return@insertSeparators null
            }
            if (before == null) {
                // Início da lista
                return@insertSeparators TimelineListItem.HeaderItem(after.log.timestamp.toLocalDate())
            }
            if (before.log.timestamp.toLocalDate() != after.log.timestamp.toLocalDate()) {
                // Insere o cabeçalho quando a data muda
                TimelineListItem.HeaderItem(after.log.timestamp.toLocalDate())
            } else {
                null
            }
        }
    }.cachedIn(viewModelScope) // Cacheia os resultados no ViewModelScope

    fun initialize(dependentId: String) {
        if (_dependentId.value == dependentId) return
        _dependentId.value = dependentId
    }

    // A função de reload agora não é mais necessária, o PagingAdapter tem seu próprio método refresh()

    fun setFilter(filter: TimelineFilter) {
        // A lógica de filtro agora precisará ser aplicada no PagingSource ou após o carregamento.
        // Por simplicidade inicial, removeremos o filtro do backend e o faremos no cliente se necessário.
        // A implementação atual não filtra, mas o mecanismo está aqui.
        _filter.value = filter
    }

    private fun TimelineEvent.toTimelineLogItem(): TimelineListItem.LogItem {
        val category = when (this.type) {
            "DOSE" -> TimelineItemCategory.DOSE
            "NOTE" -> TimelineItemCategory.NOTE
            "ACTIVITY" -> TimelineItemCategory.ACTIVITY
            "INSIGHT" -> TimelineItemCategory.INSIGHT
            else -> {
                Log.w(TAG, "Tipo de evento da timeline desconhecido: '${this.type}'. Classificando como ACTIVITY.")
                TimelineItemCategory.ACTIVITY
            }
        }

        return TimelineListItem.LogItem(
            TimelineLogItem(
                id = this.id,
                timestamp = this.getLocalDateTime(),
                description = this.description,
                author = this.author,
                iconRes = TimelineIconMapper.getIconRes(this.icon),
                iconTintRes = TimelineIconMapper.getColorRes(this.type),
                category = category,
                noteType = try { HealthNoteType.valueOf(this.icon) } catch (e: Exception) { null }
            )
        )
    }
}