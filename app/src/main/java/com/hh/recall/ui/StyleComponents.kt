package com.hh.recall.ui

import android.animation.ValueAnimator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Native adaptations of the three user-selected Uiverse components; see THIRD_PARTY.md.
@Composable
fun ThemeToggle(dark: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val wide = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } >= 600.dp
    val travel by animateFloatAsState(if (dark) 1f else 0f,
        spring(dampingRatio = .72f, stiffness = Spring.StiffnessMediumLow), label = "themeSlide")
    val night by animateFloatAsState(if (dark) 1f else 0f, tween(250), label = "themeIcon")
    val slot by animateColorAsState(if (dark) Color(0xFF374151) else Color.White, tween(250), label = "themeSlot")
    val knob by animateColorAsState(if (dark) Color(0xFF485367) else Color(0xFFFFECCF), tween(250), label = "themeKnob")
    val accent by animateColorAsState(if (dark) Color(0xFFF4F5F7) else Color(0xFFFFBB52), tween(250), label = "themeAccent")
    Box(Modifier.size(width = if (wide) 84.dp else 76.dp, height = 48.dp)
        .testTag("themeToggle").semantics {
            contentDescription = "深色模式"
            stateDescription = if (dark) "已开启" else "已关闭"
        }.toggleable(dark, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(width = if (wide) 72.dp else 64.dp, height = if (wide) 36.dp else 32.dp)) {
            val h = size.height
            val radius = h * .425f
            val center = Offset(h / 2 + (size.width - h) * travel, h / 2)
            drawRoundRect(slot, cornerRadius = CornerRadius(h / 2))
            drawCircle(knob, radius, center)
            drawCircle(accent, radius * .72f, center, style = Stroke(h * .085f))
            // Sun rays fade as a crescent is revealed inside the sliding knob.
            for (i in 0 until 8) {
                val angle = i * PI / 4 + night * PI / 4
                val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                drawLine(accent.copy(alpha = 1f - night), center + direction * radius * .85f,
                    center + direction * radius * 1.04f, h * .045f)
            }
            drawCircle(knob.copy(alpha = night), radius * .69f,
                center + Offset(radius * .32f, -radius * .26f))
        }
    }
}

@Composable
fun LibrarySearch(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focus = LocalFocusManager.current
    val corner by animateDpAsState(if (focused) 6.dp else 30.dp, tween(400), label = "searchCorner")
    val underline by animateFloatAsState(if (focused) 1f else 0f, tween(300), label = "searchUnderline")
    val colors = MaterialTheme.colorScheme
    val wide = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } >= 600.dp
    BasicTextField(value = value, onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().testTag("search").semantics { contentDescription = "搜索学习资料" },
        singleLine = true, interactionSource = interaction,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        decorationBox = { inner ->
            Row(Modifier.clip(RoundedCornerShape(corner)).background(colors.surface)
                .drawWithContent {
                    drawContent()
                    val half = size.width * underline / 2
                    drawLine(colors.primary, Offset(size.width / 2 - half, size.height - 1.dp.toPx()),
                        Offset(size.width / 2 + half, size.height - 1.dp.toPx()), 2.dp.toPx())
                }.defaultMinSize(minHeight = if (wide) 60.dp else 56.dp)
                .padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, null, Modifier.size(22.dp), tint = colors.onSurfaceVariant)
                Box(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 14.dp)) {
                    if (value.isEmpty()) Text("搜索学习资料", color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
                if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }, modifier = Modifier.testTag("clearSearch")) {
                    Icon(Icons.Outlined.Close, "清除搜索", tint = colors.onSurfaceVariant)
                } else Spacer(Modifier.width(8.dp))
            }
        })
}

@Composable
fun ImportLoadingPane(status: String, cancelling: Boolean, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val animate = !cancelling && lifecycle.isAtLeast(Lifecycle.State.RESUMED) && ValueAnimator.areAnimatorsEnabled()
    val phase: State<Float> = if (animate) {
        val transition = rememberInfiniteTransition(label = "importJump")
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(650, easing = LinearEasing)), label = "squareJump")
    } else remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(modifier.fillMaxSize().testTag("importLoadingPane"), contentAlignment = Alignment.Center) {
        val compact = maxHeight < 400.dp
        val square = if (maxWidth >= 600.dp && !compact) 52.dp else 42.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(square * 2.4f, square * 2.4f).testTag("importLoader")
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) {
                val side = square.toPx()
                // Read animation state only during drawing, not on every label/layout composition.
                val step = phase.value
                val bounce = 1f - kotlin.math.abs(step * 2 - 1)
                val center = Offset(size.width / 2, side * .65f + side * .375f * bounce)
                val shadowWidth = side * (1f + .2f * bounce)
                drawOval(colors.primary.copy(alpha = .18f),
                    Offset((size.width - shadowWidth) / 2, side * 1.9f), Size(shadowWidth, side * .105f))
                rotate(step * 90, center) {
                    scale(1f, 1f - .1f * bounce, center) {
                        val radius = side * .085f
                        val path = Path().apply { addRoundRect(RoundRect(
                            left = center.x - side / 2, top = center.y - side / 2,
                            right = center.x + side / 2, bottom = center.y + side / 2,
                            topLeftCornerRadius = CornerRadius(radius), topRightCornerRadius = CornerRadius(radius),
                            bottomLeftCornerRadius = CornerRadius(radius),
                            bottomRightCornerRadius = CornerRadius(radius + side * .55f * bounce))) }
                        drawPath(path, colors.primary)
                    }
                }
            }
            Spacer(Modifier.height(if (compact) 4.dp else 16.dp))
            Text(status, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 480.dp).testTag("importHint").semantics { liveRegion = LiveRegionMode.Polite })
            Spacer(Modifier.height(8.dp))
            Text(if (cancelling) "当前步骤结束后退出" else "全程在本机处理", style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(if (compact) 12.dp else 24.dp))
            OutlinedButton(onClick = onCancel, enabled = !cancelling, modifier = Modifier.testTag("cancelImport")) {
                Text(if (cancelling) "正在取消…" else "取消读取")
            }
        }
    }
}

