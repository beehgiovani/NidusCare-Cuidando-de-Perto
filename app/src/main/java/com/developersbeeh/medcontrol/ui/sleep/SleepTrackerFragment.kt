// src/main/java/com/developersbeeh/medcontrol/ui/sleep/SleepTrackerFragment.kt
package com.developersbeeh.medcontrol.ui.sleep

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.developersbeeh.medcontrol.R
import com.developersbeeh.medcontrol.data.model.QualidadeSono
import com.developersbeeh.medcontrol.data.model.RegistroSono
import com.developersbeeh.medcontrol.databinding.FragmentSleepTrackerBinding
import com.developersbeeh.medcontrol.util.UiState
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import dagger.hilt.android.AndroidEntryPoint
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@AndroidEntryPoint
class SleepTrackerFragment : Fragment() {

    private var _binding: FragmentSleepTrackerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SleepTrackerViewModel by viewModels()
    private val args: SleepTrackerFragmentArgs by navArgs()
    private val adapter by lazy { SleepHistoryAdapter() }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSleepTrackerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.initialize(args.dependentId)

        binding.recyclerViewSleepHistory.adapter = adapter
        binding.recyclerViewSleepHistory.layoutManager = LinearLayoutManager(requireContext())

        observeViewModel()
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            binding.progressBar.isVisible = state is UiState.Loading
            binding.emptyState.root.isVisible = state is UiState.Success && state.data.isEmpty()
            binding.recyclerViewSleepHistory.isVisible = state is UiState.Success

            if (state is UiState.Success) {
                if (state.data.isEmpty()) {
                    binding.emptyState.textViewErrorMessage.text = "Nenhum registro de sono encontrado."
                } else {
                    setupChart(state.data)
                    adapter.submitList(state.data)
                }
            } else if (state is UiState.Error) {
                binding.emptyState.root.isVisible = true
                binding.emptyState.textViewErrorMessage.text = state.message
            }
        }
    }

    private fun setupChart(records: List<RegistroSono>) {
        val sevenDaysAgo = LocalDate.now().minusDays(6)
        val recentRecords = records
            .filter { !it.getDataAsLocalDate().isBefore(sevenDaysAgo) }
            .associateBy { it.getDataAsLocalDate() }

        val entries = ArrayList<BarEntry>()
        val colors = ArrayList<Int>()
        val xAxisLabels = ArrayList<String>()
        val dayFormatter = DateTimeFormatter.ofPattern("dd/MM")

        for (i in 0..6) {
            val date = sevenDaysAgo.plusDays(i.toLong())
            val record = recentRecords[date]

            val durationHours = if (record != null) {
                val start = record.getHoraDeDormirAsLocalTime()
                val end = record.getHoraDeAcordarAsLocalTime()
                val duration = Duration.between(start, end)
                val totalDuration = if (duration.isNegative) duration.plusDays(1) else duration
                totalDuration.toMinutes() / 60.0f
            } else {
                0f // No record for this day
            }

            entries.add(BarEntry(i.toFloat(), durationHours))

            val qualityColor = when (record?.qualidade) {
                QualidadeSono.BOM.name -> ContextCompat.getColor(requireContext(), R.color.success_green)
                QualidadeSono.RAZOAVEL.name -> ContextCompat.getColor(requireContext(), R.color.warning_orange)
                QualidadeSono.RUIM.name -> ContextCompat.getColor(requireContext(), R.color.error_red)
                else -> ContextCompat.getColor(requireContext(), R.color.md_theme_outline)
            }
            colors.add(qualityColor)
            xAxisLabels.add(date.format(dayFormatter))
        }

        val dataSet = BarDataSet(entries, "Duração do Sono").apply {
            this.colors = colors
            setDrawValues(false)
        }

        binding.barChartSleep.data = BarData(dataSet)
        configureChartAppearance()
        binding.barChartSleep.xAxis.valueFormatter = IndexAxisValueFormatter(xAxisLabels)
        binding.barChartSleep.invalidate() // refresh
    }

    private fun configureChartAppearance() {
        binding.barChartSleep.apply {
            description.isEnabled = false
            legend.isEnabled = false
            setDrawGridBackground(false)
            setDrawBarShadow(false)
            setScaleEnabled(false)

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                granularity = 1f
                textColor = ContextCompat.getColor(context, R.color.md_theme_onSurfaceVariant)
            }
            axisLeft.apply {
                axisMinimum = 0f
                setDrawGridLines(true)
                gridColor = ContextCompat.getColor(context, R.color.md_theme_outline)
                textColor = ContextCompat.getColor(context, R.color.md_theme_onSurfaceVariant)
            }
            axisRight.isEnabled = false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}