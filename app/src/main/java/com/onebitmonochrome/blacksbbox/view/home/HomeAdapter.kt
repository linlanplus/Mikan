package com.onebitmonochrome.blacksbbox.view.home

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.onebitmonochrome.blacksbbox.R
import com.onebitmonochrome.blacksbbox.databinding.ItemAppBinding
import com.onebitmonochrome.blacksbbox.databinding.ItemFolderBinding
import com.onebitmonochrome.blacksbbox.util.Resolution

class HomeAdapter(
        private val onClick: (HomeItem, View) -> Unit,
        private val onLongClick: (HomeItem, View) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var items: MutableList<HomeItem> = mutableListOf()
        private set

    /** Item being dragged; its slot stays in the grid but is drawn invisible. */
    var hiddenKey: String? = null
        set(value) {
            val old = field
            field = value
            listOf(old, value).distinct().forEach { key ->
                val index = items.indexOfFirst { it.key == key }
                if (index >= 0) notifyItemChanged(index, PAYLOAD_VISIBILITY)
            }
        }

    fun submit(newItems: List<HomeItem>) {
        val old = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].key == newItems[n].key
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == newItems[n]
        })
        items = newItems.toMutableList()
        diff.dispatchUpdatesTo(this)
    }

    /** Replaces everything at once, without item animations. */
    @SuppressLint("NotifyDataSetChanged")
    fun reset(newItems: List<HomeItem>) {
        items = newItems.toMutableList()
        notifyDataSetChanged()
    }

    /** Moves an item while dragging; the grid animates the others into place. */
    fun move(from: Int, to: Int) {
        if (from == to || from !in items.indices || to !in items.indices) return
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
    }

    fun insert(index: Int, item: HomeItem) {
        val i = index.coerceIn(0, items.size)
        items.add(i, item)
        notifyItemInserted(i)
    }

    fun replace(index: Int, item: HomeItem) {
        if (index !in items.indices) return
        items[index] = item
        notifyItemChanged(index)
    }

    fun remove(index: Int) {
        if (index !in items.indices) return
        items.removeAt(index)
        notifyItemRemoved(index)
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) =
            if (items[position] is HomeItem.FolderItem) TYPE_FOLDER else TYPE_CLONE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_FOLDER) {
            FolderVH(ItemFolderBinding.inflate(inflater, parent, false))
        } else {
            CloneVH(ItemAppBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_VISIBILITY }) {
            holder.itemView.alpha = if (items[position].key == hiddenKey) 0f else 1f
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is CloneVH -> holder.bind(item as HomeItem.CloneItem)
            is FolderVH -> holder.bind(item as HomeItem.FolderItem)
        }
        holder.itemView.alpha = if (item.key == hiddenKey) 0f else 1f
        holder.itemView.scaleX = 1f
        holder.itemView.scaleY = 1f
        setDropTarget(holder.itemView, false)
        holder.itemView.setOnClickListener { view ->
            items.getOrNull(holder.bindingAdapterPosition)?.let { onClick(it, view) }
        }
        holder.itemView.setOnLongClickListener { view ->
            items.getOrNull(holder.bindingAdapterPosition)?.let { onLongClick(it, view) }
            true
        }
    }

    class CloneVH(private val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: HomeItem.CloneItem) {
            setIcon(binding.icon, item)
            binding.name.text = item.name
            binding.cornerLabel.visibility = View.INVISIBLE
        }
    }

    class FolderVH(private val binding: ItemFolderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: HomeItem.FolderItem) {
            val slots = listOf(binding.icon1, binding.icon2, binding.icon3, binding.icon4)
            slots.forEachIndexed { i, view ->
                val clone = item.clones.getOrNull(i)
                if (clone == null) view.setImageDrawable(null) else setIcon(view, clone)
            }
            binding.name.text = item.folder.name
        }
    }

    companion object {
        const val TYPE_CLONE = 0
        private const val TYPE_FOLDER = 1
        private const val PAYLOAD_VISIBILITY = "visibility"

        private fun setIcon(view: ImageView, item: HomeItem.CloneItem) {
            val icon = item.icon
            // Each view gets its own drawable over the shared bitmap.
            if (icon is BitmapDrawable) view.setImageBitmap(icon.bitmap)
            else view.setImageDrawable(icon ?: ColorDrawable(Color.parseColor("#CCCCCC")))
        }

        /** Outlines the item a dragged clone would be dropped into. */
        fun setDropTarget(itemView: View, active: Boolean) {
            val card = itemView as? MaterialCardView ?: return
            val context = card.context
            card.strokeColor = ContextCompat.getColor(context, if (active) R.color.fab_tint else R.color.surface_variant)
            card.strokeWidth = Resolution.convertDpToPixel(if (active) 2f else 1f, context).toInt()
        }
    }
}
