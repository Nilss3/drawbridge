package app.drawbridge.herald.filter

import android.content.Context
import app.drawbridge.herald.HeraldPolicy
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.support.base.log.logger.Logger
import org.json.JSONObject

/**
 * Enforces the blocklist on subresource loads — images, scripts, XHR, media —
 * which never reach [HeraldRequestInterceptor].
 *
 * A bundled web extension holds a blocking `webRequest` listener and asks this
 * class about each new hostname over native messaging. The blocklist itself is
 * never handed to JavaScript: it is hundreds of thousands of entries living in a
 * memory-mapped file, and copying it into the extension's heap on every policy
 * update would be both slow and pointless. The extension caches each answer per
 * hostname, so the round trip happens once per host per session.
 */
class BlocklistExtension(private val context: Context) {

    private val logger = Logger("herald-blocklist-extension")

    @Volatile
    private var controlPort: Port? = null

    private val messageHandler = object : MessageHandler {
        override fun onMessage(message: Any, source: EngineSession?): Any {
            val host = (message as? JSONObject)?.optString(FIELD_HOST).orEmpty()
            val blocked = host.isNotEmpty() && HeraldPolicy.manager(context).isHostBlocked(host)
            return JSONObject().put(FIELD_BLOCKED, blocked)
        }

        override fun onPortConnected(port: Port) {
            controlPort = port
        }

        override fun onPortDisconnected(port: Port) {
            if (controlPort === port) controlPort = null
        }
    }

    /** Tells the extension to forget its cached decisions after a policy change. */
    fun invalidateCache() {
        controlPort?.postMessage(JSONObject().put(FIELD_COMMAND, COMMAND_INVALIDATE))
    }

    fun install(engine: Engine) {
        engine.installBuiltInWebExtension(
            id = EXTENSION_ID,
            url = EXTENSION_URL,
            onSuccess = { extension ->
                extension.registerBackgroundMessageHandler(NATIVE_MESSAGING_NAME, messageHandler)
                logger.info("Installed content filter extension")
            },
            onError = { error ->
                // Not fatal: document loads are still filtered by the request
                // interceptor, and on a managed device the DNS layer is underneath
                // both. Loud in the log because it does widen the gap.
                logger.error("Could not install the content filter extension", error)
            },
        )
    }

    private companion object {
        const val EXTENSION_ID = "blocklist@herald.drawbridge.app"
        const val EXTENSION_URL = "resource://android/assets/extensions/blocklist/"
        const val NATIVE_MESSAGING_NAME = "herald"
        const val FIELD_HOST = "host"
        const val FIELD_BLOCKED = "blocked"
        const val FIELD_COMMAND = "command"
        const val COMMAND_INVALIDATE = "invalidate"
    }
}
