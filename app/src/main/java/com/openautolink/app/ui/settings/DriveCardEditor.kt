package com.openautolink.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.openautolink.app.drivecard.DriveCardLayout
import com.openautolink.app.drivecard.DriveCardPreview
import com.openautolink.app.secondary.SecondaryDisplayDescriptor
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.roundToInt

/** Main-screen editor. The diagram is deliberately not labelled live. Physical preview borrows
 * only an exact already-admitted card; no projection/capture/navigation/window acquisition. */
@Composable
internal fun DriveCardEditor(display:SecondaryDisplayDescriptor, live:SecondaryDisplayDescriptor?,
    saved:DriveCardLayout?, enabled:Boolean, onStage:(DriveCardLayout?)->Unit,onBack:()->Unit) {
    var draft by remember(display) {mutableStateOf(saved ?: DriveCardLayout.DEFAULT)}
    var custom by remember(display) {mutableStateOf(saved!=null)}
    var lease by remember(display) {mutableStateOf<DriveCardPreview.Lease?>(null)}
    var message by remember(display) {mutableStateOf<String?>(null)}
    val editable=enabled && live==display
    val currentStage by rememberUpdatedState(onStage)
    val stop:()->Unit={lease?.stop();lease=null}
    // Capture exact lease, never the latest registry owner or a mutable id-based stop callback.
    val ownedLease=lease
    DisposableEffect(ownedLease) {onDispose {ownedLease?.stop()}}
    LaunchedEffect(live,enabled) {if(!editable) {stop();message="Display changed. Reopen this editor to continue."}}
    LaunchedEffect(ownedLease) {
        if(ownedLease!=null) while(true) {
            delay(100)
            if(!ownedLease.isActive) {
                if(lease===ownedLease) {lease=null;message="Preview stopped."}
                break
            }
        }
    }
    fun change(value:DriveCardLayout, explicit:Boolean=true) {
        if(!editable)return
        draft=value;custom=explicit;currentStage(if(explicit)value else null)
        lease?.let {if(!it.update(value)) {it.stop();lease=null;message="Preview stopped."}}
    }
    val latestChange by rememberUpdatedState<(DriveCardLayout)->Unit>({change(it)})
    fun done() {stop();onBack()}
    BackHandler {done()}
    Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)
            .testTag("driveCardEditor")) {
            val wide=maxWidth>=760.dp
            val availableWidth=maxWidth
            val availableHeight=maxHeight
            val controls:@Composable ()->Unit={
                Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                  Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Adjust Drive Card",style=MaterialTheme.typography.headlineSmall)
                    Text("Android Auto and CarPlay",style=MaterialTheme.typography.titleSmall)
                    Text("Move the card. Drag its corner to resize. Keep vehicle gauges and warnings visible.",style=MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick={
                        if(lease!=null) {stop();message="Preview stopped."}
                        else {
                            val next=DriveCardPreview.start(display)
                            if(next==null || !next.update(draft)) {
                                next?.stop()
                                message="Live preview needs an active driver-display video window with Drive Card already on. Save and reconnect first if needed."
                            } else {lease=next;message=null}
                        }
                    },enabled=editable,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("driveCardShow")) {
                        Text(if(lease!=null)"Stop preview" else "Show on driver display · 30s")
                    }
                    Text(if(lease!=null)"Live preview · stops automatically after 30 seconds. Edits do not extend it."
                        else "Schematic only until live preview starts.",style=MaterialTheme.typography.bodyMedium,
                        modifier=Modifier.testTag("driveCardPreviewStatus"))
                    message?.let{Text(it,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.testTag("driveCardPreviewMessage"))}
                    Text("Slant",style=MaterialTheme.typography.labelLarge)
                    Slider(value=draft.slant.toFloat(),onValueChange={change(draft.copy(slant=it.toDouble().coerceIn(0.0,.18)))},valueRange=0f.. .18f,
                        enabled=editable,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).semantics{contentDescription="Drive Card slant"}.testTag("driveCardSlant"))
                    if(!custom)Text("Default position",style=MaterialTheme.typography.labelMedium)
                  }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick={change(DriveCardLayout.DEFAULT,false)},enabled=editable,
                            modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("driveCardReset")) {Text("Reset")}
                        Button(onClick={done()},modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("driveCardDone")) {Text("Done")}
                    }
                    Text("After Done, tap Save changes. Preview does not save or reconnect.",style=MaterialTheme.typography.bodyMedium)
                }
            }
            val diagram:@Composable (Modifier)->Unit={modifier ->
                BoxWithConstraints(modifier,contentAlignment=Alignment.Center) {
                    val w=min(maxWidth.value,maxHeight.value*display.width/display.height).dp
                    val h=(w.value*display.height/display.width).dp
                    val density=LocalDensity.current
                    val pixelsW=with(density){w.toPx()}
                    val pixelsH=with(density){h.toPx()}
                    Box(Modifier.size(w,h).testTag("driveCardCanvas").background(Color(0xff263647))) {
                        Canvas(Modifier.fillMaxSize()) {
                            // Explicit reserved areas, not fabricated GM gauges, map or telemetry.
                            drawRect(Color(0xff0c162d),size=androidx.compose.ui.geometry.Size(size.width*.36f,size.height))
                            drawRect(Color(0xff0c162d),topLeft=Offset(size.width*.94f,0f),size=androidx.compose.ui.geometry.Size(size.width*.06f,size.height))
                            drawRect(Color(0xff0c162d),topLeft=Offset(0f,size.height*.86f),size=androidx.compose.ui.geometry.Size(size.width,size.height*.14f))
                            val b=draft.box
                            val left=(b.left*size.width).toFloat();val top=(b.top*size.height).toFloat()
                            val right=(b.right*size.width).toFloat();val bottom=(b.bottom*size.height).toFloat()
                            val path=Path().apply {moveTo(left+((right-left)*draft.slant).toFloat(),top);lineTo(right,top);lineTo(right,bottom);lineTo(left,bottom);close()}
                            drawPath(path,Color.Black);drawPath(path,Color(0xff7294c2),style=Stroke(2.dp.toPx()))
                        }
                        Text("Schematic · full driver display",color=Color(0xffd7ddeb),style=MaterialTheme.typography.labelSmall,
                            modifier=Modifier.align(Alignment.TopCenter).padding(6.dp))
                        val b=draft.box
                        Box(Modifier.offset {IntOffset((b.left*pixelsW).roundToInt(),(b.top*pixelsH).roundToInt())}
                            .size(w*((b.right-b.left).toFloat()),h*((b.bottom-b.top).toFloat()))
                            .semantics {
                                contentDescription="Move Drive Card"
                                customActions=listOf(
                                    CustomAccessibilityAction("Move left"){latestChange(draft.move(-.01,0.0));true},
                                    CustomAccessibilityAction("Move right"){latestChange(draft.move(.01,0.0));true},
                                    CustomAccessibilityAction("Move up"){latestChange(draft.move(0.0,-.01));true},
                                    CustomAccessibilityAction("Move down"){latestChange(draft.move(0.0,.01));true})
                            }.testTag("driveCardMove")
                            .pointerInput(display,pixelsW,pixelsH,editable) {
                                if(editable)awaitEachGesture {
                                    val first=awaitFirstDown(requireUnconsumed=false)
                                    first.consume()
                                    do {
                                        val event=awaitPointerEvent()
                                        val change=event.changes.firstOrNull{it.id==first.id} ?: break
                                        val delta=change.position-change.previousPosition
                                        if(delta!=Offset.Zero) {
                                            change.consume()
                                            latestChange(draft.move((delta.x/pixelsW).toDouble(),(delta.y/pixelsH).toDouble()))
                                        }
                                    } while(change.pressed)
                                }
                            },contentAlignment=Alignment.Center) {
                            Text("Card",color=Color(0xffd7ddeb),style=MaterialTheme.typography.labelMedium,maxLines=1)
                        }
                        Box(Modifier.offset {IntOffset((b.right*pixelsW).roundToInt()-with(density){24.dp.roundToPx()},(b.bottom*pixelsH).roundToInt()-with(density){24.dp.roundToPx()})}
                            .size(48.dp).testTag("driveCardResize").semantics {
                                contentDescription="Resize Drive Card"
                                customActions=listOf(
                                    CustomAccessibilityAction("Increase width"){latestChange(draft.resize(.01,0.0));true},
                                    CustomAccessibilityAction("Decrease width"){latestChange(draft.resize(-.01,0.0));true},
                                    CustomAccessibilityAction("Increase height"){latestChange(draft.resize(0.0,.01));true},
                                    CustomAccessibilityAction("Decrease height"){latestChange(draft.resize(0.0,-.01));true})
                            }.pointerInput(display,pixelsW,pixelsH,editable) {
                                if(editable)awaitEachGesture {
                                    val first=awaitFirstDown(requireUnconsumed=false)
                                    first.consume()
                                    do {
                                        val event=awaitPointerEvent()
                                        val change=event.changes.firstOrNull{it.id==first.id} ?: break
                                        val delta=change.position-change.previousPosition
                                        if(delta!=Offset.Zero) {
                                            change.consume()
                                            latestChange(draft.resize((delta.x/pixelsW).toDouble(),(delta.y/pixelsH).toDouble()))
                                        }
                                    } while(change.pressed)
                                }
                            },contentAlignment=Alignment.Center) {
                            Canvas(Modifier.size(16.dp)) {drawRect(Color(0xff7294c2))}
                        }
                    }
                }
            }
            if(wide)Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                diagram(Modifier.weight(1f).fillMaxHeight());Box(Modifier.width(280.dp).fillMaxHeight()){controls()}
            } else Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                diagram(Modifier.fillMaxWidth().height((availableHeight*.34f).coerceAtMost(availableWidth*.55f)))
                Box(Modifier.weight(1f)){controls()}
            }
        }
    }
}
