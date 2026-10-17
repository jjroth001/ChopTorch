package com.kuniec.latarka

import android.content.Context
import android.content.pm.ActivityInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max

/** Lets the background service ignore chops while calibration is listening. */
object CalibrationGate {
    @Volatile
    var isActive = false
}

private const val GESTURES_NEEDED = 5
private const val EVERYDAY_SECONDS = 20
private const val SPARK_INTERVAL_MS = 200L
private const val SPARK_SAMPLES = (EVERYDAY_SECONDS * 1000 / SPARK_INTERVAL_MS).toInt()

private enum class Step { CHOPS, EVERYDAY, RESULTS, TRY }

/**
 * Guided calibration: the user chop-chops a few times, then moves normally for a while, and
 * ChopTorch suggests a threshold and timing that fit. Nothing is saved until they choose Save.
 */
@Composable
fun CalibrationScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val startConfig = remember { FlashlightConfig.load(context) }
    val collector = remember { ChopGestureCollector(startConfig.shakeCount) }
    val gestureStrengths = remember { mutableStateListOf<Float>() }
    var step by remember { mutableStateOf(Step.CHOPS) }
    var result by remember { mutableStateOf<Calibration.Result?>(null) }

    CalibrationSession()
    BackHandler { if (step == Step.TRY) step = Step.RESULTS else onClose() }

    fun showResults(everydayG: Float?) {
        result = Calibration.compute(collector.gestures, everydayG, startConfig, System.currentTimeMillis())
        step = Step.RESULTS
    }

    fun save() {
        result?.let { FlashlightConfig.save(context, it.config) }
        // The settings screen restarts the service when it reappears, which reloads these values.
        onClose()
    }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.calibrate_close))
            }
            Text(stringResource(R.string.calibrate_title), style = MaterialTheme.typography.titleLarge)
        }

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                (fadeIn(tween(300, delayMillis = 100)) + slideInHorizontally(tween(300)) { it / 10 }) togetherWith
                    fadeOut(tween(150))
            },
            label = "calibrationStep"
        ) { current ->
            when (current) {
                Step.CHOPS -> ChopsStep(
                    collector = collector,
                    strengths = gestureStrengths,
                    onDone = { step = Step.EVERYDAY }
                )
                Step.EVERYDAY -> EverydayStep(onDone = ::showResults)
                Step.RESULTS -> result?.let {
                    ResultsStep(
                        result = it,
                        old = startConfig,
                        onTry = { step = Step.TRY },
                        onSave = ::save,
                        onKeepOld = onClose
                    )
                }
                Step.TRY -> result?.let {
                    TryStep(config = it.config, onBack = { step = Step.RESULTS }, onSave = ::save)
                }
            }
        }
    }
}

/**
 * While calibration is open: pauses the background gesture, keeps the screen on, and locks the
 * orientation so chopping doesn't rotate the screen and restart the activity.
 */
@Composable
private fun CalibrationSession() {
    val view = LocalView.current
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        view.keepScreenOn = true
        val oldOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> CalibrationGate.isActive = true
                Lifecycle.Event.ON_STOP -> CalibrationGate.isActive = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            CalibrationGate.isActive = false
            view.keepScreenOn = false
            if (oldOrientation != null) activity?.requestedOrientation = oldOrientation
        }
    }
}

/** Delivers accelerometer samples (m/s², timestamp in ms) while the screen is visible. */
@Composable
private fun AccelerometerListener(onSample: (Float, Float, Float, Long) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onSample)
    DisposableEffect(lifecycleOwner) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                latest(event.values[0], event.values[1], event.values[2], event.timestamp / 1_000_000L)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START ->
                    sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME, 0)
                Lifecycle.Event.ON_STOP -> sensorManager.unregisterListener(listener)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sensorManager.unregisterListener(listener)
        }
    }
}

private fun View.confirmBuzz() {
    performHapticFeedback(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.LONG_PRESS
    )
}

@Composable
private fun StepColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        content = content
    )
}

