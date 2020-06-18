import com.github.salomonbrys.kotson.fromJson
import com.google.gson.Gson
import com.vk.api.sdk.client.VkApiClient
import com.vk.api.sdk.client.actors.ServiceActor
import com.vk.api.sdk.httpclient.HttpTransportClient
import com.vk.api.sdk.objects.audio.Audio
import com.vk.api.sdk.objects.base.Link
import com.vk.api.sdk.objects.docs.Doc
import com.vk.api.sdk.objects.enums.WallFilter
import com.vk.api.sdk.objects.photos.Photo
import com.vk.api.sdk.objects.photos.PhotoAlbum
import com.vk.api.sdk.objects.video.Video
import com.vk.api.sdk.objects.wall.WallpostFull
import org.telegram.telegrambots.ApiContextInitializer
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.api.methods.send.SendDocument
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import java.io.FileInputStream
import java.net.URL
import java.nio.file.Files.*
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.concurrent.timer

private val json = Gson()

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Please specify properties file")
        return
    }
    val properties = Properties().apply {
        FileInputStream(args[0]).use(this::load)
    }
    val knownPostsLocation = Path.of("known_posts.json")
    val knownPostsJson = if (exists(knownPostsLocation)) readString(knownPostsLocation) else null
    val knownPosts = if (knownPostsJson?.isNotBlank() == true) {
        json.fromJson<ChatIdToKnownPosts>(knownPostsJson)
    } else {
        mutableMapOf()
    }

    timer(period = TimeUnit.SECONDS.toMillis(15)) {
        writeString(knownPostsLocation, json.toJson(knownPosts))
    }

    ApiContextInitializer.init()
    val bot = VkFetcherBot(properties.getProperty("telegram.token"), properties.getProperty("telegram.name"), knownPosts)
    TelegramBotsApi().registerBot(bot)
    VkFetcher(
            properties.getProperty("vk.appId").toInt(),
            properties.getProperty("vk.token"),
            knownPosts,
            bot
    ).start()
}

class VkFetcher(appId: Int, token: String, private val chatToPosts: ChatIdToKnownPosts, private val bot: VkFetcherBot) {
    private val actor = ServiceActor(appId, token)
    private val vk = VkApiClient(HttpTransportClient())

    fun start() {
        timer(period = TimeUnit.SECONDS.toMillis(60)) { processWallMessages() }
    }

