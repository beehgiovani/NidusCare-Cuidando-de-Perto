package com.developersbeeh.medcontrol.ui.archive

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.navArgs
import com.developersbeeh.medcontrol.databinding.FragmentArchivedMedicationsBinding
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ArchivedMedicationsFragment : Fragment() {

    private var _binding: FragmentArchivedMedicationsBinding? = null
    private val binding get() = _binding!!
    private val args: ArchivedMedicationsFragmentArgs by navArgs()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentArchivedMedicationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Gerenciar: ${args.dependentName}"

        val adapter = ArchivedTabsAdapter(this)
        binding.viewPager.adapter = adapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Tratamentos Concluídos"
                1 -> "Estoque Zerado"
                else -> "Lotes Vencidos"
            }
        }.attach()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}