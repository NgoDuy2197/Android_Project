package com.dsoft.jgamer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dsoft.jgamer.R
import com.dsoft.jgamer.model.GameEntry
import java.text.DateFormat
import java.util.Date

class GameListAdapter(
    private val onClick: (GameEntry) -> Unit,
    private val onLongClick: (GameEntry) -> Unit
) : ListAdapter<GameEntry, GameListAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_game, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val title: TextView = v.findViewById(R.id.gameTitle)
        private val subtitle: TextView = v.findViewById(R.id.gameSubtitle)
        private val badge: TextView = v.findViewById(R.id.gameBadge)

        fun bind(e: GameEntry) {
            title.text = e.title
            badge.text = e.system.displayName
            val played = if (e.lastPlayedAt > 0)
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.lastPlayedAt))
            else itemView.context.getString(R.string.never_played)
            subtitle.text = "${e.system.displayName} • $played"
            itemView.setOnClickListener { onClick(e) }
            itemView.setOnLongClickListener { onLongClick(e); true }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<GameEntry>() {
            override fun areItemsTheSame(a: GameEntry, b: GameEntry) = a.id == b.id
            override fun areContentsTheSame(a: GameEntry, b: GameEntry) =
                a.title == b.title && a.lastPlayedAt == b.lastPlayedAt
        }
    }
}
