// src/main/java/com/developersbeeh/medcontrol/ui/weight/WeightHistoryAdapter.kt
package com.developersbeeh.medcontrol.ui.weight

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.developersbeeh.medcontrol.R
import com.developersbeeh.medcontrol.data.model.HealthNote
import com.developersbeeh.medcontrol.databinding.ItemWeightHistoryBinding
import java.time.format.DateTimeFormatter
import java.util.Locale

class WeightHistoryAdapter : ListAdapter<HealthNote, WeightHistoryAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemWeightHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val previousItem = if (position < itemCount - 1) getItem(position + 1) else null
        holder.bind(getItem(position), previousItem)
    }

    inner class ViewHolder(private val binding: ItemWeightHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val dateFormatter = DateTimeFormatter.ofPattern("dd MMM", Locale("pt", "BR"))

        fun bind(record: HealthNote, previousRecord: HealthNote?) {
            val context = binding.root.context
            val currentWeight = record.values["weight"]?.replace(',', '.')?.toFloatOrNull()
            if (currentWeight == null) {
                binding.root.isVisible = false
                return
            }

            binding.root.isVisible = true
            binding.textViewDate.text = record.timestamp.toLocalDate().format(dateFormatter)
            binding.textViewWeight.text = "${currentWeight} kg"

            val previousWeight = previousRecord?.values?.get("weight")?.replace(',', '.')?.toFloatOrNull()
            if (previousWeight != null) {
                val difference = currentWeight - previousWeight
                binding.textViewDifference.isVisible = true
                binding.imageViewTrend.isVisible = true

                when {
                    difference > 0.1f -> { // Ganho de peso
                        binding.imageViewTrend.setImageResource(R.drawable.ic_trending_up)
                        binding.imageViewTrend.setColorFilter(ContextCompat.getColor(context, R.color.error_red))
                        binding.textViewDifference.setTextColor(ContextCompat.getColor(context, R.color.error_red))
                        binding.textViewDifference.text = "+%.1f kg".format(difference)
                    }
                    difference < -0.1f -> { // Perda de peso
                        binding.imageViewTrend.setImageResource(R.drawable.ic_trending_down)
                        binding.imageViewTrend.setColorFilter(ContextCompat.getColor(context, R.color.success_green))
                        binding.textViewDifference.setTextColor(ContextCompat.getColor(context, R.color.success_green))
                        binding.textViewDifference.text = "%.1f kg".format(difference)
                    }
                    else -> { // Manteve
                        binding.imageViewTrend.setImageResource(R.drawable.ic_trending_flat)
                        binding.imageViewTrend.setColorFilter(ContextCompat.getColor(context, R.color.md_theme_onSurfaceVariant))
                        binding.textViewDifference.setTextColor(ContextCompat.getColor(context, R.color.md_theme_onSurfaceVariant))
                        binding.textViewDifference.text = "0.0 kg"
                    }
                }
            } else {
                binding.textViewDifference.isVisible = false
                binding.imageViewTrend.isVisible = false
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<HealthNote>() {
        override fun areItemsTheSame(oldItem: HealthNote, newItem: HealthNote): Boolean {
            return oldItem.id == newItem.id
        }
        override fun areContentsTheSame(oldItem: HealthNote, newItem: HealthNote): Boolean {
            return oldItem == newItem
        }
    }
}