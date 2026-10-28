package com.example.nflwatchface

import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.EmptyComplicationData
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the face shows: two lines in slot 0, one line in slot 1. */
data class GameView(val line1: String, val line2: String, val detail: String)

object BearsGames {
    private const val SCHEDULE =
        "https://site.api.espn.com/apis/site/v2/sports/football/nfl/teams/chi/schedule"
    private const val SCOREBOARD =
        "https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard"

    // Keep showing the final score until this long after kickoff, then switch to next game
    private const val FINAL_HOURS = 20L
    private const val BALL = "\uD83C\uDFC8"

    @Volatile private var cache: Pair<Long, GameView>? = null

    // Both slots call this; the cache means one network fetch serves both
    suspend fun current(): GameView {
        val c = cache
        if (c != null && System.currentTimeMillis() - c.first < 60_000) return c.second
        val v = withContext(Dispatchers.IO) { load() }
        cache = System.currentTimeMillis() to v
        return v
    }

    private fun load(): GameView {
        val events = getJson(SCHEDULE).getJSONArray("events")
        val now = Instant.now()
        var live: JSONObject? = null
        var lastPost: JSONObject? = null
        var next: JSONObject? = null

        for (i in 0 until events.length()) {
            val ev = events.getJSONObject(i)
            when (stateOf(ev)) {
                "in" -> live = ev
                "post" -> lastPost = ev
                else -> if (next == null) next = ev
            }
        }

        live?.let { return liveView(it) }
        lastPost?.let {
            if (Duration.between(kickoff(it), now) < Duration.ofHours(FINAL_HOURS)) return finalView(it)
        }
        next?.let { return preView(it) }
        return GameView("CHI", "Season over", "")
    }

    // ---- Views ----

    private fun liveView(scheduleEvent: JSONObject): GameView {
        var src = scheduleEvent
        try {
            // Scoreboard has possession and down/distance; schedule does not
            val sb = getJson(SCOREBOARD).getJSONArray("events")
            for (i in 0 until sb.length()) {
                if (sb.getJSONObject(i).optString("id") == scheduleEvent.optString("id")) {
                    src = sb.getJSONObject(i); break
                }
            }
        } catch (e: Exception) {
            Log.w("BearsScore", "scoreboard fetch failed", e)
        }
        val comp = comp(src)
        val (away, home) = sides(comp)
        val st = status(src)
        val sit = comp.optJSONObject("situation")
        val possId = sit?.optString("possession").orEmpty()
        val dd = sit?.optString("shortDownDistanceText").orEmpty()
        val detail = st.optJSONObject("type")?.optString("shortDetail").orEmpty()

        val clock = if (detail.startsWith("Half")) "Halftime"
        else "Q${st.optInt("period")} ${st.optString("displayClock")}"

        val line1 = "${label(away, possId)} ${score(away)}  ${label(home, possId)} ${score(home)}"
        return GameView(line1, if (dd.isNotBlank()) "$clock · $dd" else clock, "")
    }

    private fun finalView(ev: JSONObject): GameView {
        val comp = comp(ev)
        val (away, home) = sides(comp)
        val detail = status(ev).optJSONObject("type")?.optString("shortDetail").orEmpty()
        return GameView(
            "${abbr(away)} ${score(away)}  ${abbr(home)} ${score(home)}",
            detail.ifBlank { "Final" },
            ""
        )
    }

    private fun preView(ev: JSONObject): GameView {
        val comp = comp(ev)
        val (away, home) = sides(comp)
        val start = kickoff(ev).atZone(ZoneId.systemDefault())
        val time = start.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
        val whenText =
            if (start.toLocalDate() == ZonedDateTime.now().toLocalDate()) "Today $time"
            else start.format(DateTimeFormatter.ofPattern("EEE M/d h:mm a", Locale.getDefault()))
        val venue = comp.optJSONObject("venue")?.optString("fullName").orEmpty()
        val detail = listOf(venue, broadcast(comp)).filter { it.isNotBlank() }.joinToString(" · ")
        return GameView("${abbr(away)} @ ${abbr(home)}", whenText, detail)
    }

    // ---- JSON helpers ----

    private fun comp(ev: JSONObject) = ev.getJSONArray("competitions").getJSONObject(0)

    private fun status(ev: JSONObject): JSONObject =
        comp(ev).optJSONObject("status") ?: ev.optJSONObject("status") ?: JSONObject()

    private fun stateOf(ev: JSONObject) =
        status(ev).optJSONObject("type")?.optString("state").orEmpty()

    private fun kickoff(ev: JSONObject): Instant =
        ZonedDateTime.parse(ev.getString("date"), DateTimeFormatter.ISO_DATE_TIME).toInstant()

    // Away team first, home team second (standard "AWAY @ HOME" order)
    private fun sides(comp: JSONObject): Pair<JSONObject, JSONObject> {
        val teams = comp.getJSONArray("competitors")
        var away: JSONObject? = null
        var home: JSONObject? = null
        for (i in 0 until teams.length()) {
            val t = teams.getJSONObject(i)
            if (t.optString("homeAway") == "home") home = t else away = t
        }
        return away!! to home!!
    }

    private fun abbr(c: JSONObject) = c.getJSONObject("team").getString("abbreviation")

    private fun label(c: JSONObject, possId: String): String =
        if (possId.isNotEmpty() && possId == c.optString("id")) "$BALL${abbr(c)}" else abbr(c)

    // Score is a string on the scoreboard but an object on the schedule endpoint
    private fun score(c: JSONObject): String = when (val s = c.opt("score")) {
        null -> "0"
        is JSONObject -> s.optString("displayValue", "0")
        else -> s.toString()
    }

    private fun broadcast(comp: JSONObject): String {
        val b = comp.optJSONArray("broadcasts") ?: return ""
        for (i in 0 until b.length()) {
            val o = b.getJSONObject(i)
            o.optJSONObject("media")?.optString("shortName")?.takeIf { it.isNotBlank() }?.let { return it }
            o.optJSONArray("names")?.optString(0)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }

    private fun getJson(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        try {
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }
}

private fun longText(text: String, title: String? = null): ComplicationData {
    val b = LongTextComplicationData.Builder(
        PlainComplicationText.Builder(text).build(),
        PlainComplicationText.Builder("Bears game").build()
    )
    if (!title.isNullOrBlank()) b.setTitle(PlainComplicationText.Builder(title).build())
    return b.build()
}

/** Slot 0: teams/score + status line */
class BearsScoreService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        longText("CHI 14  GB 10", "Q3 8:42 · 2nd & 7")

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.LONG_TEXT) return null
        val g = try { BearsGames.current() } catch (e: Exception) {
             Log.w("BearsScore", "load failed", e)
             GameView("CHI --", e.javaClass.simpleName, "")
         }
        return longText(g.line1, g.line2)
    }
}

/** Slot 1: stadium and broadcast; empty during live games and finals */
class BearsDetailService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        longText("Lambeau Field · FOX")

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.LONG_TEXT) return null
        val g = try { BearsGames.current() } catch (e: Exception) { return EmptyComplicationData() }
        return if (g.detail.isBlank()) EmptyComplicationData() else longText(g.detail)
    }
}
