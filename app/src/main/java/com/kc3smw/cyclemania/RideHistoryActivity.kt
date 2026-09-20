package com.kc3smw.cyclemania

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class RideHistoryActivity : AppCompatActivity() {

    private lateinit var listRides: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: RideHistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ride_history)
        window.applyBarInsets(findViewById(android.R.id.content))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.ride_history)

        listRides = findViewById(R.id.list_rides)
        tvEmpty = findViewById(R.id.tv_empty)

        adapter = RideHistoryAdapter(this, mutableListOf())
        listRides.adapter = adapter

        listRides.setOnItemClickListener { _, _, position, _ ->
            val ride = adapter.getItem(position) ?: return@setOnItemClickListener
            startActivity(
                Intent(this, RideSummaryActivity::class.java)
                    .putExtra(RideSummaryActivity.EXTRA_RIDE_ID, ride.id)
            )
        }
        listRides.setOnItemLongClickListener { _, _, position, _ ->
            val ride = adapter.getItem(position) ?: return@setOnItemLongClickListener true
            confirmDeleteRide(ride)
            true
        }

        loadRides()
    }

    override fun onResume() {
        super.onResume()
        loadRides()
    }

    private fun loadRides() {
        lifecycleScope.launch {
            val rides = withContext(Dispatchers.IO) { RideHistoryStore.listRides(this@RideHistoryActivity) }
            adapter.clear()
            adapter.addAll(rides)
            tvEmpty.visibility = if (rides.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun confirmDeleteRide(ride: RideSummary) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_ride)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { RideHistoryStore.deleteRide(this@RideHistoryActivity, ride.id) }
                    loadRides()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private class RideHistoryAdapter(
        context: RideHistoryActivity,
        items: MutableList<RideSummary>
    ) : ArrayAdapter<RideSummary>(context, 0, items) {

        private val useKm = PreferenceManager.getDefaultSharedPreferences(context)
            .getString("units", "km") == "km"
        private val dateFormat = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.US)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_ride_history, parent, false)
            val ride = getItem(position)!!

            val distStr = if (useKm) String.format("%.1f km", ride.distanceMeters / 1000.0)
            else String.format("%.1f mi", ride.distanceMeters / 1609.34)
            view.findViewById<TextView>(R.id.tv_row_title).text =
                "${dateFormat.format(Date(ride.timestamp))} · $distStr"

            val hours = TimeUnit.MILLISECONDS.toHours(ride.durationMs)
            val mins = TimeUnit.MILLISECONDS.toMinutes(ride.durationMs) % 60
            val secs = TimeUnit.MILLISECONDS.toSeconds(ride.durationMs) % 60
            view.findViewById<TextView>(R.id.tv_row_subtitle).text =
                String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)

            return view
        }
    }
}
