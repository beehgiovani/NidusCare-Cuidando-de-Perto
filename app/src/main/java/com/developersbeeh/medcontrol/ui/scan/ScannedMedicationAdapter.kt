// src/main/java/com/developersbeeh/niduscare/ui/scan/ScannedMedicationAdapter.kt
package com.developersbeeh.medcontrol.ui.scan

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.developersbeeh.medcontrol.R
import com.developersbeeh.medcontrol.data.model.Medicamento
import com.developersbeeh.medcontrol.databinding.ItemScannedMedicationBinding

class ScannedMedicationAdapter(
    private val onEditClick: (Medicamento) -> Unit,
    private val onDeleteClick: (Medicamento) -> Unit
) : ListAdapter<Medicamento, ScannedMedicationAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemScannedMedicationBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemScannedMedicationBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(medicamento: Medicamento) {
            binding.textViewName.text = medicamento.nome
            binding.textViewDosage.text = medicamento.dosagem.ifBlank { "Dosagem: Não identificada" }

            // Verifica se a posologia foi identificada
            val hasPosologia = medicamento.horarios.isNotEmpty() || medicamento.isUsoEsporadico
            binding.textViewPosologyStatus.text = if (hasPosologia) "Posologia: OK" else "Posologia: Pendente"
            binding.textViewPosologyStatus.setTextColor(
                if (hasPosologia) binding.root.context.getColor(R.color.success_green)
                else binding.root.context.getColor(R.color.error_red)
            )

            binding.buttonEdit.setOnClickListener { onEditClick(medicamento) }
            binding.buttonDelete.setOnClickListener { onDeleteClick(medicamento) }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<Medicamento>() {
        override fun areItemsTheSame(oldItem: Medicamento, newItem: Medicamento): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Medicamento, newItem: Medicamento): Boolean {
            return oldItem == newItem
        }
    }
}