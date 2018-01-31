import com.github.salomonbrys.kotson.fromJson
import com.google.gson.Gson
import com.vk.api.sdk.client.VkApiClient
import com.vk.api.sdk.client.actors.ServiceActor
import com.vk.api.sdk.httpclient.HttpTransportClient
import com.vk.api.sdk.objects.wall.WallpostFull
import com.vk.api.sdk.queries.wall.WallGetFilter
import org.telegram.telegrambots.ApiContextInitializer
import org.telegram.telegrambots.TelegramBotsApi
import org.telegram.telegrambots.api.methods.send.SendDocument
import org.telegram.telegrambots.api.methods.send.SendMessage
import org.telegram.telegrambots.api.methods.send.SendPhoto
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
            bot)
}

class VkFetcher(appId: Int, token: String, chatToPosts: ChatIdToKnownPosts, private val bot: VkFetcherBot) {
    private val actor = ServiceActor(appId, token)
    private val vk = VkApiClient(HttpTransportClient())

    init {
        fun convertToAction(wallId: String, post: WallpostFull): List<(Long) -> Unit> {
            val result = mutableListOf<(Long) -> Unit>()
            val fromText = "<b>from $wallId</b> (added ${LocalDateTime.ofEpochSecond(post.date.toLong(), 0, ZoneOffset.UTC)} UTC):"
            val postText = if (post.text != null && post.text.isNotBlank()) {
                "\n\n" + post.text.replace(Regex("\\[(.+?)\\|(.+?)]"), "<a href=\"vk.com/$1\">$2</a>")
            } else {
                ""
            }
            result += { bot.execute(SendMessage(it, fromText + postText).apply { setParseMode("html") }) }
            if (post.attachments?.isNotEmpty() == true) {
                post.attachments.forEach {
                    when {
                        it.photo != null -> with(it.photo) {
                            photo2560 ?: photo1280 ?: photo807 ?: photo604
                        }?.let { url ->
                            result += {
                                bot.sendPhoto(SendPhoto().apply {
                                    chatId = it.toString()
                                    photo = url
                                })
                            }
                        }
                        it.video != null -> with(it.video) {
                            result += { bot.execute(SendMessage(it, "vk.com/video${ownerId}_$id")) }
                        }
                        it.link != null -> if (post.text?.contains(it.link.url) != true) with(it.link) {
                            result += { bot.execute(SendMessage(it, url)) }
                        }
                        it.audio != null -> with(it.audio) {
                            result += { bot.execute(SendMessage(it, "Audio: $artist - $title")) }
                        }
                        it.doc != null -> with(it.doc) {
                            result += {
                                bot.sendDocument(SendDocument().apply {
                                    chatId = it.toString()
                                    document = url
                                })
                            }
                        }
                        it.album != null -> with(it.album) {
                            result += { bot.execute(SendMessage(it, "vk.com/album${ownerId}_$id")) }
                        }
                    }
                }
            }
            return if (result.size > 1 || postText.isNotBlank()) result else emptyList()
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
                            .flatMap { convertToAction(it.first, it.second) }
                            .forEach { it(chatId) }
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
        if (!sentToMe(update.message) || !update.message.hasText()) return

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
                if (wallToPosts?.remove(wallId) != null) {
                    execute(SendMessage(chatId, "Wall $wallId removed."))
                } else {
                    execute(SendMessage(chatId, "You weren't subscribed to wall#$wallId. Wrong id?"))
                    println("Wall $wallId not present in subscriptions of $chatId.")
                }
            }
        }
        if (text.startsWith("/list")) {
            execute(SendMessage(chatId, chatToPosts[chatId]?.keys?.joinToString(", ") ?: "No walls found for you"))
        }
    }

    private fun sentToMe(message: Message?) = message != null &&
            (message.isUserMessage || message.text?.contains("@$username") == true)
}

typealias ChatIdToKnownPosts = MutableMap<Long, MutableMap<String, MutableList<Int>>>