@Composable
private fun StepHeader(step: Int, total: Int = 3) {
    Text(
        stringResource(R.string.calibrate_step, step, total),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            val color by animateColorAsState(
                if (i < step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                animationSpec = tween(400),
                label = "stepSegment"
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
        }
    }
    Spacer(Modifier.height(28.dp))
}

@Composable
private fun StepTitle(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(10.dp))
    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The torch mark in a tinted circle that swells briefly each time [pulse] changes. */
@Composable
private fun PulsingTorch(pulse: Int, modifier: Modifier = Modifier) {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(pulse) {
        if (pulse > 0) {
            scale.animateTo(1.12f, tween(110))
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
        }
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(184.dp)
                .scale(scale.value)
                .border(2.dp, MaterialTheme.colorScheme.primaryContainer, CircleShape)
                .padding(16.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painterResource(R.drawable.ic_launcher_monochrome),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(150.dp)
            )
        }
    }
}

@Composable
private fun ChopsStep(
    collector: ChopGestureCollector,
    strengths: SnapshotStateList<Float>,
    onDone: () -> Unit
) {
    val view = LocalView.current
    var recorder by remember { mutableStateOf(ChopRecorder()) }
    var pulse by remember { mutableIntStateOf(0) }

    AccelerometerListener { x, y, z, t ->
        if (strengths.size >= GESTURES_NEEDED) return@AccelerometerListener
        val chop = recorder.onSensorChanged(x, y, z, t) ?: return@AccelerometerListener
        val gesture = collector.add(chop) ?: return@AccelerometerListener
        strengths += gesture.minOf { it.strengthG }
        pulse++
        view.confirmBuzz()
    }
    LaunchedEffect(strengths.size) {
        if (strengths.size >= GESTURES_NEEDED) {
            delay(800)
            onDone()
        }
    }

    StepColumn {
        StepHeader(1)
        StepTitle(
            pluralStringResource(R.plurals.calibrate_chops_title, GESTURES_NEEDED, GESTURES_NEEDED),
            stringResource(R.string.calibrate_chops_body)
        )
        Spacer(Modifier.height(24.dp))
        PulsingTorch(pulse)
        Spacer(Modifier.height(24.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)) {
            repeat(GESTURES_NEEDED) { i -> ProgressDot(done = i < strengths.size, current = i == strengths.size) }
        }
        Spacer(Modifier.height(14.dp))
        val count = strengths.size
        Text(
            when (count) {
                0 -> stringResource(R.string.calibrate_chops_waiting)
                GESTURES_NEEDED -> stringResource(R.string.calibrate_chops_done)
                else -> stringResource(R.string.calibrate_chops_progress, count, GESTURES_NEEDED)
            },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            stringResource(R.string.calibrate_chops_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(20.dp))

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.calibrate_chops_strength), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                val scaleMax = max(4f, strengths.maxOrNull() ?: 0f)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(92.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom
                ) {
                    repeat(GESTURES_NEEDED) { i -> StrengthBar(strengths.getOrNull(i), scaleMax) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = {
                collector.reset()
                strengths.clear()
                recorder = ChopRecorder()
            },
            enabled = strengths.isNotEmpty() && strengths.size < GESTURES_NEEDED,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) { Text(stringResource(R.string.calibrate_start_over)) }
    }
}

@Composable
private fun ProgressDot(done: Boolean, current: Boolean) {
    val fill by animateColorAsState(
        if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        animationSpec = tween(250),
        label = "dotFill"
    )
    val ring = when {
        done -> MaterialTheme.colorScheme.primary
        current -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(fill)
            .border(if (current) 2.5.dp else 2.dp, ring, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(visible = done, enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy))) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp)
            )
        }
        if (current) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun StrengthBar(strengthG: Float?, scaleMax: Float) {
    val height by animateDpAsState(
        if (strengthG == null) 4.dp else (64 * strengthG / scaleMax).dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "barHeight"
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (strengthG != null) {
            Text(
                stringResource(R.string.calibrate_g, strengthG),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
        }
        Box(
            Modifier
                .width(30.dp)
                .height(height)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (strengthG == null) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.primary
                )
        )
    }
}

