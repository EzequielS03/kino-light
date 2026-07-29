package com.arkiv.player.ui.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddScreen(
    onBack: () -> Unit,
    onAdded: (String) -> Unit,
) {
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

    Scaffold(
        containerColor = ArkivBlack,
        topBar = {
            TopAppBar(
                title = { Text("Agregar de archive.org") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ArkivBlack),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Pegá el link de un ítem (o su identificador). Ej: " +
                    "https://archive.org/details/…",
                color = ArkivTextSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedTextField(
                value = state.input,
                onValueChange = vm::onInputChange,
                label = { Text("URL o identificador") },
                singleLine = true,
                isError = state.error != null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { vm.submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.error != null) {
                Text(state.error!!, color = ArkivRed)
            }
            Button(
                onClick = vm::submit,
                enabled = !state.loading && state.input.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.padding(end = 8.dp).size(18.dp),
                    )
                    Text("Buscando…")
                } else {
                    Text("Agregar a mi biblioteca")
                }
            }
        }
    }
}
