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
import java.io.FileInputStream
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
    ApiContextInitializer.init()
    val bot = VkFetcherBot(properties.getProperty("telegram.token"), properties.getProperty("telegram.name"))
    TelegramBotsApi().registerBot(bot)
    VkFetcher(properties.getProperty("vk.appId").toInt(),
            properties.getProperty("vk.token"),
            setOf(-101952234, -79505183),
            bot::newPost + { println(it) })
}

class VkFetcher(appId: Int, token: String, wallOwners: Set<Int>, private val callback: (String) -> Unit) {
    private val actor = ServiceActor(appId, token)
    private val vk = VkApiClient(HttpTransportClient())
    private val knownPosts: Map<Int, MutableList<Int>> = wallOwners.map { it to mutableListOf<Int>() }.toMap() //todo provide saving

    init {
        val convertToText = { post: WallpostFull -> post.text } //todo provide group name, replace links

        timer(initialDelay = TimeUnit.SECONDS.toMillis(30), period = TimeUnit.SECONDS.toMillis(60)) {
            println("Try to update, 'k?")
            val posts = knownPosts.keys.map {
                it to vk.wall().get(actor)
                        .ownerId(it).count(5)
                        .filter(WallGetFilter.OWNER).execute().items
            }.toMap()
            val new = posts.mapValues { (key, value) -> value.filter { !knownPosts[key]!!.contains(it.id) } }
            new.mapValues { it.value.map { it.id } }.forEach { knownPosts[it.key]!!.addAll(it.value) }
            new.flatMap { it.value }.mapNotNull(convertToText).filter { it.isNotBlank() }.forEach(callback)
        }
    }

}

class VkFetcherBot(private val token: String, private val username: String) : TelegramLongPollingBot() {
    private val chats = hashSetOf<Long>() //todo provide saving
    override fun getBotToken(): String = token
    override fun getBotUsername() = username

    override fun onUpdateReceived(update: Update?) {
        update?.message?.chatId?.let(chats::add + { println(it) })
    }

    fun newPost(text: String) = chats.map { SendMessage(it, text) }.forEach { execute(it) }
}

operator fun <T> ((T) -> Any?).plus(and: (T) -> Any?): (T) -> Unit = {
    this(it)
    and(it)
}
