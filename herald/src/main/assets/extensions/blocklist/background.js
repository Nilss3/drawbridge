"use strict";

/*
 * Subresource filtering for herald.
 *
 * Document loads (main_frame / sub_frame) are handled natively by
 * HeraldRequestInterceptor, which can show a proper block page. This listener
 * covers everything else — images, scripts, XHR, fonts, media — where cancelling
 * the request silently is the right behaviour.
 *
 * The blocklist stays on the native side: Firefox lets a blocking webRequest
 * listener return a Promise, so each new hostname costs one native round trip
 * and is then cached for the life of the extension process.
 */

const NATIVE_APP = "herald";
const decisions = new Map();

// A long-lived port so the app can push cache invalidations when a new policy or
// blocklist is installed. Without it, decisions made before an update would
// survive until the browser process restarts.
let controlPort = null;

function connectControlPort() {
  try {
    controlPort = browser.runtime.connectNative(NATIVE_APP);
    controlPort.onMessage.addListener((message) => {
      if (message && message.command === "invalidate") {
        decisions.clear();
      }
    });
    controlPort.onDisconnect.addListener(() => {
      controlPort = null;
      decisions.clear();
    });
  } catch (e) {
    controlPort = null;
  }
}

connectControlPort();

function hostnameOf(url) {
  try {
    return new URL(url).hostname;
  } catch (e) {
    return null;
  }
}

function ask(host) {
  return browser.runtime
    .sendNativeMessage(NATIVE_APP, { host })
    .then((response) => {
      const blocked = !!(response && response.blocked);
      decisions.set(host, blocked);
      return blocked;
    })
    .catch(() => {
      // Fail open rather than breaking every page if the native side is not
      // reachable; the request interceptor and, on managed devices, the DNS
      // filter are still in front of this.
      decisions.set(host, false);
      return false;
    });
}

browser.webRequest.onBeforeRequest.addListener(
  (details) => {
    const host = hostnameOf(details.url);
    if (!host) {
      return {};
    }

    const cached = decisions.get(host);
    if (cached !== undefined) {
      return cached ? { cancel: true } : {};
    }

    return ask(host).then((blocked) => (blocked ? { cancel: true } : {}));
  },
  {
    urls: ["http://*/*", "https://*/*"],
    types: [
      "font",
      "image",
      "imageset",
      "media",
      "object",
      "object_subrequest",
      "ping",
      "script",
      "stylesheet",
      "websocket",
      "xmlhttprequest",
      "other",
    ],
  },
  ["blocking"],
);
