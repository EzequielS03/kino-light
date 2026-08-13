package com.arkiv.player.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arkiv.player.pairing.PairingManager
import com.arkiv.player.pairing.qrImageBitmap
import com.arkiv.player.pocketbase.AccountException
import com.arkiv.player.pocketbase.AccountManager
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Las tres puertas de entrada del TV (Task 9 agrega la tercera). */
private enum class TabDeEntrada { DESCARGA, PAREO, LOGIN }

/**
 * Se compone en vez de `ArkivTvRoot` cuando `EntradaViewModel` (Task 4) dice que no hay sesión de
 * persona.
 *
 * ### Por qué tres pestañas y no un solo QR
 *
 * El pareo (pestaña 2) asume un teléfono que ya tiene Arkiv instalado. Quien estrena el TV sin eso
 * quedaba en un callejón: la única pantalla que ve le pide escanear con una app que no tiene, y
 * desde el TV no hay salida. La pestaña "Descargar" resuelve el caso de "tengo un Android pero
 * todavía no instalé nada". La pestaña "Entrar en esta TV" (Task 9) resuelve el otro caso, el que
 * quedaba sin salida: quien tiene un iPhone -o ningún otro Android a mano- y jamás va a poder parear
 * porque nunca va a tener la app en un celular. El pareo sigue siendo el camino recomendado cuando
 * hay un Android a mano -es más rápido y no expone la contraseña en la pantalla del living-; esta
 * pestaña es la salida para cuando no lo hay.
 *
 * Son **pestañas y no pasos** para que se pueda ir y volver: con un asistente de pasos, quien pasa
 * al pareo y después se da cuenta de que el teléfono no tenía la app queda otra vez sin camino de
 * vuelta. Las tres están siempre a la vista y a un movimiento del control remoto.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvPantallaDeEntrada(pairing: PairingManager, account: AccountManager) {
    var tab by remember { mutableStateOf(TabDeEntrada.DESCARGA) }
    val focoDescarga = remember { FocusRequester() }

    // El foco arranca en las pestañas, con reintento: pedirlo en la primera composición falla en
    // silencio porque el nodo todavía no está colocado, y queda una pantalla que se ve pero no
    // responde al control remoto hasta que alguien mueve el DPAD por su cuenta. Pasó exactamente
    // eso en el Fire TV.
    LaunchedEffect(Unit) {
        repeat(20) {
            if (runCatching { focoDescarga.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
            delay(50)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(top = 36.dp, start = 48.dp, end = 48.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                "KINO",
                style = MaterialTheme.typography.headlineMedium,
                color = ArkivRed,
                fontWeight = FontWeight.Black,
            )
            Text(
                "Este TV todavía no está emparejado -es en el teléfono donde iniciás sesión-.",
                style = MaterialTheme.typography.bodyMedium,
                color = ArkivTextSecondary,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PestanaDeEntrada(
                    texto = "1 · Descargar la app",
                    seleccionada = tab == TabDeEntrada.DESCARGA,
                    onSelect = { tab = TabDeEntrada.DESCARGA },
                    modifier = Modifier.focusRequester(focoDescarga),
                )
                PestanaDeEntrada(
                    texto = "2 · Parear este TV",
                    seleccionada = tab == TabDeEntrada.PAREO,
                    onSelect = { tab = TabDeEntrada.PAREO },
                )
                PestanaDeEntrada(
                    texto = "3 · Entrar en esta TV",
                    seleccionada = tab == TabDeEntrada.LOGIN,
                    onSelect = { tab = TabDeEntrada.LOGIN },
                )
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                TabDeEntrada.DESCARGA -> PanelDeDescarga()
                TabDeEntrada.PAREO ->
                    // Column y no Box superpuesto: `TvPairingScreen` es un `fillMaxSize()` centrado
                    // que dibuja su propio título, y encimarlo al encabezado los hacía caer en la
                    // misma línea, ilegibles uno sobre el otro.
                    TvPairingScreen(pairing = pairing, deviceName = android.os.Build.MODEL, onDone = {})
                TabDeEntrada.LOGIN -> PanelDeLogin(account)
            }
        }
    }
}

/** Mismo lenguaje visual que los chips de fuente de `TvSearchScreen`: el foco gana en contraste,
 *  que es lo que se necesita ver desde el sofá. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PestanaDeEntrada(
    texto: String,
    seleccionada: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onSelect,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (seleccionada) ArkivRed.copy(alpha = 0.28f) else ArkivSurfaceHigh,
            focusedContainerColor = ArkivRed.copy(alpha = 0.55f),
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White)),
        ),
    ) {
        Text(
            texto,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

/**
 * QR para bajar el APK al teléfono. La URL sale de `latest.json` -la misma que usa el aviso de
 * actualización- y no de una constante: lleva el número de versión adentro, así que hardcodearla
 * la dejaría apuntando a un archivo viejo en la próxima publicación.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PanelDeDescarga() {
    val graph = rememberGraph()
    var url by remember { mutableStateOf<String?>(null) }
    var fallo by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        url = graph.updateChecker.urlDeDescarga()
        fallo = url == null
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Escaneá este código para instalar Kino en tu teléfono",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        Spacer(Modifier.height(20.dp))
        val u = url
        when {
            u != null -> {
                val qr = remember(u) { runCatching { qrImageBitmap(u) }.getOrNull() }
                qr?.let {
                    Image(bitmap = it, contentDescription = "QR de descarga", modifier = Modifier.size(280.dp))
                }
            }
            // Sin red no se puede armar el QR, pero el pareo sí funciona en la LAN: no hay que
            // dejar trabado acá a quien ya tiene la app.
            fallo -> Text(
                "No se pudo obtener el enlace de descarga. Si ya tenés la app, pasá a \"Parear este TV\".",
                style = MaterialTheme.typography.bodyMedium,
                color = ArkivTextSecondary,
            )
            else -> Text("Generando código…", style = MaterialTheme.typography.bodyMedium, color = Color.White)
        }
    }
}

/**
 * Normaliza un código de licencia tipeado con el control remoto: lo arma en la forma EXACTA que
 * espera el backend (`XXXX-XXXX-XXXX`, tres grupos de 4 -ver `ALFABETO`/`generar()` en
 * `licencias/codigo.py` del backend-). El servidor no normaliza nada de su lado: `_licencia_libre`
 * en `identidad/cuentas.py` compara el código tal cual llega (`filter: codigo="..."`), así que un
 * "GS9WSH8CYC6Y" sin guiones simplemente no matchea contra "GS9W-SH8C-YC6Y" en la base. Obligar a
 * tipear los guiones con un D-pad es pedir un error de tipeo -por eso el alfabeto de la licencia ya
 * evita 0/O/1/I/L, para poder dictarlo sin ambigüedad-, así que acá se reconstruye la forma canónica
 * sea como sea que la persona la haya tipeado: con guiones, sin guiones, en minúsculas, con espacios
 * de más.
 */
