package com.anyfile.x.feature.openas

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.anyfile.x.databinding.ItemFileTypeBinding
import com.anyfile.x.engine.MimeDetector

/** One cell in the 3x3 Open As grid. */
sealed class OpenAsGridItem {
    data class Recommended(
        val mime: String,
        val detectedType: MimeDetector.FileType
    ) : OpenAsGridItem()

    data class Category(val type: MimeDetector.FileType) : OpenAsGridItem()
}

/**
 * Grid adapter for the "Open as" type chooser.
 * First cell is the detected/recommended MIME; the rest are category overrides.
 */
class FileTypeAdapter(
    private val items: List<OpenAsGridItem>,
    private var selectedIndex: Int = 0,
    private val onItemSelected: (OpenAsGridItem) -> Unit
) : RecyclerView.Adapter<FileTypeAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemFileTypeBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFileTypeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        when (val item = items[position]) {
            is OpenAsGridItem.Recommended -> {
                holder.binding.emojiText.text = "★"
                holder.binding.labelText.text = "Recommended"
            }
            is OpenAsGridItem.Category -> {
                holder.binding.emojiText.text = item.type.emoji
                holder.binding.labelText.text = item.type.label
            }
        }
        holder.binding.root.isSelected = position == selectedIndex
        holder.binding.root.setOnClickListener {
            val prev = selectedIndex
            selectedIndex = position
            if (prev in items.indices) notifyItemChanged(prev)
            notifyItemChanged(selectedIndex)
            onItemSelected(items[position])
        }
    }

    override fun getItemCount(): Int = items.size
}
