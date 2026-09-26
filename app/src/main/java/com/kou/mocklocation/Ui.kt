package com.kou.mocklocation

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

// ---------- theme ----------

val Teal = Color(0xFF5EEAD4)
val TealDeep = Color(0xFF14B8A6)
val OnTeal = Color(0xFF04201D)
val Live = Color(0xFFFF4D6D)
val Amber = Color(0xFFFBBF24)
val Bg = Color(0xFF0A0E13)
val Card = Color(0xFF151C26)
val Track = Color(0xFF243042)
val Muted = Color(0xFF8D9AAD)
val Hairline = Color(0x12FFFFFF)
private val Glass = Color(0xE6121821)
private val PanelBg = Color(0xF50E141C)

private val Scheme = darkColorScheme(
    primary = Teal, onPrimary = OnTeal, primaryContainer = Color(0xFF0F3B37), onPrimaryContainer = Teal,
    secondary = Amber, onSecondary = Color.Black,
    background = Bg, onBackground = Color(0xFFE7EEF6),
    surface = Color(0xFF111720), onSurface = Color(0xFFE7EEF6),
    surfaceVariant = Card, onSurfaceVariant = Muted,
    surfaceContainerLowest = Bg, surfaceContainerLow = Color(0xFF111720), surfaceContainer = Card,
    surfaceContainerHigh = Color(0xFF1B2330), surfaceContainerHighest = Track,
    outline = Color(0xFF2B3647), outlineVariant = Color(0xFF222B38),
    error = Live, onError = Color.White,
)

@Composable
fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Scheme) {
    CompositionLocalProvider(LocalContentColor provides Scheme.onSurface, content = content)
}

fun Modifier.glass(shape: Shape = RoundedCornerShape(20.dp), color: Color = Glass) =
    shadow(12.dp, shape, clip = false).clip(shape).background(color).border(1.dp, Hairline, shape)

val Mode.label get() = when (this) { Mode.TELEPORT -> "Teleport"; Mode.ROUTE -> "Rute"; Mode.JOYSTICK -> "Joystick" }
val Mode.icon get() = when (this) { Mode.TELEPORT -> Icons.Rounded.PinDrop; Mode.ROUTE -> Icons.Rounded.Timeline; Mode.JOYSTICK -> Icons.Rounded.SportsEsports }
val Loop.label get() = when (this) { Loop.ONCE -> "Sekali"; Loop.LOOP -> "Ulangi"; Loop.PINGPONG -> "Bolak-balik" }
val Loop.icon get() = when (this) { Loop.ONCE -> Icons.Rounded.East; Loop.LOOP -> Icons.Rounded.Repeat; Loop.PINGPONG -> Icons.Rounded.SyncAlt }
private val PRESET_ICONS = listOf(Icons.Rounded.DirectionsWalk, Icons.Rounded.DirectionsRun, Icons.Rounded.DirectionsBike,
    Icons.Rounded.TwoWheeler, Icons.Rounded.DirectionsCar)

enum class NameFor(val title: String) { PLACE("Simpan lokasi favorit"), ROUTE("Simpan rute") }

// ---------- root ----------

