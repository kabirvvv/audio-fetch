package com.audiofetch

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class LyricsAdapter : RecyclerView.Adapter<LyricsAdapter.VH>() {

    private var lines: List<String> = emptyList()
    private var currentLine: Int = -1

    inner class VH(val textView: TextView) : RecyclerView.ViewHolder(textView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = TextView(parent.context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val vPad = (10 * parent.context.resources.displayMetrics.density).toInt()
                val hPad = (32 * parent.context.resources.displayMetrics.density).toInt()
                setPadding(hPad, vPad, hPad, vPad)
            }
            gravity = Gravity.CENTER_HORIZONTAL
            textSize = 17f
            setLineSpacing(0f, 1.3f)
        }
        return VH(tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.textView.text = lines.getOrNull(position) ?: ""
        val isCurrent = position == currentLine
        holder.textView.apply {
            if (isCurrent) {
                textSize  = 19f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                alpha = 1f
            } else {
                textSize  = 17f
                setTypeface(null, Typeface.NORMAL)
                setTextColor(Color.WHITE)
                alpha = 0.32f
            }
        }
    }

    override fun getItemCount() = lines.size

    /** Replace all lyrics lines and reset highlight. */
    fun update(newLines: List<String>) {
        lines       = newLines
        currentLine = -1
        notifyDataSetChanged()
    }

    /** Update the highlighted line, animating only the changed rows. */
    fun setCurrentLine(index: Int) {
        val old = currentLine
        currentLine = index
        if (old >= 0) notifyItemChanged(old)
        if (index >= 0) notifyItemChanged(index)
    }
}
