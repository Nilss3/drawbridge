// Turns a Short into the same video in the ordinary player, from inside the page.
//
// Kotlin's Shorts.redirected already rewrites /shorts/<id> on any *navigation*
// GeckoView reports, which covers a typed URL, a link from another app, and a
// restored session. It does not cover the case that actually happens: tapping a
// Short inside YouTube itself. YouTube is a single-page app, so that is a
// history.pushState and no navigation is reported to intercept.
//
// This is deliberately the *only* thing in the file. It reads location and
// nothing else — no selectors, no element names, no assumptions about YouTube's
// markup, which is what rots. The one fact it depends on is that a Short and a
// video share an id, and that has been true since Shorts existed.
//
// **Why this polls, which looks worse than it is.** The obvious version wraps
// history.pushState and reacts when YouTube calls it. That was shipped in
// herald 0.1.11 and never fired once. A content script runs in an isolated
// world with Xray vision: assigning to history.pushState replaces it *for this
// script only*, and the page goes on calling the original, so the wrapper sat
// there waiting for a call that by construction never arrived. Reaching the
// page's own object needs window.eval or exportFunction, which means running
// our code in YouTube's world, under YouTube's CSP, for a rewrite that has to
// keep working. location is the way across that costs nothing: it is the
// document's own state rather than a page object, so the isolated world sees
// every change to it, pushState included.
//
// The cost is a string compare every quarter second on youtube.com and nowhere
// else, skipped entirely while the tab is in the background.

(function () {
  "use strict";

  const SHORTS = /^\/shorts\/([^/?#]+)/;
  const EVERY_MS = 250;

  function target() {
    const match = SHORTS.exec(location.pathname);
    if (!match) return null;

    // Carry the rest of the query across, minus a v of its own, so the page
    // cannot be handed two and pick the wrong one. Mirrors the Kotlin side.
    const params = new URLSearchParams(location.search);
    params.delete("v");
    const rest = params.toString();
    return `/watch?v=${encodeURIComponent(match[1])}${rest ? "&" + rest : ""}${location.hash}`;
  }

  function rewrite() {
    const to = target();
    // replace() rather than assign(): the Short should not sit in the back stack
    // waiting to be returned to, which would put the feed one gesture away again.
    if (to) location.replace(to);
  }

  // Every way into the feed changes the URL, and this notices the change rather
  // than the thing that caused it: a tap, a swipe to the next Short, a back
  // gesture, and the load that brought us here all look the same from here.
  let seen = location.href;
  setInterval(function () {
    if (document.hidden) return;
    if (location.href === seen) return;
    seen = location.href;
    rewrite();
  }, EVERY_MS);

  rewrite();
})();
