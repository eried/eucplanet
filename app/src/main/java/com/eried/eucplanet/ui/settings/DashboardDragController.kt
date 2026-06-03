package com.eried.eucplanet.ui.settings

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import com.eried.eucplanet.ui.theme.appColors
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * Drag-and-drop coordinator for the dashboard layout editor. Owns the active-
 * drag state (which item, source kind, current pointer position) and the
 * registered drop targets. Backed entirely by Compose pointer input so we can
 * animate the floating preview's size / alpha during the drag — something
 * Compose's built-in `dragAndDropSource` (which uses the View-level system
 * drag shadow) doesn't allow.
 *
 * Drag flow:
 * 1. Tile or pool pill calls [startDrag] from `detectDragGesturesAfterLongPress`.
 * 2. Each subsequent drag delta is applied via [movePointer].
 * 3. While dragging, [hoveredTarget] re-derives from pointer + target bounds.
 * 4. On finger lift, [commitDrop] invokes the hovered target's `onDrop` if any.
 *
 * Only grid slots register as drop targets; the pool stays a source-only
 * region so dragging outside the active grid reads as "no valid drop".
 */
@Stable
class DashboardDragController {

    var draggingKey: String? by mutableStateOf(null)
        private set
    var draggingValue: String by mutableStateOf("")
        private set
    var sourceKind: DragSourceKind? by mutableStateOf(null)
        private set
    var sourceSizePx: IntSize by mutableStateOf(IntSize.Zero)
        private set
    var pointerWindowPos: Offset? by mutableStateOf(null)
        private set
    /**
     * True when the rider started this drag from a big tile in the active
     * grid; false when it started from a pool pill (catalog source) or a
     * pool template. Drop handlers read this to decide between SWAP
     * (grid → grid), COPY (pool → grid, pool stays full), and discard
     * (grid → pool).
     */
    var sourceFromGrid: Boolean by mutableStateOf(false)
        private set

    /**
     * Latched preview size for the floating drag overlay. Updates only when
     * the pointer enters a registered drop target or hint region — never on
     * pointer-exit. This implements the "once it grew, keep it grown until I
     * drag it back to the pool" behaviour: the preview holds its last
     * recognised size as the rider sweeps through empty space, and only
     * shrinks when they re-enter the pool's hint region.
     */
    var previewSizePx: IntSize by mutableStateOf(IntSize.Zero)
        private set

    private val targets = mutableStateMapOf<String, DropTargetInfo>()
    private val hintRegions = mutableStateMapOf<String, HintRegionInfo>()

    /** Target the pointer is currently over, or null if outside every drop area. */
    val hoveredTarget: DropTargetInfo? by derivedStateOf {
        val pos = pointerWindowPos ?: return@derivedStateOf null
        val source = sourceKind ?: return@derivedStateOf null
        val key = draggingKey ?: return@derivedStateOf null
        targets.values.firstOrNull { it.accepts(source, key) && it.bounds.contains(pos) }
    }

    val isDragging: Boolean
        get() = draggingKey != null

    fun startDrag(
        key: String,
        value: String,
        sourceKind: DragSourceKind,
        sourceSize: IntSize,
        pointerWindowPos: Offset,
        sourceFromGrid: Boolean
    ) {
        this.draggingKey = key
        this.draggingValue = value
        this.sourceKind = sourceKind
        this.sourceSizePx = sourceSize
        this.pointerWindowPos = pointerWindowPos
        this.previewSizePx = sourceSize
        this.sourceFromGrid = sourceFromGrid
    }

    fun movePointer(delta: Offset) {
        val current = pointerWindowPos ?: return
        val source = sourceKind ?: return
        val key = draggingKey ?: return
        val newPos = current + delta
        pointerWindowPos = newPos

        // Latch preview size to whatever region the pointer just entered.
        // Drop targets win over hint regions when both contain the pointer.
        val hit = targets.values.firstOrNull { it.accepts(source, key) && it.bounds.contains(newPos) }
            ?.expectedSizePx
            ?: hintRegions.values.firstOrNull { it.accepts(source) && it.bounds.contains(newPos) }
                ?.expectedSizePx
        if (hit != null) previewSizePx = hit
    }

    fun commitDrop() {
        val key = draggingKey
        val target = hoveredTarget
        if (key != null && target != null) {
            target.onDrop(key)
        }
        resetDrag()
    }

    fun cancelDrag() {
        resetDrag()
    }

    private fun resetDrag() {
        draggingKey = null
        sourceKind = null
        pointerWindowPos = null
    }

    fun registerTarget(info: DropTargetInfo) {
        targets[info.key] = info
    }

    fun unregisterTarget(key: String) {
        targets.remove(key)
    }

    fun registerHintRegion(info: HintRegionInfo) {
        hintRegions[info.key] = info
    }
}

