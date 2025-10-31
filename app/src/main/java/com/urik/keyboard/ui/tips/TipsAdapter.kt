package com.urik.keyboard.ui.tips

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.urik.keyboard.R

class TipsAdapter(
    private val tips: List<TipItem>,
) : RecyclerView.Adapter<TipsAdapter.TipViewHolder>() {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): TipViewHolder {
        val view =
            LayoutInflater
                .from(parent.context)
                .inflate(R.layout.item_tip_card, parent, false)
        return TipViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: TipViewHolder,
        position: Int,
    ) {
        holder.bind(tips[position])
    }

    override fun getItemCount(): Int = tips.size

    class TipViewHolder(
        itemView: View,
    ) : RecyclerView.ViewHolder(itemView) {
        private val titleTextView: TextView = itemView.findViewById(R.id.tip_title)
        private val descriptionTextView: TextView = itemView.findViewById(R.id.tip_description)

        fun bind(tip: TipItem) {
            titleTextView.setText(tip.titleResId)
            descriptionTextView.setText(tip.descriptionResId)
        }
    }
}
