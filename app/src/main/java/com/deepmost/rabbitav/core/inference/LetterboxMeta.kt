package com.deepmost.rabbitav.core.inference

/**
 * Geometry of the letterbox mapping between the upright analysis frame
 * (srcW x srcH) and the model input (dstW x dstH). Aspect is preserved; the
 * scaled content is centered and the remainder padded gray.
 */
class LetterboxMeta {
    var srcW = 0; private set
    var srcH = 0; private set
    var dstW = 0; private set
    var dstH = 0; private set
    var scale = 1f; private set
    var contentW = 0; private set
    var contentH = 0; private set
    var padX = 0; private set
    var padY = 0; private set

    fun configure(srcW: Int, srcH: Int, dstW: Int, dstH: Int) {
        this.srcW = srcW; this.srcH = srcH; this.dstW = dstW; this.dstH = dstH
        scale = minOf(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
        contentW = (srcW * scale).toInt().coerceAtLeast(2) and 0x7FFFFFFE // even for I420 chroma
        contentH = (srcH * scale).toInt().coerceAtLeast(2) and 0x7FFFFFFE
        padX = (dstW - contentW) / 2
        padY = (dstH - contentH) / 2
    }

    /** Model-input pixel x -> normalized upright-frame x in [0,1]. */
    fun unmapX(xPx: Float): Float = ((xPx - padX) / contentW).coerceIn(0f, 1f)

    /** Model-input pixel y -> normalized upright-frame y in [0,1]. */
    fun unmapY(yPx: Float): Float = ((yPx - padY) / contentH).coerceIn(0f, 1f)

    /** Model-input pixel width -> normalized upright width. */
    fun unmapW(wPx: Float): Float = (wPx / contentW).coerceIn(0f, 1f)

    fun unmapH(hPx: Float): Float = (hPx / contentH).coerceIn(0f, 1f)
}