    private fun processWallMessages() {
        try {
            val wallOwnerIds = chatToPosts.values.flatMap { it.keys }.distinct()
            println("Going to find new wall messages for $wallOwnerIds")
            val posts = wallOwnerIds.associateWith { wallOwnerId ->
                vk.wall().get(actor)
                        .apply {
                            wallOwnerId.toIntOrNull()?.let(this::ownerId) ?: domain(wallOwnerId)
                        }
                        .count(5)
                        .filter(WallFilter.OWNER)
                        .execute().items
            }
            chatToPosts.forEach { (chatId, knownPosts) ->
                val new = posts
                        .filterKeys { it in knownPosts }
                        .mapValues { (wallOwnerId, posts) -> posts.filter { it.id !in knownPosts[wallOwnerId]!! } }
                new.mapValues { (_, posts) -> posts.map { it.id } }
                        .forEach { (wallOwnerId, posts) -> knownPosts[wallOwnerId]!! += (posts) }
                new.flatMap { (wallId, posts) -> posts.map { wallId to it } }
                        .flatMap { (wallId, post) -> convertToAction(wallId, post) }
                        .forEach { it(chatId) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun convertToAction(wallId: String, post: WallpostFull): List<(Long) -> Unit> {
        val result = mutableListOf<(Long) -> Unit>()
        fun <T> addAction(t: T, action: (Long, T) -> Unit) {
            result += { action(it, t) }
        }

        val fromText = "<b>from $wallId</b> (added ${fromEpochSeconds(post.date)} UTC):"
        val postText = if (post.text?.isNotBlank() == true) {
            "\n\n" + post.text.replace(Regex("\\[(.+?)\\|(.+?)]"), "<a href=\"vk.com/$1\">$2</a>")
        } else {
            ""
        }
        addAction(fromText + postText, ::sendHtmlText)
        post.attachments?.forEach { attachment ->
            attachment.photo?.let { addAction(it, ::sendPhoto) }
            attachment.video?.let { addAction(it, ::sendVideo) }
            attachment.link?.takeIf { it.isNonPost(post.text) }?.let { addAction(it, ::sendLink) }
            attachment.audio?.let { addAction(it, ::sendAudio) }
            attachment.doc?.let { addAction(it, ::sendDoc) }
            attachment.album?.let { addAction(it, ::sendAlbum) }
        }
        return if (result.size > 1 || postText.isNotBlank()) result else emptyList()
    }

    private fun sendHtmlText(chatId: Long, text: String) {
        bot.execute(SendMessage(chatId, text).apply { enableHtml(true) })
    }

    private fun sendPhoto(chatId: Long, photo: Photo) {
        (photo.sizes.maxBy { it.width }?.url)?.let { url ->
            bot.execute(SendPhoto().apply {
                this.chatId = chatId.toString()
                this.photo = url.toInputFile()
            })
        }
    }

    private fun sendVideo(chatId: Long, video: Video) {
        bot.execute(SendMessage(chatId, "vk.com/video${video.ownerId}_${video.id}"))
    }

    private fun sendLink(chatId: Long, link: Link) {
        bot.execute(SendMessage(chatId, link.url.toString()))
    }

    private fun sendAudio(chatId: Long, audio: Audio) {
        bot.execute(SendMessage(chatId, "Audio: ${audio.artist} - ${audio.title}"))
    }

    private fun sendDoc(chatId: Long, doc: Doc) {
        bot.execute(SendDocument().apply {
            this.chatId = chatId.toString()
            document = doc.url.toInputFile()
        })
    }

    private fun sendAlbum(chatId: Long, album: PhotoAlbum) {
        bot.execute(SendMessage(chatId, "vk.com/album${album.ownerId}_${album.id}"))
    }

    private fun Link.isNonPost(postText: String?) =
            postText == null || url.toString() !in postText
}

class VkFetcherBot(private val token: String,
                   private val username: String,
                   private val chatToPosts: ChatIdToKnownPosts) : TelegramLongPollingBot() {
    override fun getBotToken() = token
    override fun getBotUsername() = username

    override fun onUpdateReceived(update: Update) {
        if (!sentToMe(update.message) || !update.message.hasText()) return

        val chatId = update.message.chatId
        val text = update.message.text
        if (text.split(" ").size == 2) {
            val wallId = text.substringAfter(" ")
            if (text.startsWith("/add")) {
                processAddCommand(chatId, wallId)
            } else if (text.startsWith("/remove")) {
                processRemoveCommand(chatId, wallId)
            }
        }
        if (text.startsWith("/list")) processListCommand(chatId)
    }

    private fun processAddCommand(chatId: Long, wallId: String) {
        println("Add new wall ($wallId) for $chatId")
        val wallToPosts = chatToPosts.getOrPut(chatId) { mutableMapOf() }
        wallToPosts.getOrPut(wallId) { mutableListOf() }
        execute(SendMessage(chatId, "Wall $wallId added."))
    }

    private fun processRemoveCommand(chatId: Long, wallId: String) {
        println("Remove wall $wallId from $chatId")
        val wallToPosts = chatToPosts[chatId]
        if (wallToPosts?.remove(wallId) != null) {
            execute(SendMessage(chatId, "Wall $wallId removed."))
        } else {
            execute(SendMessage(chatId, "You weren't subscribed to wall#$wallId. Wrong id?"))
            println("Wall $wallId not present in subscriptions of $chatId.")
        }
    }

    private fun processListCommand(chatId: Long) {
        println("List walls for $chatId")
        val wallsText = chatToPosts[chatId]?.keys?.takeUnless { it.isEmpty() }?.joinToString(", ")
        execute(SendMessage(chatId, wallsText ?: "No walls found for you"))
    }

    private fun sentToMe(message: Message?) = message != null &&
            (message.isUserMessage || message.text?.contains("@$username") == true)
}

typealias ChatIdToKnownPosts = MutableMap<Long, MutableMap<String, MutableList<Int>>>

fun URL.toInputFile() = InputFile(toString())
fun fromEpochSeconds(seconds: Int): LocalDateTime =
        LocalDateTime.ofEpochSecond(seconds.toLong(), 0, ZoneOffset.UTC)
