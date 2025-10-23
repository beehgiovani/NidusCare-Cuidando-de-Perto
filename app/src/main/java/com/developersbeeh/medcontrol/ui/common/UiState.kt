package com.developersbeeh.medcontrol.ui.common

/**
 * ✅ NOVO: Sistema padronizado de estados de UI
 * Usado em todo o aplicativo para consistência
 */
sealed class UiState<out T> {
    /**
     * Estado inicial - nenhuma ação foi tomada ainda
     */
    object Idle : UiState<Nothing>()
    
    /**
     * Estado de carregamento
     * @param message Mensagem opcional para exibir durante o carregamento
     */
    data class Loading(val message: String? = null) : UiState<Nothing>()
    
    /**
     * Estado de sucesso com dados
     * @param data Os dados carregados com sucesso
     */
    data class Success<T>(val data: T) : UiState<T>()
    
    /**
     * Estado vazio - operação bem-sucedida mas sem dados
     * @param message Mensagem para exibir no estado vazio
     * @param actionText Texto do botão de ação (opcional)
     */
    data class Empty(
        val message: String = "Nenhum dado encontrado",
        val actionText: String? = null
    ) : UiState<Nothing>()
    
    /**
     * Estado de erro
     * @param message Mensagem de erro amigável
     * @param exception Exceção original (opcional)
     * @param canRetry Se true, mostra botão de tentar novamente
     */
    data class Error(
        val message: String,
        val exception: Throwable? = null,
        val canRetry: Boolean = true
    ) : UiState<Nothing>()
}

/**
 * Extensões úteis para UiState
 */
fun <T> UiState<T>.isLoading(): Boolean = this is UiState.Loading
fun <T> UiState<T>.isSuccess(): Boolean = this is UiState.Success
fun <T> UiState<T>.isError(): Boolean = this is UiState.Error
fun <T> UiState<T>.isEmpty(): Boolean = this is UiState.Empty

fun <T> UiState<T>.getDataOrNull(): T? = when (this) {
    is UiState.Success -> data
    else -> null
}

fun <T> UiState<T>.getErrorOrNull(): String? = when (this) {
    is UiState.Error -> message
    else -> null
}

