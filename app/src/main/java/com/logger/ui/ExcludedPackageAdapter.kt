package com.logger.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.logger.databinding.ItemExcludedPackageBinding

class ExcludedPackageAdapter(
    private var packages: List<String>,
    private val onRemoveClick: (String) -> Unit
) : RecyclerView.Adapter<ExcludedPackageAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemExcludedPackageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemExcludedPackageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val pkg = packages[position]
        holder.binding.textPackageName.text = pkg
        holder.binding.btnRemove.setOnClickListener {
            onRemoveClick(pkg)
        }
    }

    override fun getItemCount() = packages.size

    fun updateList(newList: List<String>) {
        packages = newList
        notifyDataSetChanged()
    }
}
