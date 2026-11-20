package vn.anio.signage

import android.content.Context
import android.view.View
import android.widget.FrameLayout

/**
 * Nhiều TV box không có cảm biến xoay: nếu hệ thống không xoay màn hình theo yêu cầu,
 * xoay view gốc 90 độ và hoán đổi kích thước để nội dung vẫn đúng hướng.
 */
class RotateLayout(ctx: Context) : FrameLayout(ctx) {
    var wantLandscape = false
        set(v) {
            field = v
            requestLayout()
        }

    private fun mismatch(w: Int, h: Int): Boolean = (w > h) != wantLandscape

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val c = getChildAt(0) ?: return
        if (mismatch(w, h)) {
            c.measure(MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY))
        } else {
            c.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val c: View = getChildAt(0) ?: return
        val w = r - l
        val h = b - t
        if (mismatch(w, h)) {
            c.layout(0, 0, h, w)
            c.pivotX = 0f
            c.pivotY = 0f
            c.rotation = 90f
            c.translationX = w.toFloat()
            c.translationY = 0f
        } else {
            c.layout(0, 0, w, h)
            c.rotation = 0f
            c.translationX = 0f
            c.translationY = 0f
        }
    }
}