@Composable
private fun EverydayStep(onDone: (Float?) -> Unit) {
    val recorder = remember { ChopRecorder() }
    var strongest by remember { mutableFloatStateOf(0f) }
    var secondsLeft by remember { mutableIntStateOf(EVERYDAY_SECONDS) }
    val spark = remember { mutableStateListOf<Float>() }
    val bucket = remember { floatArrayOf(0f) }
    val progress = remember { Animatable(0f) }

    AccelerometerListener { x, y, z, t ->
        recorder.onSensorChanged(x, y, z, t)?.let { strongest = max(strongest, it.strengthG) }
        bucket[0] = max(bucket[0], recorder.movementG)
    }
    LaunchedEffect(Unit) {
        launch { progress.animateTo(1f, tween(EVERYDAY_SECONDS * 1000, easing = LinearEasing)) }
        launch {
            while (true) {
                delay(SPARK_INTERVAL_MS)
                spark += bucket[0]
                if (spark.size > SPARK_SAMPLES) spark.removeAt(0)
                bucket[0] = 0f
            }
        }
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft--
        }
        onDone(strongest)
    }

    StepColumn {
        StepHeader(2)
        StepTitle(
            stringResource(R.string.calibrate_everyday_title),
            pluralStringResource(R.plurals.calibrate_everyday_body, EVERYDAY_SECONDS, EVERYDAY_SECONDS)
        )
        Spacer(Modifier.height(28.dp))

        val track = MaterialTheme.colorScheme.secondaryContainer
        val arc = MaterialTheme.colorScheme.primary
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(176.dp)) {
                val stroke = 10.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                drawArc(
                    arc, -90f, 360f * progress.value, false, Offset(inset, inset), arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.calibrate_time_left, secondsLeft / 60, secondsLeft % 60),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.calibrate_left),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(28.dp))

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(16.dp)) {
                Row {
                    Text(
                        stringResource(R.string.calibrate_everyday_strongest),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (strongest > 0f) stringResource(R.string.calibrate_g, strongest)
                        else stringResource(R.string.calibrate_under_g, ChopRecorder.FLOOR_G),
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                Spacer(Modifier.height(10.dp))
                val line = MaterialTheme.colorScheme.onSurfaceVariant
                val base = MaterialTheme.colorScheme.outlineVariant
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    drawLine(base, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                    if (spark.size < 2) return@Canvas
                    val top = max(2f, spark.max())
                    val dx = size.width / (SPARK_SAMPLES - 1)
                    val path = Path()
                    spark.forEachIndexed { i, v ->
                        val x = i * dx
                        val y = size.height - (v / top) * size.height
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, line, style = Stroke(2.dp.toPx()))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.calibrate_everyday_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { onDone(null) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.calibrate_skip))
        }
    }
}

@Composable
private fun ResultsStep(
    result: Calibration.Result,
    old: FlashlightConfig,
    onTry: () -> Unit,
    onSave: () -> Unit,
    onKeepOld: () -> Unit
) {
    StepColumn {
        StepHeader(3)
        StepTitle(
            stringResource(R.string.calibrate_results_title),
            stringResource(
                when (result.margin) {
                    Calibration.Margin.PLENTY -> R.string.calibrate_margin_plenty
                    Calibration.Margin.TIGHT -> R.string.calibrate_margin_tight
                    Calibration.Margin.OVERLAP -> R.string.calibrate_margin_overlap
                    Calibration.Margin.UNKNOWN -> R.string.calibrate_margin_unknown
                }
            )
        )
        Spacer(Modifier.height(20.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.calibrate_chart_title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                StrengthChart(result)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.calibrate_new_settings), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        val new = result.config
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChangeRow(
                stringResource(R.string.calibrate_row_strength),
                stringResource(R.string.calibrate_g, old.gForceThreshold),
                stringResource(R.string.calibrate_g, new.gForceThreshold)
            )
            ChangeRow(
                stringResource(R.string.calibrate_row_shortest),
                stringResource(R.string.calibrate_ms, old.slopDelayMs),
                stringResource(R.string.calibrate_ms, new.slopDelayMs)
            )
            ChangeRow(
                stringResource(R.string.calibrate_row_longest),
                stringResource(R.string.calibrate_ms, old.resetTimeoutMs),
                stringResource(R.string.calibrate_ms, new.resetTimeoutMs)
            )
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onTry, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.calibrate_try))
            }
            Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.calibrate_save))
            }
        }
        TextButton(onClick = onKeepOld, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.calibrate_keep_old))
        }
    }
}

