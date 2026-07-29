package com.arkiv.player.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.ItemNotFoundException
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

    private fun messageFor(e: Throwable): String = when (e) {
        is ItemNotFoundException -> e.message ?: "No se encontró el ítem"
        is IOException -> "Sin conexión o archive.org no responde. Reintentá."
        is IllegalArgumentException -> e.message ?: "Entrada inválida"
        else -> "Error inesperado: ${e.message}"
    }
}
