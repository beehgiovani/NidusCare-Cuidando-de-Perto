package com.developersbeeh.medcontrol.ui.cycletracker

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.developersbeeh.medcontrol.data.model.CycleSummary
import com.developersbeeh.medcontrol.databinding.CycleHistoryItemBinding
import java.time.format.DateTimeFormatter
import java.util.Locale

class CycleHistoryAdapter : ListAdapter<CycleSummary, CycleHistoryAdapter.CycleHistoryViewHolder>(CycleDiffCallback()) {

    // Formatter para exibir a data de forma amigável
    private val dateFormatter = DateTimeFormatter.ofPattern("dd 'de' MMM. 'de' yyyy", Locale("pt", "BR"))

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CycleHistoryViewHolder {
        val binding = CycleHistoryItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CycleHistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CycleHistoryViewHolder, position: Int) {
        val cycle = getItem(position)
        holder.bind(cycle, dateFormatter)
    }

    class CycleHistoryViewHolder(private val binding: CycleHistoryItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(cycle: CycleSummary, formatter: DateTimeFormatter) {
            binding.textViewStartDate.text = cycle.startDate.format(formatter)
            binding.textViewCycleLength.text = "Ciclo: ${cycle.cycleLength} dias"
            binding.textViewPeriodLength.text = "Período: ${cycle.periodLength} dias"
        }
    }
}

class CycleDiffCallback : DiffUtil.ItemCallback<CycleSummary>() {
    override fun areItemsTheSame(oldItem: CycleSummary, newItem: CycleSummary): Boolean {
        return oldItem.startDate == newItem.startDate
    }

    override fun areContentsTheSame(oldItem: CycleSummary, newItem: CycleSummary): Boolean {
        return oldItem == newItem
    }
}