package com.kc3smw.cyclemania

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object GpxExporter {

    fun export(context: Context, points: List<TrackPoint>, distanceMeters: Double): File? {
        if (points.isEmpty()) return null
        val dateLabel = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)
            .format(Date(points.first().timestamp))
        val dir = context.getExternalFilesDir("rides") ?: return null
        dir.mkdirs()
        val file = File(dir, "$dateLabel.gpx")
        val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val distKm = String.format("%.2f", distanceMeters / 1000.0)
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<gpx version="1.1" creator="MyCycleMania" xmlns="http://www.topografix.com/GPX/1/1">""")
        sb.appendLine("""  <metadata><name>Ride $dateLabel</name><desc>Distance: ${distKm}km</desc></metadata>""")
        sb.appendLine("""  <trk><name>Ride $dateLabel</name><trkseg>""")
        for (p in points) {
            val timeStr = isoFmt.format(Date(p.timestamp))
            sb.appendLine("""    <trkpt lat="${p.latitude}" lon="${p.longitude}"><ele>${p.altitude}</ele><time>$timeStr</time></trkpt>""")
        }
        sb.appendLine("""  </trkseg></trk>""")
        sb.appendLine("""</gpx>""")
        file.writeText(sb.toString())
        return file
    }
}