@Composable
fun App(vm: AppVm) {
    val ctx = LocalContext.current
    val status by vm.status.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    var map by remember { mutableStateOf<MapView?>(null) }
    var naming by remember { mutableStateOf<NameFor?>(null) }
    var showRoutes by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.messages.collect { snack.currentSnackbarData?.dismiss(); launch { snack.showSnackbar(it) } }
    }
    LifecycleResumeEffect(Unit) {
        vm.refreshSetup()
        onPauseOrDispose { vm.persist() }
    }
    LaunchedEffect(status.running) { if (status.running) vm.mode = status.mode }

    val locationPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refreshSetup()
        if (!vm.setup.location) vm.say("Izin lokasi ditolak")
    }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshSetup() }
    val openGpx = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importGpx) }
    val saveGpx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { it?.let(vm::exportGpx) }
    val requestLocation = {
        // After two refusals Android stops showing the dialog; send the user to app settings instead.
        if (vm.locationAsked++ >= 2) vm.openAppSettings(ctx)
        else locationPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    val center = { map?.mapCenter?.let { Pt(it.latitude, it.longitude) } ?: Pt(vm.camera.lat, vm.camera.lon) }

    Box(Modifier.fillMaxSize().background(Bg)) {
        MapPane(vm, status) { map = it }
        Box(Modifier.fillMaxWidth().height(150.dp).background(Brush.verticalGradient(listOf(Color(0xE6070A0E), Color.Transparent))))

        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            SearchBox(vm)
            AnimatedVisibility(status.running, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                LivePill(status, Modifier.padding(top = 10.dp))
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
                AnimatedVisibility(status.running && status.mode == Mode.JOYSTICK,
                    enter = scaleIn(spring(0.6f)) + fadeIn(), exit = scaleOut() + fadeOut()) { Joystick() }
                Spacer(Modifier.weight(1f))
                MapFabs(vm, status)
            }
            SnackbarHost(snack, Modifier.padding(horizontal = 8.dp)) {
                Snackbar(it, containerColor = Color(0xFF1B2330), contentColor = Color(0xFFE7EEF6), shape = RoundedCornerShape(14.dp))
            }
            ControlPanel(vm, status,
                onStart = { vm.start(center()) },
                onImport = { openGpx.launch(arrayOf("*/*")) },
                onExport = { saveGpx.launch("rute-mock.gpx") },
                onName = { naming = it },
                onRoutes = { showRoutes = true })
        }
    }

    if (vm.showSetup) SetupSheet(vm, requestLocation) {
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    naming?.let { kind ->
        NameDialog(kind, onDismiss = { naming = null }) { name ->
            if (kind == NameFor.PLACE) vm.saveFavorite(name) else vm.saveRoute(name)
            naming = null
        }
    }
    if (showRoutes) RoutesDialog(vm) { showRoutes = false }
}

// ---------- map ----------

@Composable
private fun MapPane(vm: AppVm, status: Status, onReady: (MapView) -> Unit) {
    val ctx = LocalContext.current
    val scene = remember { Scene(ctx) }
    val mapView = remember {
        MapView(ctx).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0; maxZoomLevel = 20.0
            isVerticalMapRepetitionEnabled = false
            setScrollableAreaLimitLatitude(MapView.getTileSystem().maxLatitude, MapView.getTileSystem().minLatitude, 0)
            controller.setZoom(vm.camera.zoom)
            controller.setCenter(GeoPoint(vm.camera.lat, vm.camera.lon))
            overlays.add(scene)
            onReady(this)
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        fun save() = vm.saveCamera(Camera(mapView.mapCenter.latitude, mapView.mapCenter.longitude, mapView.zoomLevelDouble))
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) mapView.onResume()
            if (e == Lifecycle.Event.ON_PAUSE) { mapView.onPause(); save() }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); save(); mapView.onDetach() }
    }

    AndroidView({ mapView }, Modifier.fillMaxSize()) { map ->
        val style = vm.mapStyle.tiles
        if (map.tileProvider.tileSource.name() != style.name()) map.setTileSource(style)
        map.overlayManager.tilesOverlay.setColorFilter(if (vm.mapStyle == MapStyle.DARK) darkFilter else null)
        map.overlayManager.tilesOverlay.loadingBackgroundColor = android.graphics.Color.rgb(10, 14, 19)
        map.overlayManager.tilesOverlay.loadingLineColor = android.graphics.Color.rgb(27, 35, 48)
        scene.points = if (vm.mode == Mode.ROUTE) vm.points.toList() else emptyList()
        scene.target = if (vm.mode != Mode.ROUTE) vm.target else null
        scene.trail = vm.trail.toList()
        scene.live = if (status.running) status.fix else null
        scene.editRoute = vm.mode == Mode.ROUTE && !(status.running && status.mode == Mode.ROUTE)
        scene.onTap = vm::onMapTap
        scene.onTapPoint = vm::removePoint
        scene.onDragPoint = vm::movePoint
        scene.onUserPan = { vm.follow = false }
        map.invalidate()
    }

    LaunchedEffect(status.fix, vm.follow, status.running) {
        val f = status.fix
        if (vm.follow && status.running && f != null) mapView.controller.animateTo(GeoPoint(f.lat, f.lon), null, 450L)
    }
    LaunchedEffect(vm.flyTo) {
        vm.flyTo?.let { mapView.controller.animateTo(GeoPoint(it.lat, it.lon), maxOf(mapView.zoomLevelDouble, 16.0), 900L); vm.flyTo = null }
    }
    LaunchedEffect(vm.fitRequest) {
        if (vm.fitRequest == 0) return@LaunchedEffect
        val pts = if (vm.mode == Mode.ROUTE && vm.points.isNotEmpty()) vm.points.toList()
            else listOfNotNull(status.fix?.pt ?: vm.target)
        when {
            pts.isEmpty() -> {}
            pts.size == 1 -> mapView.controller.animateTo(GeoPoint(pts[0].lat, pts[0].lon), 17.0, 700L)
            else -> mapView.post {
                val box = BoundingBox.fromGeoPoints(pts.map { GeoPoint(it.lat, it.lon) }).increaseByScale(1.5f)
                mapView.zoomToBoundingBox(box, true, (48 * ctx.resources.displayMetrics.density).toInt())
            }
        }
    }
}

