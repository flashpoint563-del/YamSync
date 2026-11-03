fun Modifier.miniPlayerGestures(
    onExpand: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit
): Modifier = this.pointerInput(Unit) {
    detectVerticalDragGestures(
        onVerticalDrag = { change, dragAmount ->
            onDrag(dragAmount)
            change.consume()
        },
        onDragEnd = { onDragEnd() },
        onDragCancel = { onDragEnd() }
    )
}.clickable { onExpand() }
