import com.github.salomonbrys.kotson.fromJson
import com.google.gson.Gson
import com.vk.api.sdk.client.VkApiClient
import com.vk.api.sdk.client.actors.ServiceActor
import com.vk.api.sdk.httpclient.HttpTransportClient
import com.vk.api.sdk.objects.wall.WallpostFull
import com.vk.api.sdk.queries.wall.WallGetFilter
import org.telegram.telegrambots.ApiContextInitializer
import org.telegram.telegrambots.TelegramBotsApi
import org.telegram.telegrambots.api.methods.send.SendMessage
import org.telegram.telegrambots.api.objects.Message
import org.telegram.telegrambots.api.objects.Update
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.concurrent.timer

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Please specify properties file")
        return
    }
    val properties = Properties().apply {
        FileInputStream(args[0]).use { load(it) }
    }
    val knownPostsJson = with(File("known_posts.json")) { if (exists()) readText() else null }
    val json = Gson()
    val knownPosts = if (knownPostsJson != null && knownPostsJson.isNotBlank()) {
        json.fromJson<ChatIdToKnownPosts>(knownPostsJson)
    } else {
        hashMapOf()
    }

    timer(period = TimeUnit.SECONDS.toMillis(15)) {
        File("known_posts.json").createNewFile()
        FileOutputStream("known_posts.json").bufferedWriter().use {
            it.write(json.toJson(knownPosts))
        }
    }

    ApiContextInitializer.init()
    val bot = VkFetcherBot(properties.getProperty("telegram.token"), properties.getProperty("telegram.name"), knownPosts)
    TelegramBotsApi().registerBot(bot)
    VkFetcher(properties.getProperty("vk.appId").toInt(),
            properties.getProperty("vk.token"),
            knownPosts,
            { _: Long, it: String -> println(it) } andThen bot::newPost)
}

class VkFetcher(appId: Int, token: String, chatToPosts: ChatIdToKnownPosts, private val callback: (Long, String) -> Unit) {
    private val actor = ServiceActor(appId, token)
    private val vk = VkApiClient(HttpTransportClient())

    init {
        fun convertToText(wallId: String, post: WallpostFull): List<String> {
            val result = mutableListOf<String>()
            if (post.text != null && post.text.isNotBlank()) {
                result.add(post.text.replace(Regex("\\[(.+?)\\|(.+?)]"), "<a href=\"vk.com/$1\">$2</a>"))
            }
            if (post.attachments != null && post.attachments.isNotEmpty()) {
                post.attachments.forEach {
                    when {
                        it.photo != null -> with(it.photo) { photo2560 ?: photo1280 ?: photo807 ?: photo604 }?.let { result.add(it) }
                        it.video != null -> with(it.video) { result.add("vk.com/video${ownerId}_$id") }
                        it.link != null -> if (post.text?.contains(it.link.url) != true) result.add(it.link.url)
                        it.audio != null -> result.add("Audio: ${it.audio.artist} - ${it.audio.title}")
                    }
                }
            }
            return result.map { "from $wallId (added ${LocalDateTime.ofEpochSecond(post.date.toLong(), 0, ZoneOffset.UTC)} UTC):\n\n$it" }
        }

        timer(period = TimeUnit.SECONDS.toMillis(60)) {
            try {
                val wallOwnerIds = chatToPosts.values.flatMap { it.keys }.distinct()
                println("Going to find new wall messages for $wallOwnerIds")
                val posts = wallOwnerIds.map {
                    it to vk.wall().get(actor)
                            .apply {
                                if (it.toIntOrNull() != null) {
                                    ownerId(it.toInt())
                                } else {
                                    domain(it)
                                }
                            }
                            .count(5)
                            .filter(WallGetFilter.OWNER)
                            .execute().items
                }.toMap()
                chatToPosts.forEach { (chatId, knownPosts) ->
                    val new = posts
                            .filterKeys { knownPosts.containsKey(it) }
                            .mapValues { (key, value) -> value.filter { !knownPosts[key]!!.contains(it.id) } }
                    new.mapValues { it.value.map { it.id } }.forEach { knownPosts[it.key]!!.addAll(it.value) }
                    new.flatMap { key -> key.value.map { key.key to it } }
                            .flatMap { convertToText(it.first, it.second) }
                            .filter { it.isNotBlank() }
                            .forEach { callback(chatId, it) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

}

class VkFetcherBot(private val token: String,
                   private val username: String,
                   private val chatToPosts: ChatIdToKnownPosts) : TelegramLongPollingBot() {
    override fun getBotToken(): String = token
    override fun getBotUsername() = username

    override fun onUpdateReceived(update: Update) {
        if (sentToMe(update.message) && update.message.hasText()) {
            val chatId = update.message.chatId
            val text = update.message.text
            if (text.split(" ").size == 2) {
                val wallId = text.substringAfter(" ")
                if (text.startsWith("/add")) {
                    println("Add new wall ($wallId) for $chatId")
                    val wallToPosts = chatToPosts.getOrPut(chatId, { hashMapOf() })
                    wallToPosts.getOrPut(wallId, { mutableListOf() })
                    execute(SendMessage(chatId, "Wall $wallId added."))
                } else if (text.startsWith("/remove")) {
                    println("Remove wall $wallId from $chatId")
                    val wallToPosts = chatToPosts[chatId]
                    wallToPosts?.remove(wallId)
                    execute(SendMessage(chatId, "Wall $wallId removed."))
                }
            }
            if (text.startsWith("/list")) {
                execute(SendMessage(chatId, chatToPosts[chatId]?.keys?.joinToString(", ") ?: "No walls found for you"))
            }
        }

    }

    private fun sentToMe(message: Message?) = message != null &&
            (message.isUserMessage || message.text?.contains("@$username") == true)

    fun newPost(chatId: Long, text: String): Message = execute(SendMessage(chatId, text).apply { setParseMode("html") })
}

infix fun <T, R> ((T, R) -> Any?).andThen(and: (T, R) -> Any?): (T, R) -> Unit = { f, s ->
    this(f, s)
    and(f, s)
}

typealias ChatIdToKnownPosts = MutableMap<Long, MutableMap<String, MutableList<Int>>>
