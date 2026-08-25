package app.drawbridge.herald.filter

import android.content.Context
import android.util.Base64
import androidx.annotation.RawRes
import app.drawbridge.herald.R

/**
 * The card both of herald's own pages are drawn on: a scene, a heading, a
 * sentence, and one line of detail under it.
 *
 * Extracted from [BlockedPage] on 2026-08-25, when [OfflinePage] needed the same
 * card. Two hand-written copies of this markup would have drifted the first time
 * either was touched, and they would have drifted *visibly* — these are the two
 * pages a phone under drawbridge sees most, one after the other on a curfew
 * evening, and a card that changed shape between them would read as a fault.
 *
 * Self-contained on purpose, including the illustration, which is read out of
 * `res/raw` and inlined as a `data:` URI. Neither page may depend on the network:
 * one of them is shown *because* there is none.
 */
object HeraldCard {

    /**
     * The illustrations, base64-encoded, held for the life of the process.
     *
     * Worth caching: the pictures are the same every time, and somebody who has
     * hit a wall tends to hit it repeatedly.
     */
    private val scenes = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /**
     * Returns the page as plain HTML.
     *
     * Pair it with `encoding = "base64"` where the engine wants that: GeckoView's
     * loader base64-encodes the bytes it is handed, so passing an already-encoded
     * string would encode it twice and render a wall of base64 text.
     *
     * @param footnote the line under the message — the blocked host, or when a
     *   curfew ends. Omitted entirely when null, rather than rendered empty.
     * @param monospaceFootnote true for a hostname, false for a sentence.
     */
    fun html(
        context: Context,
        title: String,
        message: String,
        footnote: String?,
        monospaceFootnote: Boolean,
    ): String {
        val footnoteHtml = footnote?.let {
            val cls = if (monospaceFootnote) "host" else "note"
            """<span class="$cls">${it.escapeHtml()}</span>"""
        } ?: ""

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>${title.escapeHtml()}</title>
              <style>
                :root { color-scheme: light dark; }
                body {
                  margin: 0; min-height: 100vh; display: flex; align-items: flex-start;
                  justify-content: center; padding: 24px; box-sizing: border-box;
                  font-family: system-ui, -apple-system, sans-serif;
                  background: #f7f7f9; color: #1d1d21;
                }
                @media (prefers-color-scheme: dark) {
                  body { background: #1c1b22; color: #fbfbfe; }
                  .card { background: #2b2a33 !important; }
                  .host { background: #42414d !important; }
                }
                .card {
                  background: #fff; border-radius: 16px; overflow: hidden;
                  max-width: 30rem; width: 100%; text-align: center;
                  box-shadow: 0 2px 16px rgba(0,0,0,.08);
                }
                /* The scene is the top of the card rather than a small mark in
                   it: at a phone's width a 64px icon reads as an error, and
                   neither of these pages is an error. */
                .scene { display: block; width: 100%; height: auto; }
                .body { padding: 24px 28px 32px; }
                h1 { font-size: 1.25rem; margin: 0 0 12px; }
                p { margin: 0 0 16px; line-height: 1.5; opacity: .85; }
                .host {
                  display: inline-block; background: #eaeaee; border-radius: 8px;
                  padding: 6px 10px; font-family: ui-monospace, monospace;
                  font-size: .875rem; word-break: break-all;
                }
                .note { display: inline-block; font-size: .95rem; font-weight: 600; }
              </style>
            </head>
            <body>
              <div class="card">
                <!--
                  Both times of day travel with the page, and the browser picks.
                  A build-time choice would have been half the bytes and wrong
                  the moment the phone crossed into dark mode with the page still
                  open — the card under it turns, and a picture that did not
                  would be the only bright thing on the screen.
                -->
                <picture>
                  <source srcset="${scene(context, R.raw.blocked_scene_night)}"
                          media="(prefers-color-scheme: dark)">
                  <img class="scene" alt="" src="${scene(context, R.raw.blocked_scene_day)}">
                </picture>
                <div class="body">
                  <h1>${title.escapeHtml()}</h1>
                  <p>${message.escapeHtml()}</p>
                  $footnoteHtml
                </div>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    /**
     * One illustration as a `data:` URI, or an empty one if it cannot be read.
     *
     * A missing picture must not cost the page: the wording is what the page is
     * for, and an exception thrown here would leave the engine showing the site
     * that was supposed to be blocked.
     */
    private fun scene(context: Context, @RawRes resource: Int): String =
        scenes.getOrPut(resource) {
            runCatching {
                val bytes = context.resources.openRawResource(resource).use { it.readBytes() }
                "data:image/webp;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
            }.getOrDefault("")
        }

    private fun String.escapeHtml(): String = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