@Composable
private fun MapFabs(vm: AppVm, s: Status) {
    var styleMenu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.End) {
        AnimatedVisibility(s.running && !vm.follow, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            Fab(Icons.Rounded.MyLocation, "Ikuti posisi", active = true) { vm.follow = true }
        }
        Fab(Icons.Rounded.ZoomOutMap, "Tampilkan semua") { vm.fitRequest++ }
        Box {
            Fab(Icons.Rounded.Layers, "Gaya peta") { styleMenu = true }
            DropdownMenu(styleMenu, { styleMenu = false }) {
                MapStyle.entries.forEach { st ->
                    DropdownMenuItem(text = { Text(st.label) }, onClick = { vm.mapStyle = st; styleMenu = false },
                        trailingIcon = { if (vm.mapStyle == st) Icon(Icons.Rounded.Check, null, tint = Teal) })
                }
            }
        }
    }
}

@Composable
private fun Fab(icon: ImageVector, desc: String, active: Boolean = false, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(Modifier.size(48.dp).glass(CircleShape).clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
        contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = if (active) Teal else MaterialTheme.colorScheme.onSurface)
    }
}

// ---------- top: search + live ----------

@Composable
private fun SearchBox(vm: AppVm) {
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val coord = remember(vm.query) { parseLatLon(vm.query) }
    fun pick(p: Place) { vm.pick(p); focus.clearFocus() }

    Column {
        Row(Modifier.fillMaxWidth().height(54.dp).glass(RoundedCornerShape(18.dp)).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val tint by animateColorAsState(if (focused) Teal else Muted, label = "searchTint")
            Icon(Icons.Rounded.Search, null, tint = tint)
            Spacer(Modifier.width(12.dp))
            BasicTextField(vm.query, { vm.query = it },
                Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(Teal),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (coord != null) pick(Place(fmtPt(coord), coord)) else vm.search() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (vm.query.isEmpty()) Text("Cari tempat atau tempel koordinat", color = Muted, style = MaterialTheme.typography.bodyLarge)
                        inner()
                    }
                })
            when {
                vm.searching -> CircularProgressIndicator(Modifier.padding(14.dp).size(20.dp), color = Teal, strokeWidth = 2.dp)
                vm.query.isNotEmpty() || focused -> IconButton({ vm.clearSearch(); focus.clearFocus() }) {
                    Icon(Icons.Rounded.Close, "Tutup pencarian", tint = Muted)
                }
            }
        }
        val showFavs = focused && vm.query.isEmpty() && vm.favorites.isNotEmpty()
        AnimatedVisibility(vm.results.isNotEmpty() || coord != null || showFavs,
            enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            LazyColumn(Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(max = 320.dp).glass(RoundedCornerShape(18.dp)),
                contentPadding = PaddingValues(vertical = 6.dp)) {
                if (coord != null) item { ResultRow(Icons.Rounded.MyLocation, "Pergi ke koordinat", fmtPt(coord)) { pick(Place(fmtPt(coord), coord)) } }
                if (showFavs) {
                    item { Label("Favorit", Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
                    items(vm.favorites) { f -> ResultRow(Icons.Rounded.Star, f.name, fmtPt(f.pt), Amber) { pick(f) } }
                }
                items(vm.results) { r ->
                    val parts = r.name.split(", ", limit = 2)
                    ResultRow(Icons.Rounded.Place, parts[0], parts.getOrElse(1) { "" }) { pick(r) }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(icon: ImageVector, title: String, sub: String, tint: Color = Teal, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconBubble(icon, tint, 34)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LivePill(s: Status, modifier: Modifier) {
    val color = if (s.paused) Amber else Live
    Row(modifier.glass(CircleShape).padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        PulseDot(color)
        Spacer(Modifier.width(10.dp))
        Text(if (s.paused) "JEDA" else "LIVE", color = color, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
            style = MaterialTheme.typography.labelLarge)
        Text("  ·  ${s.mode.label}", style = MaterialTheme.typography.labelLarge)
        if (s.mode != Mode.TELEPORT) Text("  ·  ${(s.speed * 3.6).roundToInt()} km/j", style = MaterialTheme.typography.labelLarge, color = Muted)
        s.fix?.let {
            Text(String.format(Locale.US, "  ·  %.4f, %.4f", it.lat, it.lon), style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PulseDot(color: Color) {
    val t = rememberInfiniteTransition(label = "pulse")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearOutSlowInEasing)), label = "p")
    Canvas(Modifier.size(10.dp)) {
        drawCircle(color.copy(alpha = (1 - p) * .5f), radius = size.minDimension / 2 * (0.6f + p * 1.2f))
        drawCircle(color, radius = size.minDimension / 2)
    }
}

// ---------- joystick ----------

@Composable
private fun Joystick() {
    var knob by remember { mutableStateOf(Offset.Zero) }
    val haptic = LocalHapticFeedback.current
    val shown by animateOffsetAsState(knob,
        if (knob == Offset.Zero) spring(dampingRatio = 0.45f, stiffness = 500f) else snap(), label = "knob")
    DisposableEffect(Unit) { onDispose { MockState.joyX = 0f; MockState.joyY = 0f } }

    Canvas(Modifier.size(156.dp).glass(CircleShape, Color(0xCC0E141C))
        .semantics { contentDescription = "Joystick arah" }
        .pointerInput(Unit) {
            val r = size.width / 2f; val max = r * 0.6f
            fun set(p: Offset) {
                var v = (p - Offset(r, r)) / max
                val m = v.getDistance(); if (m > 1f) v /= m
                knob = v; MockState.joyX = v.x; MockState.joyY = v.y
            }
            awaitEachGesture {
                val down = awaitFirstDown()
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                set(down.position); down.consume()
                while (true) {
                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    set(c.position); c.consume()
                }
                knob = Offset.Zero; MockState.joyX = 0f; MockState.joyY = 0f
            }
        }) {
        val r = size.minDimension / 2; val max = r * 0.6f
        drawCircle(Teal.copy(alpha = .25f), max, center, style = Stroke(1.5.dp.toPx()))
        for ((dx, dy) in listOf(0f to -1f, 1f to 0f, 0f to 1f, -1f to 0f)) {
            drawLine(Teal.copy(alpha = .6f), center + Offset(dx, dy) * (r - 14.dp.toPx()), center + Offset(dx, dy) * (r - 8.dp.toPx()),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
        val k = center + shown * max
        if (shown != Offset.Zero) drawLine(Teal.copy(alpha = .5f), center, k, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(Color.Black.copy(alpha = .35f), 27.dp.toPx(), k + Offset(0f, 3.dp.toPx()))
        drawCircle(Brush.radialGradient(listOf(Color(0xFFA7F3E6), TealDeep), center = k - Offset(8.dp.toPx(), 8.dp.toPx()), radius = 36.dp.toPx()),
            26.dp.toPx(), k)
    }
}

// ---------- bottom panel ----------

@Composable
private fun ControlPanel(
    vm: AppVm, s: Status,
    onStart: () -> Unit, onImport: () -> Unit, onExport: () -> Unit,
    onName: (NameFor) -> Unit, onRoutes: () -> Unit,
) {
    val ctx = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(s.running) { expanded = !s.running } // get out of the way while mocking
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val editable = !s.running

    Column(Modifier.fillMaxWidth().shadow(24.dp, shape).clip(shape).background(PanelBg).border(1.dp, Hairline, shape)
        .navigationBarsPadding().padding(horizontal = 18.dp).padding(bottom = 12.dp)
        .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))) {
        Box(Modifier.fillMaxWidth()
            .clickable(remember { MutableInteractionSource() }, null) { expanded = !expanded }
            .semantics { contentDescription = if (expanded) "Ciutkan panel" else "Buka panel" }
            .padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(40.dp, 4.dp).clip(CircleShape).background(Color.White.copy(alpha = .18f)))
        }
        Segmented(Mode.entries, vm.mode, { it.label }, { it.icon }, enabled = editable, height = 48) { vm.mode = it }

        if (expanded) {
            Column(Modifier.padding(top = 14.dp).heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                AnimatedContent(vm.mode, label = "mode", transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (fadeIn(tween(220)) + slideInHorizontally { dir * it / 6 }) togetherWith fadeOut(tween(120)) using SizeTransform(clip = false)
                }) { m ->
                    when (m) {
                        Mode.TELEPORT -> TeleportPane(vm, onName)
                        Mode.ROUTE -> RoutePane(vm, editable, onImport, onExport, onName, onRoutes)
                        Mode.JOYSTICK -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            TargetCard(vm.target, "Ketuk peta untuk titik awal (default: tengah peta)", "Titik awal", onCopy = { vm.say("Koordinat disalin") })
                            SwitchRow(Icons.Rounded.PictureInPicture, "Joystick melayang",
                                if (vm.setup.overlay) "Kendalikan dari dalam game atau aplikasi lain" else "Butuh izin tampil di atas aplikasi lain",
                                vm.floating && vm.setup.overlay, editable) { on ->
                                if (on && !vm.setup.overlay) vm.openOverlaySettings(ctx)
                                vm.floating = on
                            }
                        }
                    }
                }
                if (vm.mode != Mode.TELEPORT) SpeedSection(vm)
                SwitchRow(Icons.Rounded.AutoAwesome, "Gerak natural", "Variasi kecepatan & akurasi seperti GPS asli",
                    vm.humanize, editable) { vm.humanize = it }
            }
        }
        AnimatedVisibility(s.running && s.mode == Mode.ROUTE) { RunInfo(s, Modifier.padding(top = 14.dp)) }
        Spacer(Modifier.height(14.dp))
        Actions(vm, s, onStart)
        Text(vm.mapStyle.tiles.copyrightNotice ?: "", style = MaterialTheme.typography.labelSmall, color = Muted.copy(alpha = .6f),
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
    }
}

@Composable
private fun <T> Segmented(
    options: List<T>, selected: T, label: (T) -> String, icon: (T) -> ImageVector,
    enabled: Boolean = true, height: Int = 42, onSelect: (T) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(16.dp)).background(Card).padding(4.dp)) {
        val w = maxWidth / options.size
        val x by animateDpAsState(w * options.indexOf(selected), spring(dampingRatio = .75f, stiffness = 500f), label = "seg")
        Box(Modifier.offset(x = x).width(w).fillMaxHeight().clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(listOf(TealDeep, Teal))).alpha(if (enabled) 1f else .6f))
        Row(Modifier.fillMaxSize()) {
            options.forEach { o ->
                val sel = o == selected
                val color by animateColorAsState(when { sel -> OnTeal; enabled -> MaterialTheme.colorScheme.onSurface; else -> Muted.copy(alpha = .5f) }, label = "segc")
                Row(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = enabled && !sel, role = Role.Tab) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSelect(o) },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon(o), null, tint = color, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(label(o), color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun TeleportPane(vm: AppVm, onName: (NameFor) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TargetCard(vm.target, "Ketuk peta atau cari lokasi untuk memilih titik", onCopy = { vm.say("Koordinat disalin") }) {
            if (vm.target != null) IconButton({ onName(NameFor.PLACE) }) { Icon(Icons.Rounded.StarBorder, "Simpan favorit", tint = Amber) }
        }
        if (vm.favorites.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.favorites, key = { it.name }) { f ->
                    Chip(f.name, Icons.Rounded.Star, Amber, selected = vm.target == f.pt, onLongClick = { vm.deleteFavorite(f) }) { vm.pick(f) }
                }
            }
            Hint("Tahan chip favorit untuk menghapus")
        }
    }
}

@Composable
private fun RoutePane(vm: AppVm, editable: Boolean, onImport: () -> Unit, onExport: () -> Unit, onName: (NameFor) -> Unit, onRoutes: () -> Unit) {
    val total by remember { derivedStateOf { RoutePlayer.total(vm.points) } }
    val n = vm.points.size
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Titik", "$n", Modifier.weight(1f))
            Stat("Jarak", fmtDist(total), Modifier.weight(1f))
            Stat("Estimasi", if (n < 2) "–" else fmtDur(total / (vm.speedKmh / 3.6)), Modifier.weight(1.2f))
        }
        if (n < 2) Hint("Ketuk peta untuk menambah titik · seret titik untuk memindah · ketuk titik untuk menghapus")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Tool(Icons.Rounded.Undo, "Urungkan", editable && n > 0, vm::undo) }
            item { Tool(Icons.Rounded.SwapHoriz, "Balik", editable && n > 1, vm::reverse) }
            item { Tool(Icons.Rounded.DeleteSweep, "Hapus", editable && n > 0, vm::clearRoute) }
            item { Tool(Icons.Rounded.BookmarkAdd, "Simpan", n > 1) { onName(NameFor.ROUTE) } }
            item { Tool(Icons.Rounded.FolderOpen, "Rute tersimpan", editable, onRoutes) }
            item { Tool(Icons.Rounded.FileUpload, "Impor GPX", editable, onImport) }
            item { Tool(Icons.Rounded.FileDownload, "Ekspor GPX", n > 1, onExport) }
        }
        Segmented(Loop.entries, vm.loop, { it.label }, { it.icon }, enabled = editable) { vm.loop = it }
    }
}

