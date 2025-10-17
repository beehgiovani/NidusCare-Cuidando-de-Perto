package com.developersbeeh.medcontrol.ui.archive

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.developersbeeh.medcontrol.data.model.Medicamento
import com.developersbeeh.medcontrol.databinding.ItemArchivedMedicationBinding

class ArchivedMedicationAdapter(
    private val filterType: ArchivedListFragment.Companion.FilterType,
    private val onPrimaryAction: (Medicamento) -> Unit,
    private val onSecondaryAction: (Medicamento) -> Unit
) : ListAdapter<Pair<Medicamento, String>, ArchivedMedicationAdapter.ViewHolder>(DiffCallback()) {

    inner class ViewHolder(private val binding: ItemArchivedMedicationBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(pair: Pair<Medicamento, String>) {
            val medicamento = pair.first
            val reason = pair.second

            binding.textViewMedicationName.text = medicamento.nome
            binding.textViewArchiveReason.text = reason

            // Configura os botões com base no tipo de filtro da lista
            when (filterType) {
                ArchivedListFragment.Companion.FilterType.FINISHED -> {
                    binding.buttonPrimaryAction.text = "Reativar Tratamento"
                    binding.buttonSecondaryAction.text = "Excluir"
                }
                ArchivedListFragment.Companion.FilterType.ZERO_STOCK -> {
                    binding.buttonPrimaryAction.text = "Repor Estoque"
                    binding.buttonSecondaryAction.text = "Parar de Rastrear"
                }
                ArchivedListFragment.Companion.FilterType.EXPIRED -> {
                    binding.buttonPrimaryAction.text = "Remover Vencidos"
                    binding.buttonSecondaryAction.text = "Repor Estoque"
                }
            }

            binding.buttonPrimaryAction.setOnClickListener { onPrimaryAction(medicamento) }
            binding.buttonSecondaryAction.setOnClickListener { onSecondaryAction(medicamento) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemArchivedMedicationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DiffCallback : DiffUtil.ItemCallback<Pair<Medicamento, String>>() {
        override fun areItemsTheSame(oldItem: Pair<Medicamento, String>, newItem: Pair<Medicamento, String>): Boolean {
            return oldItem.first.id == newItem.first.id
        }

        override fun areContentsTheSame(oldItem: Pair<Medicamento, String>, newItem: Pair<Medicamento, String>): Boolean {
            return oldItem == newItem
        }
    }
}