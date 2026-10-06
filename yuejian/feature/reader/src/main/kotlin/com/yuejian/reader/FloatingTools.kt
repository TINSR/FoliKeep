package com.yuejian.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 纸页小幽灵悬浮入口：拖动换位置，点按在入口上方竖排展开三个阅读工具。
 * 点空白处收起；全屏阅读时球与菜单一起朝最近的屏幕边界滑出。
 */
@Composable
fun FloatingTools(
    immersive: Boolean,
    regionActive: Boolean,
    catalogActive: Boolean,
    onRead: () -> Unit,
    onRegion: () -> Unit,
    onCatalog: () -> Unit,
    modifier: Modifier = Modifier,
    initialX: Float = -1f,
    initialY: Float = -1f,
    onMoveEnd: (Float, Float) -> Unit = { _, _ -> }
) {
    val density = LocalDensity.current
    val bubblePx = with(density) { 56.dp.toPx() }
    val menuW = with(density) { 172.dp.toPx() }
    val menuH = with(density) { 168.dp.toPx() }
    var fraction by remember {
        mutableStateOf(if(initialX in 0f..1f && initialY in 0f..1f)Offset(initialX,initialY) else Offset.Unspecified)
    }
    val currentMoveEnd by rememberUpdatedState(onMoveEnd)
    var expanded by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val maxX = (w - bubblePx).coerceAtLeast(0f)
        val maxY = (h - bubblePx).coerceAtLeast(0f)
        val clamped = if(fraction == Offset.Unspecified) {
            Offset((w - bubblePx * 2f).coerceIn(0f,maxX), (h * .38f).coerceIn(0f,maxY))
        } else Offset(fraction.x * maxX, fraction.y * maxY)
        fun savePosition() {
            if(fraction != Offset.Unspecified)currentMoveEnd(fraction.x,fraction.y)
        }
        // 全屏阅读：朝最近的屏幕边界滑出
        val target = if (immersive) {
            val cx = clamped.x + bubblePx / 2
            val cy = clamped.y + bubblePx / 2
            val dl = cx; val dr = w - cx; val dt = cy; val db = h - cy
            val m = minOf(dl, dr, dt, db)
            when (m) {
                dl -> Offset(-bubblePx - 24f, clamped.y)
                dr -> Offset(w + 24f, clamped.y)
                dt -> Offset(clamped.x, -bubblePx - 24f)
                else -> Offset(clamped.x, h + 24f)
            }
        } else clamped
        val anim by animateOffsetAsState(target, label = "floating-tools")

        // 点空白处收起菜单
        if (expanded && !immersive) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(onTap = { expanded = false })
            })
        }

        // 菜单独立定位在球上方（球在顶部附近时翻到下方），不再与球重叠
        val above = anim.y > menuH + 24f
        val menuY = if (above) anim.y - menuH - 12f else anim.y + bubblePx + 12f
        val menuX = (anim.x + bubblePx / 2 - menuW / 2).coerceIn(8f, (w - menuW - 8f).coerceAtLeast(8f))
        AnimatedVisibility(
            visible = expanded && !immersive,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
            modifier = Modifier.offset { IntOffset(menuX.roundToInt(), menuY.roundToInt()) }
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = .92f),
                tonalElevation = 3.dp,
                shadowElevation = 14.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.width(172.dp)
            ) {
                Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ToolItem("阅读 / 长按选字", !regionActive) { expanded = false; onRead() }
                    ToolItem("圈选问 AI", regionActive) { expanded = false; onRegion() }
                    ToolItem("标注与问答", catalogActive) { expanded = false; onCatalog() }
                }
            }
        }

        // 悬浮球
        Box(Modifier.offset { IntOffset(anim.x.roundToInt(), anim.y.roundToInt()) }) {
            Surface(
                shape = RoundedCornerShape(17.dp),
                color = Color.Black,
                tonalElevation = 0.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, Color.White.copy(alpha = .20f)),
                onClick = { expanded = !expanded },
                modifier = Modifier
                    .size(56.dp)
                    .semantics { contentDescription = if (expanded) "收起阅读工具" else "展开阅读工具" }
                    .pointerInput(w,h,bubblePx) {
                        detectDragGestures(
                            onDragStart = { expanded = false },
                            onDragEnd = { savePosition() },
                            onDragCancel = { savePosition() }) { change, drag ->
                            change.consume()
                            val current = if(fraction == Offset.Unspecified)clamped
                                else Offset(fraction.x * maxX, fraction.y * maxY)
                            fraction = Offset(
                                (current.x + drag.x).coerceIn(0f,maxX) / maxX.coerceAtLeast(1f),
                                (current.y + drag.y).coerceIn(0f,maxY) / maxY.coerceAtLeast(1f)
                            )
                        }
                    }
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_paper_ghost),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(6.dp)
                )
            }
        }
    }
}

@Composable
private fun ToolItem(label: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(13.dp),
        color = if (active) MaterialTheme.colorScheme.onSurface.copy(alpha = .9f)
        else MaterialTheme.colorScheme.surface.copy(alpha = .55f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label,
                color = if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        }
    }
}
