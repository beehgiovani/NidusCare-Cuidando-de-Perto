package com.developersbeeh.medcontrol.ui.cycletracker

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.developersbeeh.medcontrol.databinding.FragmentCycleHistoryBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class CycleHistoryFragment : Fragment() {

    private var _binding: FragmentCycleHistoryBinding? = null
    private val binding get() = _binding!!

    // Usamos 'activityViewModels' para compartilhar o mesmo ViewModel da tela do calendário
    private val viewModel: CycleTrackerViewModel by activityViewModels()
    private lateinit var historyAdapter: CycleHistoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCycleHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupRecyclerView()
        observeViewModel()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            // Ação para voltar para a tela anterior
            findNavController().navigateUp()
        }
    }

    private fun setupRecyclerView() {
        historyAdapter = CycleHistoryAdapter()
        binding.recyclerViewHistory.adapter = historyAdapter
    }

    private fun observeViewModel() {
        viewModel.cycleHistory.observe(viewLifecycleOwner) { historyList ->
            // Mostra o estado de lista vazia se a lista for nula ou vazia
            binding.textViewEmptyState.isVisible = historyList.isNullOrEmpty()
            binding.recyclerViewHistory.isVisible = !historyList.isNullOrEmpty()

            historyAdapter.submitList(historyList)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}