package com.example.isekichadet

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class RecordAdapter(private val list: List<Record>) :
    RecyclerView.Adapter<RecordAdapter.RecordViewHolder>() {

    class RecordViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvProduksi: TextView = itemView.findViewById(R.id.tvProduksi)
        val tvKanban: TextView = itemView.findViewById(R.id.tvKanban)
        val tvScan: TextView = itemView.findViewById(R.id.tvScan)
        val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_record, parent, false)
        return RecordViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecordViewHolder, position: Int) {
        val record = list[position]
        holder.tvProduksi.text = record.No_Produksi
        holder.tvKanban.text = if (record.No_Chasis_Kanban.isNullOrBlank() || record.No_Chasis_Kanban == "null") "" else record.No_Chasis_Kanban
        holder.tvScan.text = if (record.No_Chasis_Scan.isNullOrBlank() || record.No_Chasis_Scan == "null") "" else record.No_Chasis_Scan
        holder.tvTime.text = record.Time
        holder.tvStatus.text = record.Status_Record

        when {
            record.Status_Record.equals("OK", ignoreCase = true) -> {
                holder.tvStatus.setBackgroundResource(R.drawable.bg_badge_ok)
            }
            record.Status_Record.equals("NG-Approved", ignoreCase = true) -> {
                holder.tvStatus.setBackgroundResource(R.drawable.bg_badge_approved)
//                holder.tvStatus.setTextSize(12F)
                holder.tvStatus.text = "NG-OK"
            }
            record.Status_Record.equals("NG", ignoreCase = true) -> {
                holder.tvStatus.setBackgroundResource(R.drawable.bg_badge_ng)
            }
            else -> {
                holder.tvStatus.setBackgroundResource(R.drawable.bg_badge_default)
            }
        }

        // Baris belang
        if (position % 2 == 0) {
            holder.itemView.setBackgroundColor(android.graphics.Color.parseColor("#FFFEC7B4")) // putih
        } else {
            holder.itemView.setBackgroundColor(android.graphics.Color.parseColor("#FFFC819E")) // abu-abu muda
        }
    }

    override fun getItemCount(): Int = list.size
}
