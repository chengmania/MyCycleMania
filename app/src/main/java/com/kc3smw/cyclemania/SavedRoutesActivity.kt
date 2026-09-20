package com.kc3smw.cyclemania

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
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

class SavedRoutesActivity : AppCompatActivity() {

    private lateinit var listRoutes: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: SavedRouteAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saved_routes)
        window.applyBarInsets(findViewById(android.R.id.content))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.saved_routes)

        listRoutes = findViewById(R.id.list_routes)
        tvEmpty = findViewById(R.id.tv_empty)

        adapter = SavedRouteAdapter(this, mutableListOf())
        listRoutes.adapter = adapter

        listRoutes.setOnItemClickListener { _, _, position, _ ->
            val route = adapter.getItem(position) ?: return@setOnItemClickListener
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_LOADED_ROUTE_ID, route.id))
            finish()
        }
        listRoutes.setOnItemLongClickListener { _, _, position, _ ->
            val route = adapter.getItem(position) ?: return@setOnItemLongClickListener true
            showRouteActions(route)
            true
        }

        loadRoutes()
    }

    private fun loadRoutes() {
        lifecycleScope.launch {
            val routes = withContext(Dispatchers.IO) { SavedRouteStore.listRoutes(this@SavedRoutesActivity) }
            adapter.clear()
            adapter.addAll(routes)
            tvEmpty.visibility = if (routes.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showRouteActions(route: RouteSummary) {
        val options = arrayOf(getString(R.string.rename), getString(R.string.delete))
        AlertDialog.Builder(this)
            .setTitle(route.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> promptRename(route)
                    1 -> confirmDeleteRoute(route)
                }
            }
            .show()
    }

    private fun promptRename(route: RouteSummary) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val editText = EditText(this).apply {
            setText(route.name)
            setSelection(text.length)
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_route)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { SavedRouteStore.renameRoute(this@SavedRoutesActivity, route.id, newName) }
                        loadRoutes()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteRoute(route: RouteSummary) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_route, route.name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { SavedRouteStore.deleteRoute(this@SavedRoutesActivity, route.id) }
                    loadRoutes()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private class SavedRouteAdapter(
        context: SavedRoutesActivity,
        items: MutableList<RouteSummary>
    ) : ArrayAdapter<RouteSummary>(context, 0, items) {

        private val useKm = PreferenceManager.getDefaultSharedPreferences(context)
            .getString("units", "km") == "km"
        private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_saved_route, parent, false)
            val route = getItem(position)!!

            view.findViewById<TextView>(R.id.tv_row_title).text = route.name

            val distStr = if (useKm) String.format("%.1f km", route.distanceMeters / 1000.0)
            else String.format("%.1f mi", route.distanceMeters / 1609.34)
            view.findViewById<TextView>(R.id.tv_row_subtitle).text =
                "Saved ${dateFormat.format(Date(route.createdTimestamp))} · $distStr"

            return view
        }
    }

    companion object {
        const val EXTRA_LOADED_ROUTE_ID = "extra_loaded_route_id"
    }
}