enum class DragSourceKind { METRIC, ACTION }

enum class DropKind {
    METRIC_GRID_SLOT,
    ACTION_GRID_SLOT,
    /** Pool acts as a "drag-off-to-delete" trash for composite/group instances. */
    METRIC_POOL_TRASH,
    ACTION_POOL_TRASH
}

data class DropTargetInfo(
    val key: String,
    val kind: DropKind,
    val bounds: Rect,
    /** Size the floating preview should animate to while hovering this target. */
    val expectedSizePx: IntSize,
    /** Index within the active grid (0..N-1) for grid-slot targets; -1 otherwise. */
    val slotIndex: Int,
    val onDrop: (sourceKey: String) -> Unit,
    /**
     * Optional per-source-key acceptance check. When set, the target only
     * highlights and accepts drops for keys that pass this predicate. Used
     * by the pool trash targets, which exist exclusively to delete
     * composite/group instances (and shouldn't react to regular pool pills
     * or template keys being dragged back over them).
     */
    val acceptsSourceKey: ((String) -> Boolean)? = null
) {
    fun accepts(source: DragSourceKind, sourceKey: String): Boolean {
        val kindAllows = when (source) {
            DragSourceKind.METRIC -> kind == DropKind.METRIC_GRID_SLOT || kind == DropKind.METRIC_POOL_TRASH
            DragSourceKind.ACTION -> kind == DropKind.ACTION_GRID_SLOT || kind == DropKind.ACTION_POOL_TRASH
        }
        return kindAllows && (acceptsSourceKey?.invoke(sourceKey) ?: true)
    }
}

/**
 * A non-drop region whose only purpose is to influence the preview size.
 * The pool registers as a hint region so the dragged tile shrinks back to
 * its source size when the rider drags it back over the pool, but a drop
 * over the pool is still ignored (only drop targets accept drops).
 */
data class HintRegionInfo(
    val key: String,
    val sourceKind: DragSourceKind,
    val bounds: Rect,
    val expectedSizePx: IntSize
) {
    fun accepts(source: DragSourceKind): Boolean = source == sourceKind
}

/**
 * Attaches drag-source behaviour to a tile/pill: long-press starts a drag,
 * subsequent moves update the controller's pointer position, lift commits a
 * drop. Tap events fall through to a sibling `.clickable(...)` modifier
 * because `detectDragGesturesAfterLongPress` only consumes events once the
 * long-press timeout fires.
 */
fun Modifier.dashboardDragSource(
    key: String,
    value: String,
    sourceKind: DragSourceKind,
    controller: DashboardDragController,
    /**
     * True when this drag-source is a big tile in the active grid; false
     * for pool pills and templates. Controls SWAP vs COPY semantics in
     * the drop handlers: grid sources reorder, pool sources spawn into
     * the target slot leaving the catalog untouched.
     */
    fromGrid: Boolean
): Modifier = composed {
    var sourceWindowPos by remember { mutableStateOf(Offset.Zero) }
    var sourceSize by remember { mutableStateOf(IntSize.Zero) }
    this
        .onGloballyPositioned { coords ->
            sourceWindowPos = coords.positionInWindow()
            sourceSize = coords.size
        }
        .pointerInput(key, sourceKind) {
            detectDragGesturesAfterLongPress(
                onDragStart = { localOffset ->
                    controller.startDrag(
                        key = key,
                        value = value,
                        sourceKind = sourceKind,
                        sourceSize = sourceSize,
                        pointerWindowPos = sourceWindowPos + localOffset,
                        sourceFromGrid = fromGrid
                    )
                },
                onDrag = { change, dragAmount ->
                    controller.movePointer(dragAmount)
                    change.consume()
                },
                onDragEnd = { controller.commitDrop() },
                onDragCancel = { controller.cancelDrag() }
            )
        }
}

/**
 * Attaches drop-target behaviour. The target's bounds are tracked via
 * `onGloballyPositioned` so the controller can hit-test the pointer at any
 * moment. [expectedSizePx] is the size the floating preview animates to when
 * hovering this target — pass the slot's measured size so a pool pill that's
 * being promoted to the grid visibly grows.
 */
fun Modifier.dashboardDropTarget(
    key: String,
    kind: DropKind,
    slotIndex: Int = -1,
    controller: DashboardDragController,
    onDrop: (sourceKey: String) -> Unit,
    acceptsSourceKey: ((String) -> Boolean)? = null,
    /**
     * If non-null, the drag preview shrinks/grows to this size while hovering
     * the target instead of matching the target's own measured size. Pool
     * trash targets pass `poolPillSizePx` so a composite tile dragged over
     * them previews at pill size — signalling "release = delete (back to
     * pool)" rather than "release = land here at full grid size".
     */
    overrideExpectedSizePx: IntSize? = null
): Modifier = composed {
    onGloballyPositioned { coords ->
        controller.registerTarget(
            DropTargetInfo(
                key = key,
                kind = kind,
                bounds = coords.boundsInWindow(),
                expectedSizePx = overrideExpectedSizePx ?: coords.size,
                slotIndex = slotIndex,
                onDrop = onDrop,
                acceptsSourceKey = acceptsSourceKey
            )
        )
    }
}

