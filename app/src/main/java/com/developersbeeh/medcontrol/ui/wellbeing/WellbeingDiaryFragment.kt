// src/main/java/com/developersbeeh/medcontrol/ui/wellbeing/WellbeingDiaryFragment.kt
package com.developersbeeh.medcontrol.ui.wellbeing

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.developersbeeh.medcontrol.NavGraphDirections
import com.developersbeeh.medcontrol.R
import com.developersbeeh.medcontrol.data.UserPreferences
import com.developersbeeh.medcontrol.data.model.QualidadeSono
import com.developersbeeh.medcontrol.data.model.TipoRefeicao
import com.developersbeeh.medcontrol.databinding.*
import com.developersbeeh.medcontrol.ui.wellbeing.timer.ActivityTimerService
import com.developersbeeh.medcontrol.ui.wellbeing.timer.TimerServiceManager
import com.developersbeeh.medcontrol.ui.wellbeing.timer.TimerState
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.MobileAds
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class WellbeingDiaryFragment : Fragment() {

    private lateinit var userPreferences: UserPreferences
    private var _binding: FragmentWellbeingDiaryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: BemEstarViewModel by viewModels()
    private val args: WellbeingDiaryFragmentArgs by navArgs()

    private var tempImageUri: Uri? = null

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            tempImageUri?.let {
                showAddMealDialog(isAnalyzing = true)
                viewModel.analyzeMealPhoto(it)
            }
        }
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            showAddMealDialog(isAnalyzing = true)
            viewModel.analyzeMealPhoto(it)
        }
    }

    private val timerStopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "ACTIVITY_TIMER_STOP_ACTION") {
                val elapsedTimeMs = intent.getLongExtra("ELAPSED_TIME_MS", 0)
                val activityType = intent.getStringExtra("ACTIVITY_TYPE") ?: "Atividade"
                val durationMinutes = TimeUnit.MILLISECONDS.toMinutes(elapsedTimeMs).toInt()

                if (durationMinutes > 0) {
                    viewModel.addAtividadeFisica(activityType, durationMinutes)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWellbeingDiaryBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.initialize(args.dependentId)
        userPreferences = UserPreferences(requireContext())
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Diário de Bem-Estar"
        val dateFormatter = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", Locale("pt", "BR"))
        binding.textViewDate.text = "Hoje, ${LocalDate.now().format(dateFormatter).replaceFirstChar { it.uppercase() }}"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireActivity().registerReceiver(timerStopReceiver, IntentFilter("ACTIVITY_TIMER_STOP_ACTION"), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            requireActivity().registerReceiver(timerStopReceiver, IntentFilter("ACTIVITY_TIMER_STOP_ACTION"))
        }

        setupListeners()
        observeViewModel()
        observeTimerState()
        setupAds()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        requireActivity().unregisterReceiver(timerStopReceiver)
        _binding = null
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

    private fun setupListeners() {
        // --- Ações de Navegação (clique no card inteiro) ---
        binding.cardHydration.root.setOnClickListener {
            val action = NavGraphDirections.actionGlobalToHydrationTrackerFragment(args.dependentId)
            findNavController().navigate(action)
        }
        binding.cardActivity.root.setOnClickListener {
            val action = NavGraphDirections.actionGlobalToPhysicalActivityTrackerFragment(args.dependentId)
            findNavController().navigate(action)
        }
        binding.cardMeal.root.setOnClickListener {
            val action = NavGraphDirections.actionGlobalToMealTrackerFragment(args.dependentId)
            findNavController().navigate(action)
        }
        binding.cardSleep.root.setOnClickListener {
            val action = NavGraphDirections.actionGlobalToSleepTrackerFragment(args.dependentId)
            findNavController().navigate(action)
        }
        binding.cardWeight.root.setOnClickListener {
            val action = NavGraphDirections.actionGlobalToWeightTrackerFragment(args.dependentId)
            findNavController().navigate(action)
        }

        // --- Ações de Botões (dentro dos cards) ---
        binding.cardWeight.buttonUpdateWeight.setOnClickListener { showUpdateWeightDialog() }
        binding.cardHydration.buttonAdd200ml.setOnClickListener { viewModel.addWaterIntake(200) }
        binding.cardHydration.buttonAdd500ml.setOnClickListener { viewModel.addWaterIntake(500) }
        binding.cardActivity.buttonStartActivity.setOnClickListener { showActivityDialog(isStartingTimer = true) }
        binding.cardActivity.buttonAddActivityManually.setOnClickListener { showActivityDialog(isStartingTimer = false) }
        binding.cardActivity.buttonPauseTimer.setOnClickListener { sendCommandToService(ActivityTimerService.ACTION_PAUSE) }
        binding.cardActivity.buttonStopTimer.setOnClickListener { sendCommandToService(ActivityTimerService.ACTION_STOP) }
        binding.cardMeal.buttonAddMeal.setOnClickListener { showAddMealDialog() }
        binding.cardMeal.buttonAnalyzeMeal.setOnClickListener { handleMealAnalysisClick() }
        binding.cardSleep.buttonAddEditSleep.setOnClickListener { showAddSleepDialog() }
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            val dependent = state.dependente ?: return@observe
            val animation = AnimationUtils.loadAnimation(context, R.anim.slide_up_fade_in)

            updateWeightCard(state)

            if (!binding.cardHydration.root.isVisible) binding.cardHydration.root.startAnimation(animation)
            binding.cardHydration.root.isVisible = true
            animateTextViewUpdate(binding.cardHydration.textViewConsumed, "${state.hidratacaoTotalMl}")
            binding.cardHydration.progressBarWater.setProgressCompat(state.hidratacaoPorcentagem, true)
            binding.cardHydration.textViewGoal.text = "Meta: ${state.hidratacaoMetaMl} ml"

            if (!binding.cardActivity.root.isVisible) binding.cardActivity.root.startAnimation(animation)
            binding.cardActivity.root.isVisible = true
            binding.cardActivity.progressBarActivity.setProgressCompat(state.atividadePorcentagem, true)
            animateTextViewUpdate(binding.cardActivity.textViewMinutes, "${state.atividadeTotalMin}")
            binding.cardActivity.textViewActivityGoal.text = "Meta: ${state.atividadeMetaMin} min"

            if (!binding.cardMeal.root.isVisible) binding.cardMeal.root.startAnimation(animation)
            binding.cardMeal.root.isVisible = true
            binding.cardMeal.progressCalories.setProgressCompat(state.caloriasPorcentagem, true)
            animateTextViewUpdate(binding.cardMeal.textViewCaloriesProgress, "${state.caloriasTotal} / ${state.caloriasMeta} kcal")

            if (!binding.cardSleep.root.isVisible) binding.cardSleep.root.startAnimation(animation)
            binding.cardSleep.root.isVisible = true
            val registroSono = state.registroSono
            if (registroSono != null) {
                animateTextViewUpdate(binding.cardSleep.textViewSleepDuration, "${state.sonoTotalHoras}h ${state.sonoTotalMinutos}m")
                val qualidade = try { QualidadeSono.valueOf(registroSono.qualidade) } catch (e: Exception) { QualidadeSono.RAZOAVEL }
                animateTextViewUpdate(binding.cardSleep.textViewSleepQuality, "Qualidade: ${qualidade.displayName}")
                binding.cardSleep.buttonAddEditSleep.text = "Editar"
            } else {
                binding.cardSleep.textViewSleepDuration.text = "- h -- m"
                binding.cardSleep.textViewSleepQuality.text = "Nenhum registro hoje"
                binding.cardSleep.buttonAddEditSleep.text = "Registrar"
            }
        }

        viewModel.actionFeedback.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let { message ->
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
        }

        viewModel.sleepSuggestionEvent.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let {
                Toast.makeText(context, "Horário sugerido preenchido!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateWeightCard(state: BemEstarUiState) {
        val animation = AnimationUtils.loadAnimation(context, R.anim.slide_up_fade_in)
        val dependent = state.dependente ?: return

        if (dependent.peso.isNotBlank()) {
            if (!binding.cardWeight.root.isVisible) {
                binding.cardWeight.root.startAnimation(animation)
                binding.cardWeight.root.isVisible = true
            }
            binding.cardWeight.textViewCurrentWeight.text = dependent.peso.replace(',', '.')
        } else {
            binding.cardWeight.root.isVisible = false
            return
        }

        binding.cardWeight.layoutImc.isVisible = state.imcResult != null
        state.imcResult?.let {
            binding.cardWeight.textViewImcValue.text = String.format(Locale.getDefault(), "%.1f", it.value)
            binding.cardWeight.textViewImcClassification.text = it.classification
            binding.cardWeight.textViewImcClassification.setTextColor(ContextCompat.getColor(requireContext(), it.color))
        }

        binding.cardWeight.goalContainer.isVisible = true
        binding.cardWeight.layoutWeightGoal.isVisible = state.pesoMetaStatus != null
        binding.cardWeight.buttonSetGoal.isVisible = state.pesoMetaStatus == null

        state.pesoMetaStatus?.let {
            binding.cardWeight.progressWeightGoal.progress = it.progress
            binding.cardWeight.textViewWeightGoalProgress.text = it.progressText
            binding.cardWeight.progressWeightGoal.setIndicatorColor(ContextCompat.getColor(requireContext(), it.color))
        } ?: binding.cardWeight.buttonSetGoal.setOnClickListener {
            findNavController().navigate(NavGraphDirections.actionGlobalToHealthGoalsFragment(args.dependentId))
        }
    }

    private fun observeTimerState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                TimerServiceManager.timerState.collectLatest { state ->
                    updateActivityCardUI(state)
                }
            }
        }
    }

    private fun animateTextViewUpdate(textView: TextView, newText: String) {
        if (textView.text == newText || !textView.isShown) {
            textView.text = newText
            return
        }
        val fadeOut = AnimationUtils.loadAnimation(context, R.anim.fade_out)
        val fadeIn = AnimationUtils.loadAnimation(context, R.anim.fade_in)
        fadeOut.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation?) {}
            override fun onAnimationRepeat(animation: Animation?) {}
            override fun onAnimationEnd(animation: Animation?) {
                textView.text = newText
                textView.startAnimation(fadeIn)
            }
        })
        textView.startAnimation(fadeOut)
    }

    private fun updateActivityCardUI(state: TimerState) {
        if (_binding == null) return

        val cardActivityBinding = binding.cardActivity
        val timeInMillis = when (state) {
            is TimerState.Running -> state.elapsedTime
            is TimerState.Paused -> state.elapsedTime
            is TimerState.Idle -> 0L
        }

        cardActivityBinding.layoutStartActivity.isVisible = state is TimerState.Idle
        cardActivityBinding.layoutTimerRunning.isVisible = state is TimerState.Running || state is TimerState.Paused

        if (state is TimerState.Idle) {
            val totalMinutos = viewModel.uiState.value?.atividadeTotalMin ?: 0
            cardActivityBinding.textViewMinutes.text = totalMinutos.toString()
        } else {
            val hours = TimeUnit.MILLISECONDS.toHours(timeInMillis)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(timeInMillis) % 60
            val seconds = TimeUnit.MILLISECONDS.toSeconds(timeInMillis) % 60
            cardActivityBinding.textViewTimer.text = if (hours > 0) {
                String.format("%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%02d:%02d", minutes, seconds)
            }
        }

        when(state) {
            is TimerState.Running -> {
                cardActivityBinding.buttonPauseTimer.text = "Pausar"
                cardActivityBinding.buttonPauseTimer.setIconResource(R.drawable.ic_timer_pause)
                cardActivityBinding.buttonPauseTimer.setOnClickListener { sendCommandToService(ActivityTimerService.ACTION_PAUSE) }
            }
            is TimerState.Paused -> {
                cardActivityBinding.buttonPauseTimer.text = "Continuar"
                cardActivityBinding.buttonPauseTimer.setIconResource(R.drawable.ic_play_arrow)
                cardActivityBinding.buttonPauseTimer.setOnClickListener { sendCommandToService(ActivityTimerService.ACTION_START_RESUME) }
            }
            is TimerState.Idle -> {}
        }
    }

    private fun showUpdateWeightDialog() {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_single_input, null)
        val textInputLayout = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.textInputLayout)
        val editText = dialogView.findViewById<TextInputEditText>(R.id.editTextSingleInput)
        editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        textInputLayout.hint = "Novo Peso (kg)"
        editText.setText(viewModel.uiState.value?.dependente?.peso ?: "")
        MaterialAlertDialogBuilder(requireContext(), R.style.AppTheme_DialogAnimation)
            .setTitle("Atualizar Peso Atual")
            .setView(dialogView)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                val newWeight = editText.text.toString().trim()
                if (newWeight.isNotEmpty()) {
                    viewModel.updateWeight(newWeight)
                }
            }
            .show()
    }

    private fun showActivityDialog(isStartingTimer: Boolean) {
        val dialogBinding = DialogAddActivityBinding.inflate(LayoutInflater.from(context))
        val dialogTitle = if (isStartingTimer) "Iniciar Atividade com Cronômetro" else "Registrar Atividade Manualmente"
        val positiveButtonText = if (isStartingTimer) "Iniciar" else "Salvar"
        dialogBinding.tilDuration.isVisible = !isStartingTimer
        val activities = resources.getStringArray(R.array.activity_suggestions)
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, activities)
        dialogBinding.autoCompleteActivityType.setAdapter(adapter)
        MaterialAlertDialogBuilder(requireContext(), R.style.AppTheme_DialogAnimation)
            .setTitle(dialogTitle)
            .setView(dialogBinding.root)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton(positiveButtonText) { _, _ ->
                val tipo = dialogBinding.autoCompleteActivityType.text.toString()
                if (tipo.isBlank()) {
                    Toast.makeText(context, "Por favor, defina um tipo de atividade.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (isStartingTimer) {
                    sendCommandToService(ActivityTimerService.ACTION_START_RESUME, tipo)
                } else {
                    val duracao = dialogBinding.editTextDuration.text.toString().toIntOrNull() ?: 0
                    if (duracao <= 0) {
                        Toast.makeText(context, "Por favor, insira uma duração válida.", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    viewModel.addAtividadeFisica(tipo, duracao)
                }
            }
            .show()
    }

    private fun handleMealAnalysisClick() {
        val isPremium = UserPreferences(requireContext()).isPremium()
        if (isPremium) {
            showImageSourceDialog()
        } else {
            findNavController().navigate(R.id.action_global_to_premiumPlansFragment)
            Toast.makeText(context, "Análise de refeição com IA é um recurso Premium.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showImageSourceDialog() {
        val options = arrayOf("Tirar Foto com a Câmera", "Escolher da Galeria")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Analisar Refeição")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { // Câmera
                        tempImageUri = getTmpFileUri()
                        takePictureLauncher.launch(tempImageUri)
                    }
                    1 -> { // Galeria
                        pickImageLauncher.launch("image/*")
                    }
                }
            }
            .show()
    }

    private fun getTmpFileUri(): Uri {
        val tmpFile = File.createTempFile("meal_photo_", ".jpg", requireContext().cacheDir).apply {
            createNewFile()
            deleteOnExit()
        }
        return FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.provider", tmpFile)
    }

    private fun showAddMealDialog(isAnalyzing: Boolean = false) {
        val dialogBinding = DialogAddMealBinding.inflate(LayoutInflater.from(context))
        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.AppTheme_DialogAnimation)
            .setTitle("Registrar Refeição")
            .setView(dialogBinding.root)
            .setNegativeButton("Cancelar") { _, _ -> viewModel.resetMealAnalysisState() }
            .setPositiveButton("Salvar") { _, _ ->
                val selectedId = dialogBinding.radioGroupMealType.checkedRadioButtonId
                val tipo = when (selectedId) {
                    R.id.radioCafeDaManha -> TipoRefeicao.CAFE_DA_MANHA
                    R.id.radioAlmoco -> TipoRefeicao.ALMOCO
                    R.id.radioJantar -> TipoRefeicao.JANTAR
                    else -> TipoRefeicao.LANCHE
                }
                val descricao = dialogBinding.editTextDescription.text.toString()
                val calorias = dialogBinding.editTextCalories.text.toString().toIntOrNull()
                viewModel.addRefeicao(tipo, descricao, calorias)
            }
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            if (isAnalyzing) {
                viewModel.mealAnalysisState.observe(viewLifecycleOwner) { state ->
                    dialogBinding.progressBarAnalysis.isVisible = state is MealAnalysisState.Loading
                    dialogBinding.buttonAnalyzeWithCamera.isEnabled = state !is MealAnalysisState.Loading
                    positiveButton.isEnabled = state !is MealAnalysisState.Loading
                    when (state) {
                        is MealAnalysisState.Success -> {
                            dialogBinding.layoutAiResults.isVisible = true
                            dialogBinding.dividerAi.isVisible = true
                            dialogBinding.editTextDescription.setText(state.result.descricao)
                            dialogBinding.editTextCalories.setText(state.result.calorias.toString())
                            dialogBinding.textViewBenefits.text = state.result.beneficios
                            dialogBinding.textViewTips.text = state.result.dicas
                            viewModel.resetMealAnalysisState()
                        }
                        is MealAnalysisState.Error -> {
                            Toast.makeText(context, state.message, Toast.LENGTH_LONG).show()
                            viewModel.resetMealAnalysisState()
                            dialog.dismiss()
                        }
                        else -> {}
                    }
                }
            }
        }

        dialogBinding.buttonAnalyzeWithCamera.setOnClickListener {
            handleMealAnalysisClick()
            dialog.dismiss()
        }
        dialog.setOnDismissListener {
            viewModel.mealAnalysisState.removeObservers(viewLifecycleOwner)
            viewModel.resetMealAnalysisState()
        }
        dialog.show()
    }

    private fun showAddSleepDialog() {
        val dialogBinding = DialogAddSleepBinding.inflate(layoutInflater)
        val existingRecord = viewModel.uiState.value?.registroSono
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        var bedtime = existingRecord?.getHoraDeDormirAsLocalTime() ?: LocalTime.of(22, 30)
        var wakeTime = existingRecord?.getHoraDeAcordarAsLocalTime() ?: LocalTime.of(7, 0)
        var interruptions = existingRecord?.interrupcoes ?: 0

        dialogBinding.editTextBedtime.setText(bedtime.format(timeFormatter))
        dialogBinding.editTextWakeUp.setText(wakeTime.format(timeFormatter))
        dialogBinding.textViewInterruptionsCount.text = interruptions.toString()
        dialogBinding.editTextNotes.setText(existingRecord?.notas)

        val initialQuality = try {
            existingRecord?.let { QualidadeSono.valueOf(it.qualidade) } ?: QualidadeSono.RAZOAVEL
        } catch (e: Exception) { QualidadeSono.RAZOAVEL }

        when (initialQuality) {
            QualidadeSono.BOM -> dialogBinding.chipGroupSleepQuality.check(R.id.chipQualityGood)
            QualidadeSono.RAZOAVEL -> dialogBinding.chipGroupSleepQuality.check(R.id.chipQualityOk)
            QualidadeSono.RUIM -> dialogBinding.chipGroupSleepQuality.check(R.id.chipQualityBad)
        }

        dialogBinding.editTextBedtime.setOnClickListener {
            showTimePicker("Hora de Dormir", bedtime) { time ->
                bedtime = time
                dialogBinding.editTextBedtime.setText(time.format(timeFormatter))
            }
        }
        dialogBinding.editTextWakeUp.setOnClickListener {
            showTimePicker("Hora de Acordar", wakeTime) { time ->
                wakeTime = time
                dialogBinding.editTextWakeUp.setText(time.format(timeFormatter))
            }
        }

        dialogBinding.buttonSuggestTime.setOnClickListener { viewModel.requestSleepTimeSuggestion() }

        dialogBinding.buttonAddInterruption.setOnClickListener {
            interruptions++
            dialogBinding.textViewInterruptionsCount.text = interruptions.toString()
        }
        dialogBinding.buttonRemoveInterruption.setOnClickListener {
            if (interruptions > 0) {
                interruptions--
                dialogBinding.textViewInterruptionsCount.text = interruptions.toString()
            }
        }

        viewModel.sleepSuggestionEvent.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let { (suggestedBedtime, suggestedWaketime) ->
                bedtime = suggestedBedtime
                wakeTime = suggestedWaketime
                dialogBinding.editTextBedtime.setText(bedtime.format(timeFormatter))
                dialogBinding.editTextWakeUp.setText(wakeTime.format(timeFormatter))
            }
        }

        MaterialAlertDialogBuilder(requireContext(), R.style.AppTheme_DialogAnimation)
            .setTitle(if (existingRecord != null) "Editar Registro de Sono" else "Registrar Sono")
            .setView(dialogBinding.root)
            .setNegativeButton("Cancelar") { dialog, _ ->
                viewModel.sleepSuggestionEvent.removeObservers(viewLifecycleOwner)
                dialog.dismiss()
            }
            .setPositiveButton("Salvar") { _, _ ->
                val qualidade = when (dialogBinding.chipGroupSleepQuality.checkedChipId) {
                    R.id.chipQualityGood -> QualidadeSono.BOM
                    R.id.chipQualityBad -> QualidadeSono.RUIM
                    else -> QualidadeSono.RAZOAVEL
                }
                val notas = dialogBinding.editTextNotes.text.toString().trim().takeIf { it.isNotEmpty() }
                val dataDoRegistro = if (bedtime.isAfter(wakeTime)) LocalDate.now().minusDays(1) else LocalDate.now()

                viewModel.saveSono(dataDoRegistro, bedtime, wakeTime, qualidade, notas, interruptions)
                viewModel.sleepSuggestionEvent.removeObservers(viewLifecycleOwner)
            }
            .setOnDismissListener {
                viewModel.sleepSuggestionEvent.removeObservers(viewLifecycleOwner)
            }
            .show()
    }

    private fun showTimePicker(title: String, initialTime: LocalTime, onTimeSelected: (LocalTime) -> Unit) {
        val timePicker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(initialTime.hour)
            .setMinute(initialTime.minute)
            .setTitleText(title)
            .build()
        timePicker.addOnPositiveButtonClickListener {
            onTimeSelected(LocalTime.of(timePicker.hour, timePicker.minute))
        }
        timePicker.show(parentFragmentManager, "TIME_PICKER_WELLBEING")
    }

    private fun sendCommandToService(action: String, activityType: String? = null) {
        val intent = Intent(requireContext(), ActivityTimerService::class.java).apply {
            this.action = action
            putExtra(ActivityTimerService.EXTRA_DEPENDENT_ID, args.dependentId)
            activityType?.let {
                putExtra(ActivityTimerService.EXTRA_ACTIVITY_TYPE, it)
            }
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }
}