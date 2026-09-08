package com.arkiv.player.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.ArkivRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

data class AddUiState(
    val input: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val addedIdentifier: String? = null,
)

class AddViewModel(private val repo: ArkivRepository) : ViewModel() {

    private val _state = MutableStateFlow(AddUiState())
    val state: StateFlow<AddUiState> = _state.asStateFlow()

    fun onInputChange(value: String) {
        _state.value = _state.value.copy(input = value, error = null)
    }

    fun submit() {
        val current = _state.value
        if (current.loading || current.input.isBlank()) return
        _state.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = repo.addItem(current.input)
            _state.value = result.fold(
                onSuccess = { _state.value.copy(loading = false, addedIdentifier = it.identifier) },
                onFailure = { _state.value.copy(loading = false, error = messageFor(it)) },
            )
        }
    }

    fun consumeNavigation() {
        _state.value = _state.value.copy(addedIdentifier = null)
    }

    // archive.org (y con él, esta pantalla de "pegar una URL") se borró en la poda de esta rama;
    // `repo.addItem` siempre devuelve UnsupportedOperationException ahora. La pantalla se conserva
    // -no se borra del todo- porque sacarla implica tocar la navegación, que es alcance de otra
    // tarea (ver el KDoc de ArkivRepository.addItem).
    private fun messageFor(e: Throwable): String = when (e) {
        is UnsupportedOperationException -> e.message ?: "Esta función ya no está disponible"
        is IOException -> "Sin conexión. Reintentá."
        is IllegalArgumentException -> e.message ?: "Entrada inválida"
        else -> "Error inesperado: ${e.message}"
    }
}
