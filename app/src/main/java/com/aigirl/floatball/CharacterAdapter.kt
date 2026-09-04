package com.aigirl.floatball

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

/**
 * 角色选择列表适配器
 */
class CharacterAdapter(
    private val ctx: Context,
    private val list: List<CharacterDef>,
    private var selectedId: String,
    private val onClick: (CharacterDef) -> Unit,
) : RecyclerView.Adapter<CharacterAdapter.VH>() {

    class VH(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_character, parent, false)
    ) {
        val card: MaterialCardView = itemView.findViewById(R.id.cardChar)
        val avatar: ImageView = itemView.findViewById(R.id.ivCharAvatar)
        val name: TextView = itemView.findViewById(R.id.tvCharName)
        val tick: android.view.View = itemView.findViewById(R.id.vSelectedTick)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(parent)

    override fun getItemCount() = list.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = list[position]
        holder.avatar.setImageResource(item.drawableRes)
        holder.name.setText(item.nameRes)
        val isSel = item.id == selectedId
        holder.tick.visibility = if (isSel) android.view.View.VISIBLE else android.view.View.GONE
        val colorSelected = android.graphics.Color.parseColor(item.accentColor)
        holder.card.strokeColor = if (isSel) colorSelected else 0x00000000
        holder.card.setCardBackgroundColor(
            if (isSel) colorSelected and 0x0FFFFFFF or 0x1A000000.toInt()
            else ContextCompat.getColor(ctx, R.color.bg_card)
        )
        holder.itemView.setOnClickListener {
            if (selectedId != item.id) {
                val old = selectedId
                selectedId = item.id
                notifyItemChanged(list.indexOfFirst { it.id == old })
                notifyItemChanged(holder.bindingAdapterPosition)
            }
            onClick(item)
        }
    }

    fun setSelected(id: String) {
        val oldIdx = list.indexOfFirst { it.id == selectedId }
        selectedId = id
        val newIdx = list.indexOfFirst { it.id == id }
        if (oldIdx >= 0) notifyItemChanged(oldIdx)
        if (newIdx >= 0) notifyItemChanged(newIdx)
    }

    fun getSelectedId() = selectedId
}
