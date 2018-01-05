import com.github.salomonbrys.kotson.fromJson
import com.google.gson.Gson
import org.telegram.telegrambots.ApiContextInitializer
import org.telegram.telegrambots.TelegramBotsApi
import org.telegram.telegrambots.api.objects.Update
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import com.vk.api.sdk.client.VkApiClient
import com.vk.api.sdk.client.actors.ServiceActor
import com.vk.api.sdk.httpclient.HttpTransportClient
import com.vk.api.sdk.objects.wall.WallpostFull
import com.vk.api.sdk.queries.wall.WallGetFilter
import org.telegram.telegrambots.api.methods.send.SendMessage
import org.telegram.telegrambots.api.objects.Message
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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
            bot::newPost andThen { _, it -> println(it) })
}

class VkFetcher(appId: Int, token: String, chatToPosts: ChatIdToKnownPosts, private val callback: (Long, String) -> Unit) {
    private val actor = ServiceActor(appId, token)
    private val vk = VkApiClient(HttpTransportClient())

    init {
        val convertToText = { post: WallpostFull -> post.text } //todo provide group name, replace links

        timer(period = TimeUnit.SECONDS.toMillis(60)) {
            val wallOwnerIds = chatToPosts.values.flatMap { it.keys }.distinct()
            println("Going to find new wall messages for $wallOwnerIds")
            val posts = wallOwnerIds.map {
                it to vk.wall().get(actor)
                        .ownerId(it).count(5)
                        .filter(WallGetFilter.OWNER).execute().items
            }.toMap()
            chatToPosts.forEach { (chatId, knownPosts) ->
                val new = posts
                        .filterKeys { knownPosts.containsKey(it) }
                        .mapValues { (key, value) -> value.filter { !knownPosts[key]!!.contains(it.id) } }
                new.mapValues { it.value.map { it.id } }.forEach { knownPosts[it.key]!!.addAll(it.value) }
                new.flatMap { it.value }.mapNotNull(convertToText).filter { it.isNotBlank() }.forEach { callback(chatId, it) }
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
        if (sentToMe(update.message) && update.message?.text?.split(" ")?.size == 2) {
            val text = update.message.text
            val chatId = update.message.chatId
            val groupId = update.message.text.substringAfter(" ")
            if (text.startsWith("/add")) {
                println("Add new group ($groupId) for $chatId")
                val groupToPosts = chatToPosts.getOrPut(chatId, { hashMapOf() })
                groupToPosts.getOrPut(groupId.toInt(), { mutableListOf() })
                execute(SendMessage(chatId, "Group $groupId added."))
            } else if (text.startsWith("/remove")) {
                println("Remove group $groupId from $chatId")
                val groupToPosts = chatToPosts[chatId]
                groupToPosts?.remove(groupId.toInt())
                execute(SendMessage(chatId, "Group $groupId removed."))
            }
        }

    }

    private fun sentToMe(message: Message?) = message != null &&
            (message.isUserMessage || message.text?.contains("@$username") == true)

    fun newPost(chatId: Long, text: String): Message = execute(SendMessage(chatId, text))
}

infix fun <T, R> ((T, R) -> Any?).andThen(and: (T, R) -> Any?): (T, R) -> Unit = { f, s ->
    this(f, s)
    and(f, s)
}

typealias ChatIdToKnownPosts = MutableMap<Long, MutableMap<Int, MutableList<Int>>>
