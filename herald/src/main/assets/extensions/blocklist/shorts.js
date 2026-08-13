// Turns a Short into the same video in the ordinary player, from inside the page.
//
// Kotlin's Shorts.redirected already rewrites /shorts/<id> on any *navigation*
// GeckoView reports, which covers a typed URL, a link from another app, and a
// restored session. It does not cover the case that actually happens: tapping a
// Short inside YouTube itself. YouTube is a single-page app, so that is a
// history.pushState and no navigation is reported to intercept. Found on the
// reference phone on 2026-08-13, where the rewrite looked simply broken.
//
// This is deliberately the *only* thing in the file. It reads location and
// nothing else — no selectors, no element names, no assumptions about YouTube's
// markup, which is what rots. The one fact it depends on is that a Short and a
// video share an id, and that has been true since Shorts existed.

(function () {
  "use strict";

  const SHORTS = /^\/shorts\/([^/?#]+)/;

  function rewrite() {
    const match = SHORTS.exec(location.pathname);
    if (!match) return;

    const id = match[1];
    // Carry the rest of the query across, minus a v of its own, so the page
    // cannot be handed two and pick the wrong one. Mirrors the Kotlin side.
    const params = new URLSearchParams(location.search);
    params.delete("v");
    const rest = params.toString();
    const target = `/watch?v=${encodeURIComponent(id)}${rest ? "&" + rest : ""}${location.hash}`;

    // replace() rather than assign(): the Short should not sit in the back stack
    // waiting to be returned to, which would put the feed one gesture away again.
    location.replace(target);
  }

  // pushState and replaceState fire no event of their own, so they are wrapped.
  // The wrappers only observe; they always call through.
  for (const name of ["pushState", "replaceState"]) {
    const original = history[name];
    history[name] = function () {
      const result = original.apply(this, arguments);
      rewrite();
      return result;
    };
  }

  // Back and forward, which do fire.
  window.addEventListener("popstate", rewrite);

  // And the load that brought us here, for the case where Kotlin did not see it
  // — an iframe, or a redirect chain that ended on a Short.
  rewrite();
})();
