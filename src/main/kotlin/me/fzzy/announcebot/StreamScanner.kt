package me.fzzy.announcebot

import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.utils.URIBuilder
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.net.URI
import java.util.concurrent.TimeUnit

class StreamScanner(game: String) {

    var streams = hashMapOf<Long, Stream>()
    private var pagination: String? = null
    var gameId: Int = 0
    private val file = File("$game.json")

    init {
        try {
            gameId = getGameIdRequest(game)
        } catch (e: Exception) {
            log.error("Could not retrieve game id from twitch, is the game name exactly as it is on the twitch directory?")
            e.printStackTrace()
        }
        if (gameId != 0) {
            log.info("$game id found: $gameId")
            loadStreams()
            scheduler.schedulePeriodically({
                nextPage()
            }, 10, config.scanIntervalSeconds, TimeUnit.SECONDS)
        }
    }

    private fun nextPage() {
        val iterator = streams.iterator()
        while(iterator.hasNext()) {
            val stream = iterator.next()

            if (!stream.value.isOnline() && stream.value.timeSinceOffline() >= config.broadcastCooldownMinutes * 60 * 1000) {
                //log.info("${stream.value.username} is no longer on cooldown")
                iterator.remove()
            }
        }
        var json: JSONObject? = null
        try {
            if (pagination == null) markInactiveStreams()
            json = getStreamsRequest(gameId, pagination)
            val array = json.getJSONArray("data")

            for (i in 0 until array.length()) {
                val stream = getStream(array.getJSONObject(i))
                stream.online()
                streams[stream.twitchId] = stream
                saveStreams()
            }

            pagination = if (json.getJSONObject("pagination")!!.has("cursor")) {
                json.getJSONObject("pagination").getString("cursor")
            } else null
        } catch (e: Exception) {
            if (json != null) {
                log.error(json.toString(2))
            }
            log.error("Error while trying to scan streams:")
            e.printStackTrace()
        }
    }

    fun markInactiveStreams() {
        for ((_, stream) in streams) {
            if (!stream.verifiedOnline) {
                stream.offline()
            }
            stream.verifiedOnline = false
        }
        saveStreams()
    }

    fun getStreamsRequest(gameId: Int, pagination: String? = null): JSONObject {
        val uriBuilder = URIBuilder("https://api.twitch.tv/helix/streams").addParameter("game_id", gameId.toString())
        if (pagination != null) uriBuilder.addParameter("after", pagination)
        val uri = uriBuilder.build()

        return twitchRequest(uri)
    }

    fun getGameIdRequest(name: String): Int {
        val uri = URIBuilder("https://api.twitch.tv/helix/games").addParameter("name", name).build()
        return twitchRequest(uri).getJSONArray("data").getJSONObject(0).getInt("id")
    }

    fun getStreamByTwitchId(twitchId: Long): Stream? {
        return streams[twitchId]
    }

    fun getStream(userId: Long): Stream? {
        for ((username, id) in streamingPresenceUsers) {
            if (id == userId) return getStream(username)
        }
        return null
    }

    fun getStream(username: String): Stream? {
        for ((_, stream) in streams) {
            if (stream.username.lowercase() == username.lowercase()) return stream
        }
        return null
    }

    fun getStream(title: String, username: String, twitchId: Long, tags: ArrayList<String>): Stream {
        return if (streams.containsKey(twitchId)) {
            val stream = streams[twitchId]!!
            stream.title = title
            stream.tags = tags
            stream.username = username
            stream
        } else {
            val stream = Stream(title, username, twitchId, tags)
            streams[twitchId] = stream
            stream
        }
    }

    fun getStream(json: JSONObject): Stream {
        val title = json.getString("title")
        val username = json.getString("user_name")
        val twitchId = json.getLong("user_id")
        val tags = arrayListOf<String>()
        try {
            val jsonTags = json.getJSONArray("tags")
            for (i in 0 until jsonTags.length()) {
                tags.add(jsonTags.getString(i))
            }
        } catch (e: Exception) {
            log.error("failure getting tags: $e")
        }
        return getStream(title, username, twitchId, tags)
    }

    private fun saveStreams() {
        val bufferWriter = BufferedWriter(FileWriter(file.absoluteFile, false))
        val save = JSONObject(gson.toJson(streams))
        bufferWriter.write(save.toString(2))
        bufferWriter.close()
    }

    fun loadStreams() {
        val token = object : TypeToken<HashMap<Long, Stream>>() {}
        if (file.exists()) streams = gson.fromJson(JsonReader(InputStreamReader(file.inputStream())), token.type)
    }
}