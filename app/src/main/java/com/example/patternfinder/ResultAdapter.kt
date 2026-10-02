package com.example.patternfinder

import android.net.Uri
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class ResultAdapter : RecyclerView.Adapter<ResultAdapter.VH>() {

    private val uris = mutableListOf<Uri>()

    fun submit(newUris: List<Uri>) {
        uris.clear()
        uris.addAll(newUris)
        notifyDataSetChanged()
    }

    class VH(val iv: ImageView) : RecyclerView.ViewHolder(iv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val iv = ImageView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                300
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        return VH(iv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.iv.load(uris[position])
    }

    override fun getItemCount() = uris.size
}
