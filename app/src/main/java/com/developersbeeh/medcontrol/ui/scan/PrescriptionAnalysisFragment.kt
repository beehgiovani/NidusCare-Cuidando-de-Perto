// src/main/java/com/developersbeeh/medcontrol/ui/scan/PrescriptionAnalysisFragment.kt
package com.developersbeeh.medcontrol.ui.scan

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.developersbeeh.medcontrol.R
import com.developersbeeh.medcontrol.data.model.Medicamento
import com.developersbeeh.medcontrol.databinding.FragmentPrescriptionAnalysisBinding
import com.developersbeeh.medcontrol.ui.common.LoadingDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import java.util.Calendar

@AndroidEntryPoint
class PrescriptionAnalysisFragment : Fragment() {

    private var _binding: FragmentPrescriptionAnalysisBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PrescriptionAnalysisViewModel by viewModels()
    private val args: PrescriptionAnalysisFragmentArgs by navArgs()
    private lateinit var adapter: ScannedMedicationAdapter
    private var loadingDialog: LoadingDialogFragment? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPrescriptionAnalysisBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Análise da Receita"
        viewModel.initialize(args.dependentId, args.imageUri, args.dependentName)
        setupRecyclerView()
        observeViewModel()
        setupClickListeners()
    }

    private fun setupRecyclerView() {
        adapter = ScannedMedicationAdapter(
            onEditClick = { medicamento -> navigateToEditMedicamento(medicamento) },
            onDeleteClick = { medicamento -> viewModel.removeMedicamentoFromList(medicamento) }
        )
        binding.recyclerViewMedications.adapter = adapter
    }

    private fun setupClickListeners() {
        binding.buttonSaveAll.setOnClickListener {
            viewModel.saveAllMedications()
        }
        binding.buttonCancel.setOnClickListener {
            findNavController().popBackStack()
        }
        binding.errorLayout.buttonRetry.setOnClickListener {
            viewModel.initialize(args.dependentId, args.imageUri, args.dependentName)
        }
    }

    private fun observeViewModel() {
        viewModel.showLoading.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let { message ->
                loadingDialog = LoadingDialogFragment.newInstance(message)
                loadingDialog?.show(childFragmentManager, LoadingDialogFragment.TAG)
            }
        }

        viewModel.hideLoading.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let {
                loadingDialog?.dismissAllowingStateLoss()
                loadingDialog = null
            }
        }

        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            binding.loadingLayout.visibility = View.GONE // Sempre esconde o layout antigo
            binding.contentLayout.visibility = if (state is PrescriptionAnalysisUiState.Success) View.VISIBLE else View.GONE
            binding.errorLayout.root.visibility = if (state is PrescriptionAnalysisUiState.Error) View.VISIBLE else View.GONE

            when (state) {
                is PrescriptionAnalysisUiState.Success -> {
                    adapter.submitList(state.medications)
                    binding.buttonSaveAll.isEnabled = state.medications.isNotEmpty()
                }
                is PrescriptionAnalysisUiState.Error -> {
                    binding.errorLayout.textViewErrorMessage.text = state.message
                }
            }
        }

        viewModel.saveStatus.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let { success ->
                val message = if (success) "Todos os medicamentos foram salvos!" else "Falha ao salvar um ou mais medicamentos."
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                if (success) {
                    findNavController().popBackStack(R.id.listMedicamentosFragment, false)
                }
            }
        }

        viewModel.requestStartTimeEvent.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let { medParaConfigurar ->
                showStartTimePicker(medParaConfigurar)
            }
        }
    }

    private fun showStartTimePicker(medicamento: Medicamento) {
        val calendar = Calendar.getInstance()
        val timePicker = TimePickerDialog(
            requireContext(),
            { _, hourOfDay, minute ->
                val selectedTime = java.time.LocalTime.of(hourOfDay, minute)
                viewModel.saveIncompleteMedication(medicamento, selectedTime)
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            true
        )
        timePicker.setTitle("Qual o horário da 1ª dose de ${medicamento.nome}?")
        timePicker.setCancelable(false)
        timePicker.show()
    }

    private fun navigateToEditMedicamento(medicamento: Medicamento) {
        val action = PrescriptionAnalysisFragmentDirections
            .actionPrescriptionAnalysisFragmentToAddMedicamentoFragment(
                medicamento = medicamento,
                dependentId = args.dependentId,
                dependentName = args.dependentName,
                isCaregiver = true
            )
        findNavController().navigate(action)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}