@Composable
private fun SpeedSection(vm: AppVm) {
    val kmh = vm.speedKmh
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Kecepatan", style = MaterialTheme.typography.titleSmall, color = Muted, modifier = Modifier.padding(bottom = 4.dp))
            Spacer(Modifier.weight(1f))
            AnimatedContent(kmh.roundToInt(), label = "kmh", transitionSpec = {
                val up = targetState > initialState
                (slideInVertically { if (up) it else -it } + fadeIn()) togetherWith (slideOutVertically { if (up) -it else it } + fadeOut())
            }) { Text("$it", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Teal) }
            Text(" km/j", style = MaterialTheme.typography.bodyMedium, color = Muted, modifier = Modifier.padding(bottom = 4.dp))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(SPEEDS) { i, p ->
                Chip(p.label, PRESET_ICONS[i], Teal, selected = abs(kmh - p.kmh) < 0.5f) { vm.speedKmh = p.kmh; vm.pushSpeed() }
            }
        }
        Slider(kmh, { vm.speedKmh = it }, valueRange = 1f..150f, onValueChangeFinished = vm::pushSpeed,
            colors = SliderDefaults.colors(thumbColor = Teal, activeTrackColor = Teal, inactiveTrackColor = Track))
    }
}

@Composable
private fun RunInfo(s: Status, modifier: Modifier) {
    val len = s.length.coerceAtLeast(1.0)
    val done = if (s.loop == Loop.ONCE) min(s.traveled, len) else s.traveled % len
    val progress by animateFloatAsState((done / len).toFloat(), tween(MockService.TICK_MS.toInt(), easing = LinearEasing), label = "progress")
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(when {
                s.finished -> "Tiba di tujuan ✓"
                s.loop == Loop.ONCE -> "Progres rute"
                else -> "Putaran ${(s.traveled / len).toInt() + 1}"
            }, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            Text("${fmtDist(done)} / ${fmtDist(s.length)}", style = MaterialTheme.typography.labelLarge, color = Muted)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Track)) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(Color(0xFFFF8FA3), Live))))
        }
        if (s.loop == Loop.ONCE && !s.finished && s.setSpeed > 0)
            Text("Sisa ${fmtDist(len - done)} · ± ${fmtDur((len - done) / s.setSpeed)}", style = MaterialTheme.typography.bodySmall,
                color = Muted, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Actions(vm: AppVm, s: Status, onStart: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    AnimatedContent(s.running, label = "actions", transitionSpec = {
        (fadeIn() + scaleIn(initialScale = .94f)) togetherWith fadeOut()
    }) { running ->
        if (!running) {
            val shine = rememberInfiniteTransition(label = "shine")
            val p by shine.animateFloat(-0.3f, 1.6f, infiniteRepeatable(tween(2600, delayMillis = 900)), label = "p")
            val shape = RoundedCornerShape(18.dp)
            Box(Modifier.fillMaxWidth().height(58.dp)
                .shadow(18.dp, shape, ambientColor = Teal, spotColor = Teal).clip(shape)
                .background(Brush.horizontalGradient(listOf(TealDeep, Teal)))
                .drawWithContent {
                    drawContent()
                    val x = size.width * p
                    drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = .28f), Color.Transparent),
                        start = Offset(x - 140f, 0f), end = Offset(x, size.height)))
                }
                .clickable { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onStart() },
                contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = OnTeal)
                    Spacer(Modifier.width(8.dp))
                    Text(when (vm.mode) { Mode.TELEPORT -> "Teleport ke sini"; Mode.ROUTE -> "Mulai rute"; Mode.JOYSTICK -> "Mulai joystick" },
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = OnTeal)
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val shape = RoundedCornerShape(18.dp)
                if (s.mode != Mode.TELEPORT) {
                    Row(Modifier.weight(1f).height(58.dp).clip(shape).background(Card).border(1.dp, Hairline, shape)
                        .clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); vm.togglePause() },
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (s.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null, tint = Amber)
                        Spacer(Modifier.width(8.dp))
                        Text(if (s.paused) "Lanjut" else "Jeda", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
                Row(Modifier.weight(1.3f).height(58.dp).shadow(16.dp, shape, ambientColor = Live, spotColor = Live).clip(shape)
                    .background(Brush.horizontalGradient(listOf(Color(0xFFE11D48), Live)))
                    .clickable { haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.stop() },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Stop, null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Hentikan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

// ---------- small building blocks ----------

@Composable
private fun TargetCard(p: Pt?, hint: String, label: String = "Titik tujuan", onCopy: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val clipboard = LocalClipboardManager.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconBubble(Icons.Rounded.Place, Teal, 40)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Muted)
            AnimatedContent(p, label = "target") { v ->
                if (v == null) Text(hint, style = MaterialTheme.typography.bodyMedium)
                else Text(fmtPt(v), style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable { clipboard.setText(AnnotatedString(fmtPt(v))); onCopy() })
            }
        }
        trailing()
    }
}