/** Everyday movement, the user's chops and the new trigger point on one 0–N g scale. */
@Composable
private fun StrengthChart(result: Calibration.Result) {
    val measurer = rememberTextMeasurer()
    val everydayLabel = stringResource(R.string.calibrate_chart_everyday)
    val chopsLabel = stringResource(R.string.calibrate_chart_chops)
    val triggerLabel = stringResource(R.string.calibrate_chart_trigger, result.config.gForceThreshold)
    val unit = stringResource(R.string.calibrate_g_unit)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val everydayColor = MaterialTheme.colorScheme.secondaryContainer
    val axis = MaterialTheme.colorScheme.outlineVariant
    val trigger = MaterialTheme.colorScheme.tertiary
    val small = TextStyle(fontSize = 11.sp, color = muted)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(128.dp)
    ) {
        val scaleMax = max(5f, ceil(result.strongestChopG + 0.5f))
        val pad = 12.dp.toPx()
        fun x(g: Float) = pad + (size.width - 2 * pad) * (g / scaleMax)
        val bandTop = 30.dp.toPx()
        val bandH = 22.dp.toPx()
        val radius = CornerRadius(bandH / 2)

        result.everydayG?.let { e ->
            val end = x(max(e, ChopRecorder.FLOOR_G))
            drawRoundRect(everydayColor, Offset(x(0f), bandTop), Size(end - x(0f), bandH), radius)
            drawText(measurer, everydayLabel, Offset(x(0f), bandTop - 18.dp.toPx()), small)
        }
        val chopStart = x(result.weakestChopG)
        val chopWidth = max(x(result.strongestChopG) - chopStart, bandH)
        drawRoundRect(primary, Offset(chopStart, bandTop), Size(chopWidth, bandH), radius)
        drawText(
            measurer, chopsLabel, Offset(chopStart, bandTop - 18.dp.toPx()),
            small.copy(color = primary, fontWeight = FontWeight.SemiBold)
        )

        val tx = x(result.config.gForceThreshold)
        drawLine(
            trigger, Offset(tx, bandTop - 6.dp.toPx()), Offset(tx, bandTop + bandH + 10.dp.toPx()),
            strokeWidth = 2.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
        )
        val triggerText = measurer.measure(triggerLabel, small.copy(color = trigger, fontWeight = FontWeight.SemiBold))
        val triggerX = (tx - triggerText.size.width / 2f).coerceIn(0f, size.width - triggerText.size.width)
        drawText(triggerText, topLeft = Offset(triggerX, bandTop + bandH + 12.dp.toPx()))

        val axisY = size.height - 20.dp.toPx()
        drawLine(axis, Offset(x(0f), axisY), Offset(x(scaleMax), axisY), 1.dp.toPx())
        for (g in 0..scaleMax.toInt()) {
            val label = measurer.measure("$g $unit", small)
            drawText(label, topLeft = Offset(x(g.toFloat()) - label.size.width / 2f, axisY + 4.dp.toPx()))
        }
    }
}

@Composable
private fun ChangeRow(label: String, oldValue: String, newValue: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (oldValue != newValue) {
            Text(
                oldValue,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                textDecoration = TextDecoration.LineThrough
            )
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .size(16.dp)
            )
        }
        Text(
            newValue,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun TryStep(config: FlashlightConfig, onBack: () -> Unit, onSave: () -> Unit) {
    val view = LocalView.current
    val detector = remember(config) { ShakeDetector(config) }
    var count by remember { mutableIntStateOf(0) }

    AccelerometerListener { x, y, z, t ->
        if (detector.onSensorChanged(x, y, z, t)) {
            count++
            view.confirmBuzz()
        }
    }

    StepColumn {
        StepTitle(stringResource(R.string.calibrate_try_title), stringResource(R.string.calibrate_try_body))
        Spacer(Modifier.height(28.dp))
        PulsingTorch(count)
        Spacer(Modifier.height(20.dp))
        AnimatedContent(targetState = count, label = "tryCount") { n ->
            Text(
                if (n == 0) stringResource(R.string.calibrate_try_waiting)
                else pluralStringResource(R.plurals.calibrate_try_count, n, n),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.calibrate_back))
            }
            Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.calibrate_save))
            }
        }
    }
}
