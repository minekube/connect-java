package com.minekube.connect.share.fabric.ui

data class ListPage<T>(
    val items: List<T>,
    val offset: Int,
    val previousOffset: Int?,
    val nextOffset: Int?,
    val pageNumber: Int,
    val pageCount: Int,
) {
    val hasPrevious: Boolean = previousOffset != null
    val hasNext: Boolean = nextOffset != null
}

fun <T> List<T>.page(
    offset: Int,
    size: Int,
): ListPage<T> {
    require(size > 0) { "Page size must be positive" }
    val pageCount = ((this.size + size - 1) / size).coerceAtLeast(1)
    val requestedPage = offset.coerceAtLeast(0) / size
    val pageIndex = requestedPage.coerceAtMost(pageCount - 1)
    val normalizedOffset = pageIndex * size
    val items = drop(normalizedOffset).take(size)
    return ListPage(
        items = items,
        offset = normalizedOffset,
        previousOffset = normalizedOffset.takeIf { it > 0 }
            ?.minus(size)
            ?.coerceAtLeast(0),
        nextOffset = (normalizedOffset + size).takeIf { it < this.size },
        pageNumber = pageIndex + 1,
        pageCount = pageCount,
    )
}