@Composable
private fun IconBubble(icon: ImageVector, tint: Color, size: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(tint.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size((size * 0.5f).dp))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Card).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
        AnimatedContent(value, label = "stat", transitionSpec = { fadeIn() togetherWith fadeOut() }) {
            Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun Tool(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(12.dp)
    Row(Modifier.alpha(if (enabled) 1f else .4f).clip(shape).background(Card).border(1.dp, Hairline, shape)
        .clickable(enabled) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
        .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(17.dp), tint = Teal)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Chip(label: String, icon: ImageVector, tint: Color, selected: Boolean, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(12.dp)
    val bg by animateColorAsState(if (selected) tint.copy(alpha = .16f) else Card, label = "chipBg")
    val border by animateColorAsState(if (selected) tint.copy(alpha = .6f) else Hairline, label = "chipBorder")
    Row(Modifier.clip(shape).background(bg).border(1.dp, border, shape)
        .combinedClickable(
            onLongClick = onLongClick?.let { f -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); f() } },
            onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() })
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = if (selected) tint else Muted)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) tint else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, sub: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val tint by animateColorAsState(if (checked) Teal else Muted, label = "switchTint")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card)
        .toggleable(checked, enabled, Role.Switch, onChange).alpha(if (enabled) 1f else .5f)
        .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Switch(checked, null, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Teal, checkedThumbColor = OnTeal, uncheckedTrackColor = Track, uncheckedBorderColor = Track))
    }
}