/**
 * Registers a hint region that influences only the floating preview size,
 * not drop acceptance. Use on the pool container so a pool pill that's been
 * dragged up into the grid (and grown) shrinks back to its source size when
 * the rider drags it over the pool again.
 */
fun Modifier.dashboardHintRegion(
    key: String,
    sourceKind: DragSourceKind,
    expectedSizePx: IntSize,
    controller: DashboardDragController
): Modifier = composed {
    onGloballyPositioned { coords ->
        controller.registerHintRegion(
            HintRegionInfo(
                key = key,
                sourceKind = sourceKind,
                bounds = coords.boundsInWindow(),
                expectedSizePx = expectedSizePx
            )
        )
    }
}

/**
 * Floating drag preview. Rendered as a plain `Box` positioned via
 * `Modifier.offset` so it stays in the same composition tree as the editor
 * and does not consume touch events that the underlying tile's `pointerInput`
 * still needs. (Compose's `Popup` allocates a separate WindowManager window
 * with `FLAG_NOT_TOUCH_MODAL`; touches inside that window's bounds are
 * captured by it rather than passing through to the editor, which freezes
 * the drag the moment the preview overlays the rider's finger.)
 *
 * Size animates between source size and the hovered target's expected size,
 * so a pool pill grows on its way into the grid and a grid tile shrinks on
 * its way out. Alpha animates to 0.4 when no valid drop target is under the
 * pointer, signalling "release here = no-op".
 *
 * @param rootInWindow the editor root's window-coordinate offset, captured
 *   by the caller via `onGloballyPositioned`. Used to convert the
 *   controller's window-space pointer into local coordinates so the offset
 *   modifier places the preview correctly within the editor's bounds.
 */
@Composable
fun DashboardDragPreviewOverlay(
    controller: DashboardDragController,
    rootInWindow: Offset,
    renderMetric: @Composable (key: String, value: String) -> Unit,
    renderAction: @Composable (key: String) -> Unit
) {
    val key = controller.draggingKey ?: return
    val pos = controller.pointerWindowPos ?: return
    val source = controller.sourceKind ?: return
    val target = controller.hoveredTarget

    // Only flag as a discard when the tile actually shrinks into the
    // pool — i.e. the drag started on a big grid tile. A pool pill
    // dragged up and back down again returns home (no slot is lost, no
    // definition gets deleted), so the red wash would be misleading.
    // Compare source size to the target's expected size: shrink == big
    // origin == will be removed; same size == pool-to-pool == no-op.
    val isPoolTrash = target?.kind == DropKind.METRIC_POOL_TRASH ||
        target?.kind == DropKind.ACTION_POOL_TRASH
    val isDiscardDrop = isPoolTrash &&
        controller.sourceSizePx.height > (target?.expectedSizePx?.height ?: Int.MAX_VALUE)

    val targetSize = controller.previewSizePx.takeIf { it.width > 0 && it.height > 0 }
        ?: controller.sourceSizePx
    val animWidth by animateFloatAsState(
        targetValue = targetSize.width.toFloat(),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "drag-w"
    )
    val animHeight by animateFloatAsState(
        targetValue = targetSize.height.toFloat(),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "drag-h"
    )
    val animAlpha by animateFloatAsState(
        targetValue = when {
            isDiscardDrop -> 0.55f
            target != null -> 1f
            else -> 0.4f
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "drag-alpha"
    )
    val redOverlayAlpha by animateFloatAsState(
        targetValue = if (isDiscardDrop) 0.22f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "drag-red"
    )

    val density = LocalDensity.current
    val widthDp = with(density) { animWidth.toDp() }
    val heightDp = with(density) { animHeight.toDp() }

    val localPos = pos - rootInWindow

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    x = (localPos.x - animWidth / 2f).toInt(),
                    y = (localPos.y - animHeight / 2f).toInt()
                )
            }
            .size(widthDp, heightDp)
            .alpha(animAlpha)
    ) {
        when (source) {
            DragSourceKind.METRIC -> renderMetric(key, controller.draggingValue)
            DragSourceKind.ACTION -> renderAction(key)
        }
        // Translucent red wash sits on top of the rendered tile, clipped
        // to the same rounded corners so it reads as part of the tile
        // rather than a stray overlay. Animated alpha keeps the fade
        // smooth as the rider passes in and out of the trash region.
        if (redOverlayAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.appColors.statusDanger.copy(alpha = redOverlayAlpha))
            )
        }
    }
}
