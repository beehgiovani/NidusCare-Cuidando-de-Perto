// src/main/java/com/developersbeeh/medcontrol/ui/sleep/SleepHistoryAdapter.kt
package com.developersbeeh.medcontrol.ui.sleep

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.developersbeeh.medcontrol.R
import com.developersbeeh.medcontrol.data.model.QualidadeSono
import com.developersbeeh.medcontrol.data.model.RegistroSono
import com.developersbeeh.medcontrol.databinding.ItemSleepHistoryBinding
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.Locale

class SleepHistoryAdapter : ListAdapter<RegistroSono, SleepHistoryAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSleepHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemSleepHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val dateFormatter = DateTimeFormatter.ofPattern("dd MMM", Locale("pt", "BR"))

        fun bind(record: RegistroSono) {
            val context = binding.root.context

            binding.textViewDate.text = record.getDataAsLocalDate().format(dateFormatter)
            binding.textViewTimeRange.text = "${record.horaDeDormir} - ${record.horaDeAcordar}"

            val start = record.getHoraDeDormirAsLocalTime()
            val end = record.getHoraDeAcordarAsLocalTime()
            val duration = Duration.between(start, end)
            val totalDuration = if (duration.isNegative) duration.plusDays(1) else duration
            val hours = totalDuration.toHours()
            val minutes = totalDuration.toMinutes() % 60
            binding.textViewDuration.text = "${hours}h ${minutes}m"

            val quality = try { QualidadeSono.valueOf(record.qualidade) } catch (e: Exception) { QualidadeSono.RAZOAVEL }
            binding.textViewQuality.text = quality.displayName

            val qualityColor = when (quality) {
                QualidadeSono.BOM -> R.color.success_green
                QualidadeSono.RAZOAVEL -> R.color.warning_orange
                QualidadeSono.RUIM -> R.color.error_red
            }
            binding.textViewQuality.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, qualityColor))
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<RegistroSono>() {
        override fun areItemsTheSame(oldItem: RegistroSono, newItem: RegistroSono): Boolean {
            return oldItem.id == newItem.id
        }
        override fun areContentsTheSame(oldItem: RegistroSono, newItem: RegistroSono): Boolean {
            return oldItem == newItem
        }
    }
}