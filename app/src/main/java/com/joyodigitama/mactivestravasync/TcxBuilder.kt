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
        val pointCount: Int,
        val hasGps: Boolean
    )

    /** Number of usable GPS coordinates in a workout. */
    fun gpsPointCount(workout: MicrowearClient.Workout): Int = parseCoords(workout).size

    private fun parseCoords(workout: MicrowearClient.Workout): List<Pair<String, String>> =
        workout.coordinateArr.split(",").mapNotNull { p ->
            if (p.contains("|")) {
                val parts = p.split("|")
                if (parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    parts[0].trim() to parts[1].trim()
                } else null
            } else null
        }

    fun build(workout: MicrowearClient.Workout, allowNoGps: Boolean = false): BuildResult? {
        val coords = parseCoords(workout)

        val hasGps = coords.size >= 10
        if (!hasGps && !allowNoGps) return null

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
        val titlePrefix = when {
            isBike -> "Bersepeda"
            workout.type == 2 || workout.type == 141 -> "Jalan Kaki"
            workout.type == 1 || workout.type == 140 -> if (hasGps) "Lari" else "Lari Indoor"
            else -> "Olahraga"
        }

        val titleFormat = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("Asia/Jakarta")
        }
        val activityName = "$titlePrefix ${titleFormat.format(startDate)}"

        val duration = if (workout.duration > 0) workout.duration else maxOf(60, coords.size)
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

        if (hasGps) {
            val stepSec = duration.toDouble() / maxOf(1, coords.size - 1)
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
        } else {
            val sampleCount = maxOf(2, minOf(hrs.size, 60))
            val stepSec = duration.toDouble() / maxOf(1, sampleCount - 1)
            for (i in 0 until sampleCount) {
                val ptTime = isoFormat.format(Date(startDate.time + (i * stepSec * 1000L).toLong()))
                val hrVal = if (hrs.isNotEmpty()) {
                    val idx = minOf(hrs.size - 1, (i * hrs.size) / sampleCount)
                    hrs[idx]
                } else null

                sb.append("""          <Trackpoint>
            <Time>$ptTime</Time>""")
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
            pointCount = if (hasGps) coords.size else 0,
            hasGps = hasGps
        )
    }
}
