package com.arkiv.player.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arkiv.player.ui.add.AddViewModel
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvAddScreen(onAdded: (String) -> Unit) {
    val graph = rememberGraph()
    val vm: AddViewModel = viewModel(
        factory = viewModelFactory { initializer { AddViewModel(graph.repository) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.addedIdentifier) {
        state.addedIdentifier?.let {
            vm.consumeNavigation()
            onAdded(it)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(64.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Agregar de archive.org", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Text(
            "Escribí el identificador o la URL del ítem.",
            color = ArkivTextSecondary,
        )
        OutlinedTextField(
            value = state.input,
            onValueChange = vm::onInputChange,
            label = { androidx.compose.material3.Text("URL o identificador") },
            singleLine = true,
            modifier = Modifier.width(700.dp),
        )
        if (state.error != null) {
            Text(state.error!!, color = ArkivRed)
        }
        Button(onClick = vm::submit, colors = arkivTvButtonColors(), border = arkivTvButtonBorder()) {
            Text(if (state.loading) "Buscando…" else "Agregar a mi biblioteca")
        }
    }
}