@Composable
private fun Hint(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Info, null, Modifier.size(14.dp), tint = Muted)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), modifier, style = MaterialTheme.typography.labelSmall, color = Muted, letterSpacing = 1.2.sp)

// ---------- sheets & dialogs ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupSheet(vm: AppVm, requestLocation: () -> Unit, requestNotif: () -> Unit) {
    val ctx = LocalContext.current
    val st = vm.setup
    ModalBottomSheet({ vm.showSetup = false }, containerColor = Color(0xFF0E141C), contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = .2f)) }) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Siapkan Mock Location", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Cukup sekali. Android mewajibkan langkah ini sebelum lokasi palsu bisa dipakai.",
                style = MaterialTheme.typography.bodyMedium, color = Muted)
            var n = 0
            Step(++n, st.location, "Izin lokasi", "Dibutuhkan untuk layanan lokasi latar depan.", "Izinkan", onAction = requestLocation)
            if (Build.VERSION.SDK_INT >= 33)
                Step(++n, st.notifications, "Notifikasi", "Kontrol jeda & stop langsung dari notifikasi.", "Izinkan", onAction = requestNotif)
            Step(++n, st.devOptions, "Aktifkan opsi developer", "Setelan → Tentang ponsel → ketuk Nomor build 7×.", "Buka") { vm.openDevSettings(ctx) }
            Step(++n, st.mockApp, "Pilih aplikasi lokasi palsu", "Opsi developer → Pilih aplikasi lokasi palsu → Mock Location.",
                "Buka", enabled = st.devOptions) { vm.openDevSettings(ctx) }
            Step(++n, st.overlay, "Joystick melayang (opsional)", "Izinkan tampil di atas aplikasi lain.", "Izinkan") { vm.openOverlaySettings(ctx) }
            Spacer(Modifier.height(4.dp))
            Button({ vm.showSetup = false }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (st.ready) Teal else Track,
                    contentColor = if (st.ready) OnTeal else MaterialTheme.colorScheme.onSurface)) {
                Text(if (st.ready) "Siap digunakan" else "Nanti saja", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Step(n: Int, done: Boolean, title: String, desc: String, action: String, enabled: Boolean = true, onAction: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val bg by animateColorAsState(if (done) Teal.copy(alpha = .08f) else Card, label = "stepBg")
    Row(Modifier.fillMaxWidth().clip(shape).background(bg).border(1.dp, if (done) Teal.copy(alpha = .3f) else Hairline, shape).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        AnimatedContent(done, label = "step", transitionSpec = { (scaleIn() + fadeIn()) togetherWith fadeOut() }) { ok ->
            Box(Modifier.size(32.dp).clip(CircleShape).background(if (ok) Teal else Track), contentAlignment = Alignment.Center) {
                if (ok) Icon(Icons.Rounded.Check, "Selesai", Modifier.size(18.dp), tint = OnTeal)
                else Text("$n", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        if (!done) TextButton(onAction, enabled = enabled, colors = ButtonDefaults.textButtonColors(contentColor = Teal)) { Text(action) }
    }
}

@Composable
private fun NameDialog(kind: NameFor, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(onDismiss, containerColor = Color(0xFF111720), shape = RoundedCornerShape(24.dp),
        title = { Text(kind.title) },
        text = {
            OutlinedTextField(name, { name = it.take(40) }, Modifier.focusRequester(focus), singleLine = true,
                placeholder = { Text("Nama") }, shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onSave(name.trim()) }),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Teal, cursorColor = Teal))
        },
        confirmButton = { TextButton({ onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("Simpan") } },
        dismissButton = { TextButton(onDismiss) { Text("Batal", color = Muted) } })
}

@Composable
private fun RoutesDialog(vm: AppVm, onDismiss: () -> Unit) {
    AlertDialog(onDismiss, containerColor = Color(0xFF111720), shape = RoundedCornerShape(24.dp),
        title = { Text("Rute tersimpan") },
        text = {
            if (vm.routes.isEmpty()) Text("Belum ada rute. Buat rute di peta lalu tekan Simpan.", color = Muted)
            else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(vm.routes, key = { it.name }) { r ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { vm.loadRoute(r); onDismiss() }
                        .padding(vertical = 8.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBubble(Icons.Rounded.Timeline, Teal, 36)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${r.points.size} titik · ${fmtDist(RoutePlayer.total(r.points))}", style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                        IconButton({ vm.deleteRoute(r) }) { Icon(Icons.Rounded.DeleteOutline, "Hapus ${r.name}", tint = Muted) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Tutup") } })
}
