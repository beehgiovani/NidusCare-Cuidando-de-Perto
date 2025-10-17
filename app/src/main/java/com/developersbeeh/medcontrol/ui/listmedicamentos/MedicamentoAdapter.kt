package com.developersbeeh.medcontrol.ui.listmedicamentos

import android.R.attr.colorError
import android.R.attr.colorPrimary
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.developersbeeh.medcontrol.R
import com.developersbeeh.medcontrol.data.model.Medicamento
import com.developersbeeh.medcontrol.data.model.TipoMedicamento
import com.developersbeeh.medcontrol.databinding.MedicamentoItemBinding
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.min

class MedicamentoAdapter(
    private val isCaregiver: Boolean,
    private val podeRegistrarDose: Boolean,
    private val onDeleteClick: (MedicamentoUiState) -> Unit,
    private val onEditClick: (MedicamentoUiState) -> Unit,
    private val onMarkAsTakenClick: (MedicamentoUiState) -> Unit,
    private val onPausePlayClick: (MedicamentoUiState) -> Unit,
    private val onRefillClick: (MedicamentoUiState) -> Unit,
    private val onSkipDoseClick: (MedicamentoUiState) -> Unit
) : ListAdapter<MedicamentoUiState, MedicamentoAdapter.MedicamentoViewHolder>(MedicamentoUiStateDiffCallback()) {

    private var expandedPosition = -1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MedicamentoViewHolder {
        val binding = MedicamentoItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MedicamentoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MedicamentoViewHolder, position: Int) {
        holder.itemView.animation = AnimationUtils.loadAnimation(holder.itemView.context, R.anim.item_load_in)
        holder.bind(getItem(position), position == expandedPosition)
    }

    inner class MedicamentoViewHolder(private val binding: MedicamentoItemBinding) : RecyclerView.ViewHolder(binding.root) {
        private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

        init {
            binding.root.setOnClickListener {
                toggleDetailsVisibility(bindingAdapterPosition)
            }
            binding.imageViewExpandCollapse.setOnClickListener {
                toggleDetailsVisibility(bindingAdapterPosition)
            }
            binding.buttonSkip.setOnClickListener {
                if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                    onSkipDoseClick(getItem(bindingAdapterPosition))
                }
            }
        }

        @SuppressLint("ResourceType")
        fun bind(uiState: MedicamentoUiState, isExpanded: Boolean) {
            val context = binding.root.context
            val medicamento = uiState.medicamento

            val errorColor = MaterialColors.getColor(context, colorError, Color.RED)
            val warningColor = ContextCompat.getColor(context, R.color.colorWarning)
            val primaryColor = MaterialColors.getColor(context, colorPrimary, Color.BLUE)

            binding.root.alpha = if (uiState.status == MedicamentoStatus.FINALIZADO) 0.6f else 1.0f

            val canTakeDose = podeRegistrarDose && uiState.status != MedicamentoStatus.FINALIZADO
            binding.buttonMarkAsTaken.isEnabled = canTakeDose

            if (!podeRegistrarDose) {
                TooltipCompat.setTooltipText(binding.buttonMarkAsTaken, context.getString(R.string.permission_denied_tooltip))
            } else {
                TooltipCompat.setTooltipText(binding.buttonMarkAsTaken, null)
            }


            binding.detailsLayout.visibility = if (isExpanded) View.VISIBLE else View.GONE
            binding.imageViewExpandCollapse.rotation = if (isExpanded) 180f else 0f

            binding.textViewNome.text = medicamento.nome
            binding.textViewDosagem.text = medicamento.dosagem

            binding.iconTipoMedicamento.setImageResource(when (medicamento.tipo) {
                TipoMedicamento.ORAL -> R.drawable.ic_pill
                TipoMedicamento.TOPICO -> R.drawable.ic_lotion
                TipoMedicamento.INJETAVEL -> R.drawable.ic_injection
            })

            updateStatusIndicators(uiState, context)
            updateAdherenceProgress(uiState)


            if (isExpanded) {
                binding.textViewFrequenciaHorario.visibility = if (medicamento.isUsoEsporadico) View.GONE else View.VISIBLE
                binding.textViewDuracao.visibility = if (medicamento.isUsoEsporadico) View.GONE else View.VISIBLE

                binding.textViewFrequenciaHorario.text = "Horários: ${medicamento.horarios.sorted().joinToString(", ") { it.format(timeFormatter) }}"
                binding.textViewDuracao.text = if (medicamento.isUsoContinuo) "Uso contínuo" else "Duração: ${medicamento.duracaoDias} dia(s)"

                if (medicamento.anotacoes.isNullOrBlank()) {
                    binding.anotacoesLayout.visibility = View.GONE
                } else {
                    binding.anotacoesLayout.visibility = View.VISIBLE
                    binding.textViewAnotacoes.text = medicamento.anotacoes
                }

                if (medicamento.tipo == TipoMedicamento.TOPICO || medicamento.tipo == TipoMedicamento.INJETAVEL) {
                    binding.textViewUltimoLocal.visibility = if (uiState.ultimoLocalAplicado != null) View.VISIBLE else View.GONE
                    binding.textViewUltimoLocal.text = "Última aplicação: ${uiState.ultimoLocalAplicado ?: "N/A"}"
                    binding.textViewProximoLocal.visibility = if (uiState.proximoLocalSugerido != null) View.VISIBLE else View.GONE
                    binding.textViewProximoLocal.text = "Próxima aplicação: ${uiState.proximoLocalSugerido ?: "N/A"}"
                } else {
                    binding.textViewUltimoLocal.visibility = View.GONE
                    binding.textViewProximoLocal.visibility = View.GONE
                }

                if (isCaregiver) {
                    binding.actionsLayout.visibility = View.VISIBLE
                    binding.buttonPausePlay.setIconResource(if (medicamento.isPaused) R.drawable.ic_play_arrow else R.drawable.ic_pause)
                    binding.buttonPausePlay.contentDescription = if (medicamento.isPaused) "Ativar" else "Pausar"
                    binding.buttonPausePlay.setOnClickListener { onPausePlayClick(uiState) }
                    binding.buttonEdit.setOnClickListener { onEditClick(uiState) }
                    binding.buttonDelete.setOnClickListener { onDeleteClick(uiState) }

                    val isEligibleToSkip = !medicamento.isPaused &&
                            (uiState.status == MedicamentoStatus.PROXIMA_DOSE || uiState.status == MedicamentoStatus.ATRASADO)
                    binding.buttonSkip.isVisible = isEligibleToSkip && podeRegistrarDose

                } else {
                    binding.actionsLayout.visibility = View.GONE
                }
            }

            // --- Lógica do Bloco de Estoque (Detalhado e Resumido) ---
            val hasStockInfo = medicamento.lotes.isNotEmpty() || medicamento.nivelDeAlertaEstoque > 0
            val currentStock = medicamento.estoqueAtualTotal
            val initialStock = medicamento.estoqueInicialTotal

            // Visibilidade dos blocos de estoque
            binding.estoqueLayout.visibility = if (isExpanded && hasStockInfo) View.VISIBLE else View.GONE
            binding.summaryStockLayout.visibility = if (!isExpanded && hasStockInfo) View.VISIBLE else View.GONE // ✅ ADIÇÃO

            if(hasStockInfo) {
                val percentual = if (initialStock > 0) (currentStock / initialStock) * 100 else 0.0
                val progress = currentStock.toInt()
                // Garante que o max não seja 0 se o estoque inicial for 0 mas o atual não
                val maxProgress = (if(initialStock > 0) initialStock else currentStock).toInt().coerceAtLeast(1)

                val loteMaisProximo = medicamento.lotes
                    .filter { it.dataValidade.isAfter(LocalDate.now().minusDays(1)) }
                    .minByOrNull { it.dataValidade }

                val corProgresso = when {
                    currentStock <= 0 -> errorColor
                    medicamento.nivelDeAlertaEstoque > 0 && currentStock <= medicamento.nivelDeAlertaEstoque -> errorColor
                    loteMaisProximo != null && loteMaisProximo.dataValidade.isBefore(LocalDate.now().plusDays(30)) -> warningColor
                    percentual <= 50 -> warningColor
                    else -> primaryColor
                }

                if (isExpanded) {
                    // --- Preenche o Bloco de Estoque DETALHADO ---
                    if (currentStock <= 0) {
                        binding.progressEstoque.visibility = View.GONE
                        binding.textViewEstoque.text = "Estoque zerado"
                        binding.textViewEstoque.setTextColor(errorColor)
                        binding.buttonRefill.text = "Repor"
                    } else {
                        binding.progressEstoque.visibility = View.VISIBLE
                        binding.textViewEstoque.setTextColor(MaterialColors.getColor(context, MaterialR.attr.colorOnSurfaceVariant, Color.GRAY))
                        binding.progressEstoque.max = maxProgress
                        binding.progressEstoque.progress = progress
                        binding.textViewEstoque.text = String.format("%.1f de %.1f %s restantes", currentStock, initialStock, medicamento.unidadeDeEstoque)
                        binding.buttonRefill.text = "Repor"
                    }

                    if (loteMaisProximo != null) {
                        binding.textViewValidadeProxima.visibility = View.VISIBLE
                        binding.textViewValidadeProxima.text = "Vencimento próximo: ${loteMaisProximo.dataValidade.format(dateFormatter)}"
                        binding.textViewValidadeProxima.setTextColor(when {
                            loteMaisProximo.dataValidade.isBefore(LocalDate.now()) -> errorColor
                            loteMaisProximo.dataValidade.isBefore(LocalDate.now().plusDays(30)) -> warningColor
                            else -> MaterialColors.getColor(context, MaterialR.attr.colorOnSurfaceVariant, Color.GRAY)
                        })
                    } else {
                        binding.textViewValidadeProxima.visibility = View.VISIBLE
                        binding.textViewValidadeProxima.text = if (currentStock > 0) "Todos os lotes estão vencidos" else "Sem lotes válidos"
                        binding.textViewValidadeProxima.setTextColor(errorColor)
                    }

                    binding.progressEstoque.setIndicatorColor(corProgresso)
                    binding.buttonRefill.setOnClickListener { onRefillClick(uiState) }
                    binding.buttonRefill.visibility = if (isCaregiver) View.VISIBLE else View.GONE

                } else {
                    // --- ✅ ADIÇÃO: Preenche o Bloco de Estoque RESUMIDO ---
                    binding.progressEstoqueResumido.max = maxProgress
                    binding.progressEstoqueResumido.progress = progress
                    binding.progressEstoqueResumido.setIndicatorColor(corProgresso)

                    if (currentStock <= 0) {
                        binding.textViewEstoqueResumido.text = "Estoque zerado!"
                        binding.textViewEstoqueResumido.setTextColor(errorColor)
                    } else if (medicamento.nivelDeAlertaEstoque > 0 && currentStock <= medicamento.nivelDeAlertaEstoque) {
                        binding.textViewEstoqueResumido.text = "Estoque baixo: ${currentStock.toInt()} ${medicamento.unidadeDeEstoque}"
                        binding.textViewEstoqueResumido.setTextColor(errorColor)
                    } else {
                        binding.textViewEstoqueResumido.text = "Estoque: ${percentual.toInt()}%"
                        binding.textViewEstoqueResumido.setTextColor(MaterialColors.getColor(context, MaterialR.attr.colorOnSurfaceVariant, Color.GRAY))
                    }
                }
            } else {
                // Se não houver info de estoque, esconde os dois blocos
                binding.estoqueLayout.visibility = View.GONE
                binding.summaryStockLayout.visibility = View.GONE
            }

            binding.buttonMarkAsTaken.setOnClickListener { onMarkAsTakenClick(uiState) }
        }

        private fun toggleDetailsVisibility(position: Int) {
            if (position == RecyclerView.NO_POSITION) return
            val isCurrentlyExpanded = position == expandedPosition
            val oldExpanded = expandedPosition
            expandedPosition = if (isCurrentlyExpanded) -1 else position
            if (oldExpanded != -1) {
                notifyItemChanged(oldExpanded)
            }
            if (expandedPosition != -1) {
                notifyItemChanged(expandedPosition)
            }
        }

        @SuppressLint("ResourceType")
        private fun updateStatusIndicators(uiState: MedicamentoUiState, context: Context) {
            binding.textViewStatus.text = uiState.statusText
            val color: Int
            val icon: Int
            when (uiState.status) {
                MedicamentoStatus.ATRASADO -> {
                    color = MaterialColors.getColor(context, colorError, Color.RED)
                    icon = R.drawable.ic_alert
                }
                MedicamentoStatus.PROXIMA_DOSE -> {
                    color = MaterialColors.getColor(context, colorPrimary, Color.BLUE)
                    icon = R.drawable.ic_alarm
                }
                MedicamentoStatus.ESPORADICO -> {
                    color = MaterialColors.getColor(context, MaterialR.attr.colorSecondary, Color.CYAN)
                    icon = R.drawable.ic_add_circle
                }
                MedicamentoStatus.FINALIZADO,
                MedicamentoStatus.SEM_NOTIFICACAO -> {
                    color = ContextCompat.getColor(context, R.color.success_green)
                    icon = R.drawable.ic_check
                }
                MedicamentoStatus.PAUSADO -> {
                    color = MaterialColors.getColor(context, MaterialR.attr.colorOutline, Color.GRAY)
                    icon = R.drawable.ic_pause
                }
            }
            binding.textViewStatus.setTextColor(color)
            binding.iconStatus.setImageResource(icon)
            binding.iconStatus.setColorFilter(color)
            binding.statusLayout.visibility = if (uiState.status == MedicamentoStatus.SEM_NOTIFICACAO) View.GONE else View.VISIBLE
            val showSporadicButton = uiState.status == MedicamentoStatus.ESPORADICO
            val showScheduledButton = uiState.status == MedicamentoStatus.PROXIMA_DOSE || uiState.status == MedicamentoStatus.ATRASADO
            if (showSporadicButton) {
                binding.buttonMarkAsTaken.text = "Registar Dose"
                binding.buttonMarkAsTaken.visibility = if (podeRegistrarDose) View.VISIBLE else View.GONE
            } else {
                binding.buttonMarkAsTaken.text = "Tomar Agora"
                binding.buttonMarkAsTaken.visibility = if (showScheduledButton && podeRegistrarDose) View.VISIBLE else View.GONE
            }
        }

        private fun updateAdherenceProgress(uiState: MedicamentoUiState) {
            if (uiState.medicamento.isUsoEsporadico) {
                binding.adherenceGroup.visibility = View.GONE
                return
            }
            binding.adherenceGroup.visibility = View.VISIBLE
            binding.progressAdherence.max = if (uiState.dosesEsperadasHoje > 0) uiState.dosesEsperadasHoje else 1
            binding.progressAdherence.progress = min(uiState.dosesTomadasHoje, uiState.dosesEsperadasHoje)
            binding.textViewAdherence.text = "${uiState.dosesTomadasHoje} de ${uiState.dosesEsperadasHoje} doses hoje"
            if (uiState.adherenceStatus == AdherenceStatus.OVERDOSE) {
                val errorColor = MaterialColors.getColor(binding.root.context, colorError, Color.RED)
                binding.progressAdherence.progressTintList = ColorStateList.valueOf(errorColor)
                binding.textViewOverdoseWarning.visibility = View.VISIBLE
            } else {
                val primaryColor = MaterialColors.getColor(binding.root.context, colorPrimary, Color.BLUE)
                binding.progressAdherence.progressTintList = ColorStateList.valueOf(primaryColor)
                binding.textViewOverdoseWarning.visibility = View.GONE
            }
        }
    }

    private class MedicamentoUiStateDiffCallback : DiffUtil.ItemCallback<MedicamentoUiState>() {
        override fun areItemsTheSame(oldItem: MedicamentoUiState, newItem: MedicamentoUiState): Boolean {
            return oldItem.medicamento.id == newItem.medicamento.id
        }
        override fun areContentsTheSame(oldItem: MedicamentoUiState, newItem: MedicamentoUiState): Boolean {
            return oldItem == newItem
        }
    }
}