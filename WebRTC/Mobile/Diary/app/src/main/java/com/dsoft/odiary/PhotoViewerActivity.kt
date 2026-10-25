package com.dsoft.odiary

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.io.File

/** Full-screen, swipeable photo viewer for a day's photos. */
class PhotoViewerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        val date = intent.getStringExtra("date") ?: return finish()
        val name = intent.getStringExtra("name")
        val photos = Store.allPhotos(date)
        if (photos.isEmpty()) return finish()
        val start = photos.indexOfFirst { it.name == name }.coerceAtLeast(0)

        val pager = ViewPager2(this).apply {
            setBackgroundColor(Color.BLACK)
            adapter = Adapter(photos)
            setCurrentItem(start, false)
        }
        setContentView(pager)
    }

    private inner class Adapter(val files: List<File>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val iv = ImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setOnClickListener { finish() }
            }
            return object : RecyclerView.ViewHolder(iv) {}
        }

        override fun getItemCount() = files.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val iv = holder.itemView as ImageView
            iv.setImageDrawable(null)
            val f = files[position]
            Thread {
                val b = Store.decodeSampled(f, 2000)
                if (b != null) App.main.post { iv.setImageBitmap(b) }
            }.start()
        }
    }
}
