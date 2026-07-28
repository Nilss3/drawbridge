package app.drawbridge.herald.filter

import android.content.Context
import app.drawbridge.herald.HeraldPolicy
import app.drawbridge.herald.R

/**
 * The page shown instead of blocked content.
 *
 * Built as a self-contained string rather than fetched, so it renders with no
 * network and no assets — the icon is inline SVG and the styles are inline. The
 * blocked host is repeated in the page body because the address bar shows the
 * `data:` URL the engine loaded, not the site that was requested.
 */
object BlockedPage {

    /**
     * Returns the page as plain HTML.
     *
     * Pair it with `encoding = "base64"`: GeckoView's loader base64-encodes the
     * bytes it is handed, so passing an already-encoded string here would encode
     * it twice and render the page as a wall of base64 text.
     */
    fun create(context: Context, url: String): String {
        val message = HeraldPolicy.manager(context).policy.value.browser.blockedPageMessage
        val host = app.drawbridge.policy.ContentFilter.hostOf(url) ?: url

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>${context.getString(R.string.blocked_page_title)}</title>
              <style>
                :root { color-scheme: light dark; }
                body {
                  margin: 0; min-height: 100vh; display: flex; align-items: center;
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
                  background: #fff; border-radius: 16px; padding: 32px 28px;
                  max-width: 26rem; width: 100%; text-align: center;
                  box-shadow: 0 2px 16px rgba(0,0,0,.08);
                }
                .mark { margin-bottom: 16px; }
                .mark svg { width: 48px; height: 48px; }
                h1 { font-size: 1.25rem; margin: 0 0 12px; }
                p { margin: 0 0 16px; line-height: 1.5; opacity: .85; }
                .host {
                  display: inline-block; background: #eaeaee; border-radius: 8px;
                  padding: 6px 10px; font-family: ui-monospace, monospace;
                  font-size: .875rem; word-break: break-all;
                }
              </style>
            </head>
            <body>
              <div class="card">
                <div class="mark">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor"
                       stroke-width="1.8" stroke-linecap="round" aria-hidden="true">
                    <path d="M12 2.5 4.5 5.6v5.6c0 4.6 3.2 8.9 7.5 10.3 4.3-1.4 7.5-5.7 7.5-10.3V5.6L12 2.5Z"/>
                    <path d="M9.2 9.2l5.6 5.6M14.8 9.2l-5.6 5.6"/>
                  </svg>
                </div>
                <h1>${context.getString(R.string.blocked_page_heading)}</h1>
                <p>${message.escapeHtml()}</p>
                <span class="host">${host.escapeHtml()}</span>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun String.escapeHtml(): String = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
