# Open bug: back in mono's reader view

**Status: not fixed.** Two attempts are in `main`; the second is the one running
now and it does not work on the phone. This file exists so the next go starts
from evidence rather than from my guesses, and so the three dead ends below are
not walked again.

## What is wrong

In **herald mono**, reader view enters by itself on any readerable page. Pressing
back does not take you back. The reported symptom, twice: "it brings you to
current page".

herald standard is not affected and should be left alone: there, reader view is
something the reader chose from the menu, so back undoing it is right.

## What is known, with evidence

**Showing reader view is a navigation.** It occupies a history entry. Watched
live: entering reader view flipped `canGoBack` from false to true, and the tab's
URL briefly became `moz-extension://…` before `ReaderViewMiddleware` rewrote it
back to the article's own.

**`ReaderViewFeature.hideReaderView(tab)` retreats out of that entry itself.**
From the bytecode in `feature-readerview-153.0.aar`, in order:

```
dispatch UpdateReaderActiveAction(tab.id, false)
dispatch UpdateReaderableAction(tab.id, false)
dispatch ClearReaderActiveUrlAction(tab.id)
if (tab.content.canGoBack) engineSession.goBack(false)
```

The three dispatches happen **before** the retreat is asked for. That ordering is
the whole reason attempt 1 failed.

**`ReaderViewFeature.onBackPressed()` hides reader view and returns true** when
`readerState.active`, having first hidden the controls if they were up. So the
library always spends the press on leaving reader view.

**`content.history.items` is empty** — size 0, `currentIndex` 0. History state is
not tracked in herald, so `goToHistoryIndex` is not usable without turning that
on first. `canGoBack` / `canGoForward` *are* tracked and are reliable.

## What has been tried

**Attempt 1 — treat back as a dismissal** (`d11a42a`, released behaviour).
Back leaves reader view and sets `dismissedForPage` so the automatic entry does
not put it straight back. Verified working on the emulator. Rejected on review:
it costs two presses to leave an article, and the first one looks like it did
nothing.

**Attempt 2 — hide, then go back again** (`f9a90a2`, current). `onBackPressed`
sets the dismissal, records that a history step is owed, and calls
`hideReaderView`. The collector fires the second `goBack` when the retreat has
landed. Keying that off the reader flag fired it 6 ms later, while the engine was
still on the reader's entry — two `goBack`s against the same position move one
place between them — so the signal is `canGoForward` instead: reader view is
always the newest entry when it enters by itself, so nothing can go forward from
it, and the moment something can, the retreat has landed.

**The two-step mechanism was observed firing correctly**, which is what makes
this puzzling. Logged snapshots across one back press:

```
active=false readerable=false fwd=false back=true  owed=true    <- hide dispatched, still on the reader entry
active=false readerable=false fwd=true  back=false owed=true    <- retreat landed
active=false readerable=true  fwd=true  back=false owed=false   <- second goBack fired
```

Note `back=false` on the last two lines. In that run the article was the *first*
entry in the session, so the second `goBack` had nowhere to go and correctly did
nothing. **The mechanism has never been observed with a real page behind the
article.**

## First thing to check next time

**Ask how the article was reached.** If it was opened from another app, there is
no history behind it and back cannot go anywhere — the phone would sit on the
plain article, which looks exactly like the bug and is not one. Every automated
run so far hit this. A real test needs the previous page to have been reached
*inside* the browser: a tapped link, or a second URL typed into the bar.

If the article did have a page behind it and back still does not leave it, the
mechanism is failing somewhere it has not been watched, and the instrumentation
below is the way to see it.

## A real defect in attempt 2, worth fixing regardless

In `ReaderViewIntegration.onReaderStateChanged`:

```kotlin
if (goBackWhenReaderCloses && !snapshot.active) {
    if (!snapshot.canGoForward) return      // <- returns before everything else
    ...
}
```

If `canGoForward` never becomes true, that `return` runs on **every subsequent
snapshot**, so `lastUrl` is never updated, `dismissedForPage` is never cleared,
and **automatic reader view stops working for the rest of the session**. The flag
needs a way out — clear it on a URL change, or on the next navigation, rather
than letting it latch.

## Why the automated checks were useless

Three things each produced a convincing false negative. Do not trust an
adb-driven run that has not accounted for all three:

- **The soft keyboard swallows the back press.** After typing in the URL bar,
  `input keyevent KEYCODE_BACK` goes to the IME and never reaches the activity.
  Twenty `KEYCODE_ESCAPE` presses did not dismiss it; `mInputShown` stayed true.
- **`am start -a VIEW` replaces the session** rather than extending its history.
  Two intents in a row leave `canGoBack` false, so there is nothing to go back
  to. This is what made the mechanism look broken when it was working.
- **URL normalisation adds entries.** Typing `example.org` produces both
  `https://example.org` and `https://example.org/`, so a back press that worked
  looks like one that did not. Wikipedia redirects do the same.

The reliable setup is a person pressing back while `adb logcat` runs.

## Instrumentation that works

Drop this at the top of `onReaderStateChanged`, build mono, install, and read
`adb logcat -s herald-rv:I` while pressing back by hand:

```kotlin
android.util.Log.i(
    "herald-rv",
    "snap active=${snapshot.active} readerable=${snapshot.readerable} " +
        "fwd=${snapshot.canGoForward} back=${tab()?.content?.canGoBack} " +
        "owed=$goBackWhenReaderCloses url=${snapshot.url.take(60)}",
)
```

A matching line in `onBackPressed` showing `canGoBack` at press time is worth
having too. What to look for: whether a snapshot with `active=false` and
`canGoForward=true` ever arrives, and what `canGoBack` is at that moment. If it
is false, there was no history and the bug is the test. If it is true and the
page still does not change, the second `goBack` is being lost and the next thing
to try is `goToHistoryIndex` — which needs history-state tracking enabled first,
since `content.history.items` is currently empty.

## If it cannot be made to work

Attempt 1 is a defensible fallback: back leaves reader view and stays left, at
the cost of a second press to leave the page. It is committed history
(`d11a42a`) and was verified working. Reverting to it is a small change to
`onBackPressed` plus deleting `goBackWhenReaderCloses`.

The other option not yet explored is stopping reader view from taking a history
entry at all, which would make all of this unnecessary. That means not using
`ReaderViewFeature.showReaderView` — the entry comes from the extension
navigating to its own page — and is a much larger change.

## Where the code is

| | |
|---|---|
| `herald/src/main/java/app/drawbridge/herald/browser/ReaderViewIntegration.kt` | `onBackPressed`, `onReaderStateChanged`, `goBackWhenReaderCloses` |
| `herald/src/main/java/app/drawbridge/herald/browser/BrowserFragment.kt` | `backHandlers` order — reader view runs before `sessionFeature` |
| `herald/src/main/res/layout/fragment_browser.xml` | `readerViewControls`, `android:visibility="gone"` — checked, not the cause |

The phone (Nothing A059) has **herald mono 0.1.8** with attempt 2 on it,
release-signed, installed in place. herald standard 0.1.8 is on it too and is
unaffected by any of this.