fun normalizarLicencia(input: String): String =
    input.filter { it.isLetterOrDigit() }.uppercase().chunked(4).joinToString("-")

/**
 * Arma el pedido de entrada desde la TV: normaliza la licencia (arriba) y elige `login` o
 * `registrar` según [registrando]. Separada de la Composable a propósito -este proyecto no tiene
 * infraestructura de tests de UI de Compose, así que la única forma de probar "esto llama al método
 * que corresponde, y no otro" es que sea una función de afuera, mismo criterio que `estadoDeEntrada`
 * en `EntradaViewModel`-. No reimplementa nada de `AccountManager`: éste ya resuelve
 * licencia/cupo/backend caído (ver su KDoc), acá solo se arman los argumentos y se elige el camino.
 */
suspend fun entrarDesdeTv(
    account: AccountManager,
    email: String,
    password: String,
    licencia: String,
    registrando: Boolean,
) {
    if (registrando) {
        account.registrar(email.trim(), password, normalizarLicencia(licencia))
    } else {
        account.login(email.trim(), password)
    }
}

/** Qué campo recibe las teclas del teclado en pantalla de [PanelDeLogin]. */
private enum class CampoTv { EMAIL, PASSWORD, LICENCIA }

/**
 * Pestaña "3 · Entrar en esta TV" (Task 9): login/registro con el teclado en pantalla, para quien no
 * tiene un Android a mano para parear (celular con iOS, o directamente sin otro celu). Reusa
 * [AccountManager.login]/[AccountManager.registrar] a través de [entrarDesdeTv] -no reimplementa el
 * manejo de errores de licencia/cupo/backend caído que esos métodos ya resuelven-.
 *
 * Mismo layout de dos columnas que `TvSearchScreen` (teclado fijo a la izquierda, contenido a la
 * derecha): a la derecha, los campos como chips seleccionables -el que tiene el foco es el que
 * recibe las teclas, mismo gesto que `TvSeasonChip`, sin necesitar un click aparte- más los botones
 * de acción; a la izquierda, el teclado extendido con sus tres variantes (Task 9).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PanelDeLogin(account: AccountManager) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var licencia by remember { mutableStateOf("") }
    var registrando by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var campoActivo by remember { mutableStateOf(CampoTv.EMAIL) }
    var modoTeclado by remember { mutableStateOf(TvKeyboardMode.MAYUS) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Si el campo activo era la licencia y se vuelve a "Entrar" (deja de registrar), ese campo
    // desaparece de la pantalla -sin este fallback el teclado seguiría escribiendo en un campo que
    // ya no se ve, sin ningún efecto visible, y parecería roto.
    LaunchedEffect(registrando) {
        if (!registrando && campoActivo == CampoTv.LICENCIA) campoActivo = CampoTv.EMAIL
    }

    val focoPrimerCampo = remember { FocusRequester() }
    // Mismo reintento que el foco de las pestañas (ver el KDoc de TvPantallaDeEntrada): pedirlo en
    // la primera composición falla en silencio porque el nodo todavía no está colocado.
    LaunchedEffect(Unit) {
        repeat(20) {
            if (runCatching { focoPrimerCampo.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
            delay(50)
        }
    }

    fun valorActivo(): String = when (campoActivo) {
        CampoTv.EMAIL -> email
        CampoTv.PASSWORD -> password
        CampoTv.LICENCIA -> licencia
    }

    fun escribirEnCampoActivo(nuevo: String) {
        when (campoActivo) {
            CampoTv.EMAIL -> email = nuevo
            CampoTv.PASSWORD -> password = nuevo
            CampoTv.LICENCIA -> licencia = nuevo
        }
        error = null
    }

    fun enviar() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                entrarDesdeTv(account, email, password, licencia, registrando)
            } catch (e: AccountException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    val puedeEnviar = !busy && email.isNotBlank() && password.isNotBlank() &&
        (!registrando || licencia.isNotBlank())

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxHeight().width(380.dp).padding(24.dp)) {
            TvKeyboard(
                text = valorActivo(),
                onTextChange = { escribirEnCampoActivo(it) },
                rows = tvKeyboardRows(modoTeclado),
                onModo = { modoTeclado = it },
            )
        }
        Column(
            Modifier.fillMaxSize().padding(top = 24.dp, end = 48.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CampoTvChip(
                etiqueta = "Email",
                valor = email,
                activo = campoActivo == CampoTv.EMAIL,
                onFocus = { campoActivo = CampoTv.EMAIL },
                modifier = Modifier.focusRequester(focoPrimerCampo),
            )
            CampoTvChip(
                etiqueta = "Contraseña",
                valor = if (passwordVisible) password else "•".repeat(password.length),
                activo = campoActivo == CampoTv.PASSWORD,
                // Enmascarado SOLO mientras está oculta -mismo criterio que
                // AccountSection.PasswordField-: `PasswordVisualTransformation` (celu) enmascara lo
                // que se DIBUJA, no lo que se expone en el árbol de accesibilidad, y eso se comprobó
                // hoy leyendo una contraseña real en claro con un volcado de accesibilidad. Acá el
                // valor que recibe `Text` ya son los puntos (no hay transform aparte), pero se marca
                // igual para que un lector de pantalla anuncie "contraseña" en vez de dictar el
                // relleno carácter por carácter. Cuando la persona la muestra con el botón de abajo,
                // se saca la marca: ahí SÍ decidió que se pueda leer/dictar.
                enmascarado = !passwordVisible,
                onFocus = { campoActivo = CampoTv.PASSWORD },
            )
            Surface(
                onClick = { passwordVisible = !passwordVisible },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                colors = arkivTvSurfaceColors(),
                border = arkivTvSurfaceBorder(),
            ) {
                Text(
                    if (passwordVisible) "Ocultar contraseña" else "Mostrar contraseña",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            if (registrando) {
                CampoTvChip(
                    etiqueta = "Código de licencia",
                    // Se muestra tal cual se tipeó (guiones incluidos si los puso) para que la
                    // persona vea qué escribió; la normalización (con o sin guiones, mayúsculas) pasa
                    // recién al mandar, en `entrarDesdeTv` -ver `normalizarLicencia`-.
                    valor = licencia,
                    activo = campoActivo == CampoTv.LICENCIA,
                    onFocus = { campoActivo = CampoTv.LICENCIA },
                )
                Text(
                    "Con guiones o sin guiones da igual: se acomoda solo.",
                    style = MaterialTheme.typography.labelSmall,
                    color = ArkivTextSecondary,
                )
            }

            error?.let {
                Text(
                    it,
                    color = ArkivRed,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                Surface(
                    onClick = { enviar() },
                    enabled = puedeEnviar,
                    modifier = Modifier.height(48.dp),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                    colors = arkivTvSurfaceColors(),
                    border = arkivTvSurfaceBorder(),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            when {
                                busy && registrando -> "Creando…"
                                busy -> "Entrando…"
                                registrando -> "Crear cuenta"
                                else -> "Entrar"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 18.dp),
                        )
                    }
                }
                Surface(
                    onClick = { registrando = !registrando; error = null },
                    enabled = !busy,
                    modifier = Modifier.height(48.dp),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                    colors = arkivTvSurfaceColors(),
                    border = arkivTvSurfaceBorder(),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (registrando) "Ya tengo cuenta" else "Crear cuenta",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 18.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Selector de campo con el mismo gesto que `TvSeasonChip` (`TvSearchScreen`): mover el foco ahí
 * ([onFocus]) alcanza para que se vuelva el destino del teclado en pantalla, sin un click aparte
 * -pero el click también funciona, para quien llega con un clic directo-. Muestra el valor actual
 * (o un placeholder si está vacío) para que la persona vea qué tiene tipeado sin adivinar.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CampoTvChip(
    etiqueta: String,
    valor: String,
    activo: Boolean,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    enmascarado: Boolean = false,
) {
    Surface(
        onClick = onFocus,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocus() }
            .let { if (enmascarado) it.semantics { password() } else it },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (activo) ArkivRed.copy(alpha = 0.28f) else ArkivSurfaceHigh,
            focusedContainerColor = ArkivRed.copy(alpha = 0.55f),
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White)),
        ),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(etiqueta, style = MaterialTheme.typography.labelSmall, color = ArkivTextSecondary)
            Text(
                valor.ifBlank { "—" },
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}
