package com.developersbeeh.medcontrol.ui.timeline

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import com.developersbeeh.medcontrol.NavGraphDirections
import com.developersbeeh.medcontrol.R
import com.developersbeeh.medcontrol.data.UserPreferences
import com.developersbeeh.medcontrol.databinding.FragmentTimelineBinding
import com.developersbeeh.medcontrol.databinding.LayoutEmptyStateBinding
import com.developersbeeh.medcontrol.databinding.LayoutErrorStateBinding
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.MobileAds
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class TimelineFragment : Fragment() {

    private lateinit var userPreferences: UserPreferences
    private var _binding: FragmentTimelineBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TimelineViewModel by viewModels()
    private val args: TimelineFragmentArgs by navArgs()
    private lateinit var adapter: TimelineAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTimelineBinding.inflate(inflater, container, false)
        return binding.root
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.initialize(args.dependentId)
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Linha do Tempo de ${args.dependentName}"
        userPreferences = UserPreferences(requireContext())
        setupRecyclerView()
        setupFilters()
        setupRefreshListener()
        observeViewModel()
        setupAds()
    }

    private fun setupRecyclerView() {
        adapter = TimelineAdapter()
        binding.recyclerViewTimeline.adapter = adapter
        binding.recyclerViewTimeline.layoutManager = LinearLayoutManager(requireContext())

        // ✅ CORREÇÃO: Lógica do listener refeita para tratar todos os estados de UI
        adapter.addLoadStateListener { loadState ->
            val refreshState = loadState.refresh

            // Mostra o Shimmer apenas no carregamento inicial
            binding.shimmerLayout.isVisible = refreshState is LoadState.Loading && adapter.itemCount == 0
            // O spinner do SwipeRefresh aparece em qualquer carregamento (inicial ou manual)
            binding.swipeRefreshLayout.isRefreshing = refreshState is LoadState.Loading

            // O conteúdo (RecyclerView) só é visível quando o carregamento termina e há itens
            val hasContent = refreshState is LoadState.NotLoading && adapter.itemCount > 0
            binding.recyclerViewTimeline.isVisible = hasContent

            // O estado de vazio só é visível quando o carregamento termina e não há itens
            val isEmpty = refreshState is LoadState.NotLoading && adapter.itemCount == 0
            binding.emptyStateLayout.root.isVisible = isEmpty
            if (isEmpty) {
                setupEmptyState()
            }

            // O estado de erro só é visível se ocorrer um erro
            val hasError = refreshState is LoadState.Error
            binding.errorStateLayout.root.isVisible = hasError
            if (hasError) {
                LayoutErrorStateBinding.bind(binding.errorStateLayout.root).textViewErrorMessage.text = refreshState.error.localizedMessage
            }
        }
    }

    private fun setupRefreshListener() {
        binding.swipeRefreshLayout.setOnRefreshListener {
            adapter.refresh()
        }
        binding.errorStateLayout.buttonRetry.setOnClickListener {
            adapter.retry()
        }
    }

    private fun setupAds() {
        MobileAds.initialize(requireContext()) {}
        if (!userPreferences.isPremium()) {
            binding.adView.visibility = View.VISIBLE
            val adRequest = AdRequest.Builder().build()
            binding.adView.loadAd(adRequest)
        } else {
            binding.adView.visibility = View.GONE
        }
    }

    private fun setupFilters() {
        binding.chipGroupFilter.setOnCheckedStateChangeListener { group, checkedIds ->
            val filter = if (checkedIds.isEmpty()) {
                group.check(R.id.chipFilterAll)
                TimelineFilter.ALL
            } else {
                when (checkedIds.first()) {
                    R.id.chipFilterDoses -> TimelineFilter.DOSE
                    R.id.chipFilterNotes -> TimelineFilter.NOTE
                    R.id.chipFilterOtherActivities -> TimelineFilter.ACTIVITY
                    R.id.chipFilterInsights -> TimelineFilter.INSIGHT
                    else -> TimelineFilter.ALL
                }
            }
            viewModel.setFilter(filter)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.timelinePagerFlow.collectLatest { pagingData ->
                adapter.submitData(pagingData)
            }
        }
    }

    private fun setupEmptyState() {
        val emptyBinding = LayoutEmptyStateBinding.bind(binding.emptyStateLayout.root)
        emptyBinding.lottieAnimationView.setAnimation(R.raw.empty_list)
        emptyBinding.textViewEmptyTitle.text = "Nenhuma Atividade"
        emptyBinding.textViewEmptySubtitle.text = "Quando você registrar doses, anotações ou outras atividades, elas aparecerão aqui."
        emptyBinding.buttonEmptyAction.text = "Registrar Atividade"
        emptyBinding.buttonEmptyAction.setOnClickListener {
            val action = NavGraphDirections.actionGlobalToWellbeingDiaryFragment(args.dependentId, args.dependentName)
            findNavController().navigate(action)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}