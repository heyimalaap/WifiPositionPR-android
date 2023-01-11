package com.iottest.wifipositionpr

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView


class WifiListAdapter(private val wifiListManager: WifiListManager) : RecyclerView.Adapter<WifiListAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val wifiTextView: TextView
        val levelTextView: TextView

        init {
            wifiTextView = view.findViewById(R.id.ssid_tv)
            levelTextView = view.findViewById(R.id.level_tv)
        }
    }

    override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(viewGroup.context)
            .inflate(R.layout.wifi_list_item, viewGroup, false)

        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        viewHolder.wifiTextView.text = wifiListManager.get_ith_entry(position).ssid_bssid
        viewHolder.levelTextView.text = wifiListManager.get_ith_entry(position).signal_strength.toString()
    }

    override fun getItemCount() = wifiListManager.number_of_entries
}

