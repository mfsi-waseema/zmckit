package com.zmckitlibrary.widgets

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.snap.camerakit.lenses.LensesComponent
import com.zmckitlibrary.R

class LensCarouselAdapter(
    private val lensList: List<LensesComponent.Lens>,
    private val onLensSelected: (LensesComponent.Lens) -> Unit
) : RecyclerView.Adapter<LensCarouselAdapter.LensViewHolder>() {

    private var selectedPosition = 0

    inner class LensViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.lensImage)
        val borderContainer: FrameLayout = view.findViewById(R.id.borderContainer)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LensViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lens, parent, false)
        return LensViewHolder(view)
    }

    override fun onBindViewHolder(holder: LensViewHolder, position: Int) {
        val isSelected = position == selectedPosition

        // Load image with Glide
        Glide.with(holder.imageView)
            .load(lensList[position].icons.find { it is LensesComponent.Lens.Media.Image.Webp }?.uri)
            .placeholder(R.drawable.placeholder_image)
            .into(holder.imageView)

        // Apply border if selected
        holder.borderContainer.setBackgroundResource(if (isSelected) R.drawable.border_white else android.R.color.transparent)

        // Handle click selection
        holder.itemView.setOnClickListener {
            val newPosition = holder.adapterPosition
            if (newPosition == RecyclerView.NO_POSITION) return@setOnClickListener

            val previousPosition = selectedPosition
            selectedPosition = newPosition

            notifyItemChanged(previousPosition)
            notifyItemChanged(selectedPosition)

            onLensSelected(lensList[selectedPosition])
        }
    }

    override fun getItemCount(): Int = lensList.size
}
