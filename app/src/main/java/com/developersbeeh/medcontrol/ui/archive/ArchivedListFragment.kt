// src/main/java/com/developersbeeh/medcontrol/ui/archive/ArchivedListFragment.kt
package com.developersbeeh.medcontrol.ui.archive

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.developersbeeh.medcontrol.databinding.FragmentArchivedListBinding
import android.widget.Toast
import com.developersbeeh.medcontrol.R
import com.developersbeeh.medcontrol.data.model.EstoqueLote
import com.developersbeeh.medcontrol.data.model.Medicamento
import com.developersbeeh.medcontrol.databinding.DialogRefillStockBinding
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ArchivedListFragment : Fragment() {

    private var _binding: FragmentArchivedListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ArchivedMedicationsViewModel by activityViewModels()
    private var filterType: FilterType? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            filterType = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                it.getSerializable(ARG_FILTER_TYPE, FilterType::class.java)
            } else {
                @Suppress("DEPRECATION")
                it.getSerializable(ARG_FILTER_TYPE) as? FilterType
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentArchivedListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = ArchivedMedicationAdapter(
            filterType = filterType!!,
            onPrimaryAction = { medicamento -> handlePrimaryAction(medicamento) },
            onSecondaryAction = { medicamento -> handleSecondaryAction(medicamento) }
        )
        binding.recyclerViewArchived.layoutManager = LinearLayoutManager(context)
        binding.recyclerViewArchived.adapter = adapter

        viewModel.actionFeedback.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let {
                Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            }
        }

        when (filterType) {
            FilterType.FINISHED -> viewModel.finishedTreatments.observe(viewLifecycleOwner) { meds ->
                binding.textViewEmptyState.visibility = if (meds.isEmpty()) View.VISIBLE else View.GONE
                adapter.submitList(meds.map { it to "Tratamento concluído" })
            }
            FilterType.ZERO_STOCK -> viewModel.zeroStockMeds.observe(viewLifecycleOwner) { meds ->
                binding.textViewEmptyState.visibility = if (meds.isEmpty()) View.VISIBLE else View.GONE
                adapter.submitList(meds.map { it to "Estoque zerado" })
            }
            FilterType.EXPIRED -> viewModel.expiredStockMeds.observe(viewLifecycleOwner) { meds ->
                binding.textViewEmptyState.visibility = if (meds.isEmpty()) View.VISIBLE else View.GONE
                adapter.submitList(meds.map { it to "Contém lotes vencidos" })
            }
            null -> {}
        }
    }

    private fun handlePrimaryAction(medicamento: Medicamento) {
        when (filterType) {
            // ✅ CORREÇÃO: Chamando a função correta 'restoreMedicamento'
            FilterType.FINISHED -> viewModel.restoreMedicamento(medicamento)
            FilterType.ZERO_STOCK -> showRefillStockDialog(medicamento)
            FilterType.EXPIRED -> viewModel.removeExpiredLots(medicamento)
            else -> {}
        }
    }

    private fun handleSecondaryAction(medicamento: Medicamento) {
        when (filterType) {
            FilterType.FINISHED -> showDeleteConfirmationDialog(medicamento)
            FilterType.ZERO_STOCK -> viewModel.stopTrackingStock(medicamento)
            FilterType.EXPIRED -> showRefillStockDialog(medicamento)
            else -> {}
        }
    }

    private fun showDeleteConfirmationDialog(medicamento: Medicamento) {
        MaterialAlertDialogBuilder(requireContext(), R.style.AppTheme_DialogAnimation)
            .setTitle("Excluir Permanentemente?")
            .setMessage("Esta ação excluirá '${medicamento.nome}' e todo o seu histórico de doses. Esta ação não pode ser desfeita.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Excluir") { _, _ ->
                // ✅ CORREÇÃO: Chamando a função correta 'deleteMedicationPermanently'
                viewModel.deleteMedicationPermanently(medicamento)
            }
            .show()
    }

    private fun showRefillStockDialog(medicamento: Medicamento) {
        val dialogBinding = DialogRefillStockBinding.inflate(LayoutInflater.from(context))
        dialogBinding.tilRefillAmount.hint = "Adicionar (em ${medicamento.unidadeDeEstoque})"
        val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        var selectedDate: LocalDate? = null

        dialogBinding.editTextExpirationDate.setOnClickListener {
            val datePicker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Selecione a Data de Validade")
                .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
                .build()

            datePicker.addOnPositiveButtonClickListener { selection ->
                selectedDate = Instant.ofEpochMilli(selection).atZone(ZoneId.systemDefault()).toLocalDate()
                dialogBinding.editTextExpirationDate.setText(selectedDate?.format(dateFormatter))
            }
            datePicker.show(childFragmentManager, "REFILL_ARCHIVE_DATE_PICKER")
        }

        MaterialAlertDialogBuilder(requireContext(), R.style.AppTheme_DialogAnimation)
            .setTitle("Repor Estoque de ${medicamento.nome}")
            .setView(dialogBinding.root)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Adicionar") { _, _ ->
                val amountStr = dialogBinding.editTextRefillAmount.text.toString().replace(',', '.')
                val amount = amountStr.toDoubleOrNull()

                if (amount == null || amount <= 0) {
                    Toast.makeText(context, "Quantidade inválida.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (selectedDate == null) {
                    Toast.makeText(context, "Selecione a data de validade.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val novoLote = EstoqueLote(
                    quantidade = amount,
                    quantidadeInicial = amount,
                    dataValidadeString = selectedDate!!.format(DateTimeFormatter.ISO_LOCAL_DATE)
                )
                viewModel.addStockLot(medicamento, novoLote)
            }
            .create()
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_FILTER_TYPE = "filter_type"
        enum class FilterType { FINISHED, ZERO_STOCK, EXPIRED }

        fun newInstance(filterType: FilterType) =
            ArchivedListFragment().apply {
                arguments = bundleOf(ARG_FILTER_TYPE to filterType)
            }
    }
}