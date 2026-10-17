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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Lets the background service ignore chops while calibration is listening. */
object CalibrationGate {
    @Volatile
    var isActive = false
}

private const val GESTURES_NEEDED = 5
private const val EVERYDAY_SECONDS = 20
private const val SPARK_INTERVAL_MS = 200L
private const val SPARK_SAMPLES = (EVERYDAY_SECONDS * 1000 / SPARK_INTERVAL_MS).toInt()

private enum class Step { CHOPS, EVERYDAY, TUNING, RESULTS, TRY }

/**
 * Guided calibration: the user chop-chops a few times, then moves normally for a while, and
 * ChopTorch tries settings on both recordings to find ones that fit. Nothing is saved until they
 * choose Save.
 */
@Composable
fun CalibrationScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val startConfig = remember { FlashlightConfig.load(context) }
    val collector = remember { ChopGestureCollector(startConfig.shakeCount) }
    val gestureStrengths = remember { mutableStateListOf<Float>() }
    var chopRecording by remember { mutableStateOf(MotionRecording()) }
    var step by remember { mutableStateOf(Step.CHOPS) }
    var result by remember { mutableStateOf<Calibration.Result?>(null) }
    var chosenG by remember { mutableFloatStateOf(startConfig.gForceThreshold) }

    CalibrationSession()
    BackHandler { if (step == Step.TRY) step = Step.RESULTS else onClose() }

    fun showResults(everyday: MotionRecording?) {
        step = Step.TUNING
        scope.launch {
            val tuned = withContext(Dispatchers.Default) {
                Calibration.tune(chopRecording, collector.gestures, everyday, startConfig, System.currentTimeMillis())
            }
            // Long enough to read, so the step doesn't flash past.
            delay(600)
            result = tuned
            chosenG = tuned.recommendedG
            step = Step.RESULTS
        }
    }

    fun save() {
        result?.let { FlashlightConfig.save(context, it.configAt(chosenG)) }
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
                    recording = chopRecording,
                    onStartOver = { chopRecording = MotionRecording() },
                    onDone = { step = Step.EVERYDAY }
                )
                Step.EVERYDAY -> EverydayStep(onDone = ::showResults)
                Step.TUNING -> TuningStep()
                Step.RESULTS -> result?.let {
                    ResultsStep(
                        result = it,
                        old = startConfig,
                        chosenG = chosenG,
                        onChoose = { g -> chosenG = g },
                        onTry = { step = Step.TRY },
                        onSave = ::save,
                        onKeepOld = onClose
                    )
                }
                Step.TRY -> result?.let {
                    TryStep(config = it.configAt(chosenG), onBack = { step = Step.RESULTS }, onSave = ::save)
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
    recording: MotionRecording,
    onStartOver: () -> Unit,
    onDone: () -> Unit
) {
    val view = LocalView.current
    var recorder by remember { mutableStateOf(ChopRecorder()) }
    var pulse by remember { mutableIntStateOf(0) }

    AccelerometerListener { x, y, z, t ->
        // Keep recording until the step ends, so the last chop-chop is complete when replayed.
        recording.add(x, y, z, t)
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
                onStartOver()
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
private fun EverydayStep(onDone: (MotionRecording?) -> Unit) {
    val recorder = remember { ChopRecorder() }
    val recording = remember { MotionRecording() }
    var strongest by remember { mutableFloatStateOf(0f) }
    var secondsLeft by remember { mutableIntStateOf(EVERYDAY_SECONDS) }
    val spark = remember { mutableStateListOf<Float>() }
    val bucket = remember { floatArrayOf(0f) }
    val progress = remember { Animatable(0f) }

    AccelerometerListener { x, y, z, t ->
        recording.add(x, y, z, t)
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
        onDone(recording)
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
private fun TuningStep() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.calibrate_tuning),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ResultsStep(
    result: Calibration.Result,
    old: FlashlightConfig,
    chosenG: Float,
    onChoose: (Float) -> Unit,
    onTry: () -> Unit,
    onSave: () -> Unit,
    onKeepOld: () -> Unit
) {
    val i = result.indexOf(chosenG)
    val caught = result.caught[i]
    val accidental = result.accidental?.get(i)
    val missed = result.gestureCount - caught
    val new = result.configAt(chosenG)

    StepColumn {
        StepHeader(3)
        Text(
            stringResource(R.string.calibrate_results_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                value = stringResource(R.string.calibrate_stat_caught, caught, result.gestureCount),
                label = stringResource(R.string.calibrate_stat_caught_label),
                warning = if (missed > 0) StatWarning.MISSED else null,
                modifier = Modifier.weight(1f)
            )
            StatCard(
                value = accidental?.toString() ?: "–",
                label = stringResource(
                    if (accidental == null) R.string.calibrate_stat_unchecked_label
                    else R.string.calibrate_stat_accidental_label
                ),
                warning = if ((accidental ?: 0) > 0) StatWarning.ACCIDENTAL else null,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(12.dp))

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(16.dp)) {
                Row {
                    Text(
                        stringResource(R.string.calibrate_sensitivity),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    val triggerColor by animateColorAsState(
                        when {
                            (accidental ?: 0) > 0 -> MaterialTheme.colorScheme.error
                            missed > 0 -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        },
                        label = "triggerColor"
                    )
                    Text(
                        stringResource(R.string.calibrate_trigger, new.gForceThreshold),
                        style = MaterialTheme.typography.titleSmall,
                        color = triggerColor
                    )
                }
                Spacer(Modifier.height(20.dp))
                SensitivitySlider(result, chosenG, onChoose)
                Spacer(Modifier.height(12.dp))
                val messages = buildList {
                    if (accidental != null && accidental > 0) {
                        add(pluralStringResource(R.plurals.calibrate_too_sensitive, accidental, accidental, EVERYDAY_SECONDS) to MaterialTheme.colorScheme.error)
                    }
                    if (missed > 0) {
                        add(pluralStringResource(R.plurals.calibrate_too_strict, missed, missed) to MaterialTheme.colorScheme.tertiary)
                    }
                    if (isEmpty()) {
                        val safe = result.safeRange
                        val text = when {
                            safe == null -> stringResource(R.string.calibrate_no_safe)
                            accidental == null -> stringResource(R.string.calibrate_safe_unchecked)
                            else -> stringResource(R.string.calibrate_safe_range, safe.start, safe.endInclusive)
                        }
                        add(text to MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                messages.forEach { (text, color) ->
                    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
                }
            }
        }
        if (result.safeRange == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.calibrate_no_safe),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.calibrate_learned), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val newAxes = axesLabel(new.chopAxes)
            val oldAxes = axesLabel(old.chopAxes)
            ChangeRow(
                stringResource(R.string.calibrate_row_direction),
                oldValue = null,
                newValue = newAxes,
                description = listOfNotNull(
                    axesDescription(new.chopAxes),
                    if (oldAxes != newAxes) stringResource(R.string.calibrate_was, oldAxes.lowercase()) else null
                ).joinToString(". ")
            )
            ChangeRow(
                stringResource(R.string.calibrate_row_snap),
                stringResource(R.string.calibrate_ms, old.reversalWindowMs),
                stringResource(R.string.calibrate_ms, new.reversalWindowMs),
                stringResource(R.string.calibrate_row_snap_desc)
            )
            ChangeRow(
                stringResource(R.string.calibrate_row_clean),
                stringResource(R.string.calibrate_percent, (old.dominanceRatio * 100).roundToInt()),
                stringResource(R.string.calibrate_percent, (new.dominanceRatio * 100).roundToInt()),
                stringResource(R.string.calibrate_row_clean_desc)
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

private enum class StatWarning { MISSED, ACCIDENTAL }

@Composable
private fun StatCard(value: String, label: String, warning: StatWarning?, modifier: Modifier = Modifier) {
    val container by animateColorAsState(
        when (warning) {
            StatWarning.ACCIDENTAL -> MaterialTheme.colorScheme.errorContainer
            StatWarning.MISSED -> MaterialTheme.colorScheme.tertiaryContainer
            null -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = tween(250),
        label = "statContainer"
    )
    val content = when (warning) {
        StatWarning.ACCIDENTAL -> MaterialTheme.colorScheme.onErrorContainer
        StatWarning.MISSED -> MaterialTheme.colorScheme.onTertiaryContainer
        null -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .padding(16.dp)
    ) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = content)
        Text(label, style = MaterialTheme.typography.bodySmall, color = content.copy(alpha = 0.8f))
    }
}

/**
 * A 1–5 g slider whose track is coloured by what each threshold would do on the recordings:
 * accidents (red), safe (green) or missed chop-chops (amber). Snaps to 0.1 g.
 */
@Composable
private fun SensitivitySlider(result: Calibration.Result, chosenG: Float, onChoose: (Float) -> Unit) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    val current by rememberUpdatedState(chosenG)
    val onChange by rememberUpdatedState(onChoose)
    val position = remember { Animatable(chosenG) }
    LaunchedEffect(chosenG) {
        if (position.targetValue != chosenG) position.animateTo(chosenG, spring(stiffness = Spring.StiffnessMediumLow))
    }

    val safeColor = MaterialTheme.colorScheme.primaryContainer
    val accidentColor = MaterialTheme.colorScheme.errorContainer
    val missColor = MaterialTheme.colorScheme.tertiaryContainer
    val outline = MaterialTheme.colorScheme.primary
    val thumb = MaterialTheme.colorScheme.primary
    val thumbBorder = MaterialTheme.colorScheme.surface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val recommendedLabel = stringResource(R.string.calibrate_recommended)
    val moreSensitive = stringResource(R.string.calibrate_more_sensitive)
    val fewerAccidents = stringResource(R.string.calibrate_fewer_accidents)
    val unit = stringResource(R.string.calibrate_g_unit)
    val small = TextStyle(fontSize = 11.sp, color = muted)
    val description = stringResource(R.string.calibrate_trigger, chosenG)

    val pad = 14.dp
    fun valueAt(x: Float, width: Float, padPx: Float): Float {
        val f = ((x - padPx) / (width - 2 * padPx)).coerceIn(0f, 1f)
        val g = Calibration.MIN_THRESHOLD_G + f * (Calibration.MAX_THRESHOLD_G - Calibration.MIN_THRESHOLD_G)
        return Calibration.thresholds[result.indexOf(g)]
    }
    fun choose(g: Float) {
        if (g != current) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onChange(g)
        }
    }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(92.dp)
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(
                    chosenG, Calibration.MIN_THRESHOLD_G..Calibration.MAX_THRESHOLD_G, steps = 39
                )
                setProgress { g ->
                    choose(Calibration.thresholds[result.indexOf(g)])
                    true
                }
            }
            .pointerInput(result) {
                detectTapGestures { offset ->
                    choose(valueAt(offset.x, size.width.toFloat(), pad.toPx()))
                }
            }
            .pointerInput(result) {
                detectHorizontalDragGestures { change, _ ->
                    val g = valueAt(change.position.x, size.width.toFloat(), pad.toPx())
                    scope.launch { position.snapTo(g) }
                    choose(g)
                }
            }
    ) {
        val padPx = pad.toPx()
        val left = padPx
        val right = size.width - padPx
        val span = right - left
        fun x(g: Float) = left + span * (g - Calibration.MIN_THRESHOLD_G) /
            (Calibration.MAX_THRESHOLD_G - Calibration.MIN_THRESHOLD_G)

        val trackTop = 22.dp.toPx()
        val trackH = 10.dp.toPx()
        val step = span / (Calibration.thresholds.size - 1)

        // Coloured zones, one slice per 0.1 g, clipped to a rounded track.
        clipPath(Path().apply {
            addRoundRect(RoundRect(left - step / 2, trackTop, right + step / 2, trackTop + trackH, CornerRadius(trackH / 2)))
        }) {
            Calibration.thresholds.forEachIndexed { i, g ->
                val acc = result.accidental?.get(i) ?: 0
                val color = when {
                    acc > 0 -> accidentColor
                    result.caught[i] < result.gestureCount -> missColor
                    else -> safeColor
                }
                drawRect(color, Offset(x(g) - step / 2, trackTop), Size(step + 1f, trackH))
            }
        }
        result.safeRange?.let { safe ->
            drawRoundRect(
                outline, Offset(x(safe.start) - step / 2, trackTop),
                Size(x(safe.endInclusive) - x(safe.start) + step, trackH),
                style = Stroke(1.2.dp.toPx())
            )
        }

        // Recommended marker.
        val rx = x(result.recommendedG)
        drawLine(
            outline, Offset(rx, trackTop - 6.dp.toPx()), Offset(rx, trackTop + trackH + 6.dp.toPx()),
            strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
        )
        val rec = measurer.measure(recommendedLabel, small.copy(color = outline))
        drawText(
            rec,
            topLeft = Offset((rx - rec.size.width / 2f).coerceIn(0f, size.width - rec.size.width), 0f)
        )

        // Thumb.
        val tx = x(position.value)
        drawCircle(thumbBorder, radius = 13.dp.toPx(), center = Offset(tx, trackTop + trackH / 2))
        drawCircle(thumb, radius = 10.dp.toPx(), center = Offset(tx, trackTop + trackH / 2))

        // End labels and scale.
        val labelsTop = trackTop + trackH + 14.dp.toPx()
        drawText(measurer, moreSensitive, Offset(left - padPx / 2, labelsTop), small)
        val fewer = measurer.measure(fewerAccidents, small)
        drawText(fewer, topLeft = Offset(right + padPx / 2 - fewer.size.width, labelsTop))
        val scaleTop = labelsTop + 20.dp.toPx()
        for (g in 1..5) {
            val label = measurer.measure("$g $unit", small)
            drawText(label, topLeft = Offset(x(g.toFloat()) - label.size.width / 2f, scaleTop))
        }
    }
}

@Composable
private fun axesLabel(mask: Int): String {
    val names = listOfNotNull(
        if (mask and FlashlightConfig.AXIS_Y != 0) stringResource(R.string.calibrate_axis_y) else null,
        if (mask and FlashlightConfig.AXIS_X != 0) stringResource(R.string.calibrate_axis_x) else null,
        if (mask and FlashlightConfig.AXIS_Z != 0) stringResource(R.string.calibrate_axis_z) else null
    )
    return when (names.size) {
        3 -> stringResource(R.string.calibrate_axis_any)
        2 -> stringResource(R.string.calibrate_axis_two, names[0], names[1].lowercase())
        else -> names.firstOrNull() ?: stringResource(R.string.calibrate_axis_any)
    }
}

@Composable
private fun axesDescription(mask: Int): String? = when (mask) {
    FlashlightConfig.AXIS_X -> stringResource(R.string.calibrate_axis_x_desc)
    FlashlightConfig.AXIS_Y -> stringResource(R.string.calibrate_axis_y_desc)
    FlashlightConfig.AXIS_Z -> stringResource(R.string.calibrate_axis_z_desc)
    else -> null
}

@Composable
private fun ChangeRow(label: String, oldValue: String?, newValue: String, description: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (!description.isNullOrEmpty()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (oldValue != null && oldValue != newValue) {
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
