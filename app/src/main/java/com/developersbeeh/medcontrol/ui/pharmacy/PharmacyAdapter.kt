package com.developersbeeh.medcontrol.ui.pharmacy

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.developersbeeh.medcontrol.R
import com.developersbeeh.medcontrol.data.remote.Place
import com.developersbeeh.medcontrol.databinding.ItemPharmacyBinding

class PharmacyAdapter(
    private val onPharmacyClick: (Place) -> Unit,
    // ✅ ADIÇÃO: Novo callback para o clique no menu de opções
    private val onOptionsClick: (Place, View) -> Unit
) : ListAdapter<Place, PharmacyAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPharmacyBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemPharmacyBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            // Clique no card inteiro continua levando para a seleção de medicamentos
            binding.root.setOnClickListener {
                if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                    onPharmacyClick(getItem(bindingAdapterPosition))
                }
            }

            // ✅ ADIÇÃO: Listener para o botão de menu
            binding.buttonMenu.setOnClickListener {
                if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                    onOptionsClick(getItem(bindingAdapterPosition), it)
                }
            }
        }

        fun bind(place: Place) {
            val context = binding.root.context
            binding.textViewPharmacyName.text = place.name
            binding.textViewPharmacyAddress.text = place.vicinity

            if (place.openingHours?.openNow == true) {
                binding.textViewOpenStatus.text = "Aberta agora"
                binding.textViewOpenStatus.setTextColor(ContextCompat.getColor(context, R.color.success_green))
            } else {
                binding.textViewOpenStatus.text = "Fechada"
                binding.textViewOpenStatus.setTextColor(ContextCompat.getColor(context, R.color.error_red))
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Place>() {
        override fun areItemsTheSame(oldItem: Place, newItem: Place): Boolean {
            return oldItem.placeId == newItem.placeId
        }

        override fun areContentsTheSame(oldItem: Place, newItem: Place): Boolean {
            return oldItem == newItem
        }
    }
}