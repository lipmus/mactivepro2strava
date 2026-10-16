package com.joyodigitama.mactivestravasync

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object TcxBuilder {

    data class BuildResult(
        val tcxBytes: ByteArray,
        val activityName: String,
        val sportType: String,
        val pointCount: Int
    )

    fun build(workout: MicrowearClient.Workout): BuildResult? {
        val coords = workout.coordinateArr.split(",").mapNotNull { p ->
            if (p.contains("|")) {
                val parts = p.split("|")
                if (parts.size >= 2) parts[0].trim() to parts[1].trim() else null
            } else null
        }

        if (coords.size < 10) return null

        val localFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Jakarta")
        }
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val startDate: Date = try {
            localFormat.parse(workout.dateTime) ?: Date(workout.timeStamp * 1000L)
        } catch (_: Exception) {
            Date(workout.timeStamp * 1000L)
        }

        val startIso = isoFormat.format(startDate)

        val isBike = workout.type == 3 || workout.type == 142 || workout.type == 143
        val tcxSport = if (isBike) "Biking" else "Running"
        val stravaSport = if (isBike) "ride" else "run"
        val titlePrefix = if (isBike) "Bersepeda" else "Lari"

        val titleFormat = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("Asia/Jakarta")
        }
        val activityName = "$titlePrefix ${titleFormat.format(startDate)}"

        val duration = if (workout.duration > 0) workout.duration else coords.size
        val stepSec = duration.toDouble() / maxOf(1, coords.size - 1)

        val hrs = workout.heartRateArr.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 30..230 }

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>
<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">
  <Activities>
    <Activity Sport="$tcxSport">
      <Id>$startIso</Id>
      <Lap StartTime="$startIso">
        <TotalTimeSeconds>$duration</TotalTimeSeconds>
        <DistanceMeters>${workout.distance}</DistanceMeters>
        <Calories>${workout.calories.toInt()}</Calories>
        <Intensity>Active</Intensity>
        <TriggerMethod>Manual</TriggerMethod>
        <Track>
""")

        for ((i, pt) in coords.withIndex()) {
            val ptTime = isoFormat.format(Date(startDate.time + (i * stepSec * 1000L).toLong()))
            val hrVal = if (hrs.isNotEmpty()) {
                val idx = minOf(hrs.size - 1, (i * hrs.size) / coords.size)
                hrs[idx]
            } else null

            sb.append("""          <Trackpoint>
            <Time>$ptTime</Time>
            <Position>
              <LatitudeDegrees>${pt.first}</LatitudeDegrees>
              <LongitudeDegrees>${pt.second}</LongitudeDegrees>
            </Position>""")
            if (hrVal != null) {
                sb.append("""
            <HeartRateBpm>
              <Value>$hrVal</Value>
            </HeartRateBpm>""")
            }
            sb.append("""
          </Trackpoint>
""")
        }

        sb.append("""        </Track>
      </Lap>
    </Activity>
  </Activities>
</TrainingCenterDatabase>
""")

        return BuildResult(
            tcxBytes = sb.toString().toByteArray(Charsets.UTF_8),
            activityName = activityName,
            sportType = stravaSport,
            pointCount = coords.size
        )
    }
}
