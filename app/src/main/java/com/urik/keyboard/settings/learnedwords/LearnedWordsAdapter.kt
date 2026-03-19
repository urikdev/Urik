package com.urik.keyboard.settings.learnedwords

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.urik.keyboard.R

class LearnedWordsAdapter(private val onDeleteClick: (String) -> Unit) :
    ListAdapter<String, LearnedWordsAdapter.WordViewHolder>(WordDiffCallback) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WordViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_learned_word, parent, false)
        return WordViewHolder(view, onDeleteClick)
    }

    override fun onBindViewHolder(holder: WordViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class WordViewHolder(itemView: View, private val onDeleteClick: (String) -> Unit) :
        RecyclerView.ViewHolder(itemView) {
        private val wordText: TextView = itemView.findViewById(R.id.word_text)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.delete_button)

        fun bind(word: String) {
            wordText.text = word
            deleteButton.setOnClickListener { onDeleteClick(word) }
        }
    }

    private object WordDiffCallback : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String): Boolean = oldItem == newItem
        override fun areContentsTheSame(oldItem: String, newItem: String): Boolean = oldItem == newItem
    }
}
