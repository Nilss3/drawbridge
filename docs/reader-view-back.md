# Reader view: back, and when a page is an article

**Status: two of these three no longer exist, and the third still runs.** Three
bugs, all the same shape — state that cannot be believed yet. Back in mono's
reader view stopped at the plain article; reader view often failed to trigger at
all; and the fix for the second caused a third, where clicking a link put you
back on the page you had just left.

**Read this before believing any of it of today's code.** Mono stopped entering
reader view by itself on 2026-08-19 — the feature was wrong in use, and a slower
fling replaced it; see
[design-decisions](design-decisions.md#mono-asks-for-reader-view-rather-than-imposing-it).
So:

| | |
|---|---|
| **Back stopping at the plain article** | Gone with the feature. Back is `ReaderViewFeature.onBackPressed` again: it leaves reader view and stops on the article, which is what a reader who asked for reader view wants. `leavePageWhenArticleIsBack`, `ArticleReturn` and their tests are deleted. |
| **Reader view not triggering** | **Still live, and still load-bearing.** The re-check is what makes the reader-view menu entry appear on pages that are articles, in both editions. `recheckWhenPagesFinishLoading` and `askWhetherItIsAnArticle`. |
| **Being put back on the page you had just left** | Gone with the feature: nothing acts on a readability answer by itself any more. The *reason* it happened — the answer names no page — is unchanged and would bite anything that starts acting on one again. |

Kept whole because three attempts were spent on the first one, because the dead
ends below are expensive to walk twice, and because everything under
[things that make adb-driven testing lie](#things-that-make-adb-driven-testing-lie)
is about the phone rather than about this feature.

## What was wrong, and why

### Back stopped at the plain article

Showing reader view is a navigation — `ReaderViewFeature` loads the reader
extension's own page — so an article read in reader view occupies two history
entries. In mono, where reader view is not something anyone asked for, one press
has to undo both. `hideReaderView` steps out of the reader's entry itself; the
second step was the problem.

Two things were wrong with the previous attempt, and the first is the whole bug:

- **`sessionUseCases.goBack.invoke(sessionId)` with `sessionId == null` does
  nothing at all.** `GoBackUseCase.invoke` *defaults* its tab to the selected
  one, but an explicit null returns before dispatching:

  ```kotlin
  fun invoke(tabId: String? = store.state.selectedTabId, userInteraction: Boolean = true) {
      if (tabId == null) return
      store.dispatch(EngineAction.GoBackAction(tabId, userInteraction))
  }
  ```

  `sessionId` is null in the ordinary browser fragment — it is only set for
  custom tabs. So the second step was never taken in any normal session, however
  correctly the rest of the mechanism ran. That is why the logs looked right:
  the collector *did* fire, at the right moment, into a no-op.

- **`canGoForward` is about four hundred milliseconds early.** Measured across
  one back press: the position moved at 17.031 s, `canGoForward` turned true at
  17.039 s, and the article's load ended at 17.441 s. A `goBack` issued in that
  window is spent against a traversal already in flight and is lost. Waiting for
  `loading` to turn false lands it about 700 ms after the press, and it was
  accepted first time in every run.

The fix is both: wait for the load, and name the tab. Back out of reader view
now leaves the page in one press, three runs out of three, and the plain article
shows for about that 700 ms on the way past.

### Reader view often did not trigger

Readability is a single question put to a content script, and `ReaderViewMiddleware`
asks it the moment the URL changes — before the page it is asking about exists.
Whichever port is connected then answers: the outgoing document, or the incoming
one before it has been laid out. Readability decides by measuring rendered
paragraphs (`clientHeight > 0`), so a page asked too early says *no* about
itself. `checkRequired` is then cleared whether or not anything answered, and
nothing asks again.

Measured: `en.wikipedia.org/wiki/Ostend` scores 28.5 and `.../Marcel_Keizer`
23.5 against Readability's threshold of 20 once their DOM has settled — both
articles, comfortably. herald reported Marcel_Keizer as not readerable **six
times out of six** and Ostend about half the time, entirely according to how
fast the page came out of the cache.

The fix asks again after the load ends, up to four times at 700 ms. Once was not
enough, and there is no single moment to ask at instead: on a cached page
`loading` turns false about fifty milliseconds after the navigation, and
`firstContentfulPaint` and `progress` were both still carrying the *previous*
page's values at that point in every trace.

After: 7 of 8 Wikipedia articles enter reader view unprompted, and
Marcel_Keizer — which had never once worked — 4 times out of 4. In the standard
edition the same change is what makes the reader-view menu entry appear on
pages that are articles.

### And then it put you back on the page you had just left

Asking repeatedly made late answers ordinary, and the readability answer **names
no page** — it is a bare boolean against the tab. So an answer arriving during a
navigation is an answer about the page being left, and acting on it enters
reader view on *that* article. Entering reader view is itself a navigation, so
it loads the old article's reader page over the one that was asked for. Every
click handed you the previous page back. From the phone's log:

```
42.706  LocationChange = .../Execution_by_shooting          ← the page clicked to
42.810  PageStart      = readerview.html?url=...Peter_Arshinov   ← reader view for the page before it
```

This reads at first as the URL bar failing to update. It is not: the bar is
correctly showing the page herald pulled you to.

The guard is to enter reader view only when nothing is loading, and to stop
asking about a page once it starts loading again. Late answers then cost
nothing. The trade is that reader view waits for a page to settle before it
comes on, so a slow page shows plain for a moment first.

## Dead ends

**Do not make reader view replace the article's history entry.** This is the
tidiest idea available — `LOAD_FLAGS_REPLACE_HISTORY` on the reader page's load
gives mono one history entry per page, holding the page as mono shows it, and
makes back an ordinary back with no second step and no timing at all. It was
built, and it works exactly as designed until you press back, at which point
Gecko throws inside its own location reporting:

```
GeckoView:PageStart uri=https://example.org/
JavaScript Error: "TypeError: can't access property "nodePrincipal", this.contentDocument is null"
  get contentPrincipal@chrome://global/content/elements/browser-custom-element.mjs:687
  onLocationChange@resource://gre/modules/GeckoViewNavigation.sys.mjs:707
```

The engine navigates and paints the right page; the app is never told. `content.url`,
the toolbar and `readerState.active` stay on the article for ever, and `loading`
stays true. Traversing out of the reader page *without* the flag produces no
error at all, so it is the cross-process replace that Gecko does not survive.

**`content.history` is real but about ten seconds stale.** `goToHistoryIndex`
would make the second step one atomic navigation, which is what this bug wants.
The list arrives — while in reader view it read `size=2, currentIndex=1`, with
the reader page as the current entry, so `currentIndex - 2` is the right target
— but it arrives around ten seconds after the navigation, in the same update as
`UpdateEngineSessionStateAction`. Anyone pressing back before then would be
computing from the previous page's history.

**These were the earlier attempts**, both in `main`'s history: `d11a42a` treated
back as a dismissal, which costs two presses to leave an article; `f9a90a2` is
the two-step mechanism above, with the null tab id.

## Things that make adb-driven testing lie

Each of these produced a convincing false negative:

- **The soft keyboard swallows the back press.** After typing in the URL bar,
  `input keyevent KEYCODE_BACK` goes to the IME. `adb shell ime disable
  com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME`
  is the fix — `input text` still works without an IME. Re-enable it afterwards.
- **The first back press after typing a URL is eaten anyway**, by the toolbar
  leaving edit mode. Press twice, and read the log rather than the screen.
- **`am start -a VIEW` replaces the session** rather than extending its history,
  so `canGoBack` stays false and there is nothing to go back to. Use it once to
  start a session and navigate inside the browser after that.
- **`am start` with the URL already showing does not reload**, so a test that
  repeats a page measures nothing. `am force-stop` first.
- **URL normalisation adds entries.** Typing `example.org` produces both
  `https://example.org` and `https://example.org/`.

## Instrumentation that works

A collector over the whole tab, logged on change, is what made all of this
visible:

```kotlin
scope.launch {
    store.flow()
        .map { state ->
            currentTab(state.selectedTabId)?.let { tab ->
                "url=${tab.content.url.take(60)} loading=${tab.content.loading} " +
                    "readerable=${tab.readerState.readerable} active=${tab.readerState.active} " +
                    "chk=${tab.readerState.checkRequired} " +
                    "back=${tab.content.canGoBack} fwd=${tab.content.canGoForward} " +
                    "hist=${tab.content.history.items.size}/${tab.content.history.currentIndex}"
            }
        }
        .distinctUntilChanged()
        .collect { android.util.Log.i("herald-rv", "snap $it") }
}
```

Read with `adb logcat -s herald-rv:I`. A middleware logging every action's class
name is the other half, for the times when the store is not being told something
it should be.

Readability's own arithmetic can be checked outside the phone, which is faster
than another build: load the page in any browser at a mobile viewport and run
`isProbablyReaderable`'s scoring loop from
`assets/extensions/readerview/readability/readability-readerable-0.4.2.js`
inside the AAR, with `visibilityChecker = n => n.clientHeight > 0 && n.clientWidth > 0`.
That is how the 28.5 and 23.5 above were measured, and it is what proved the
pages were articles and herald was asking at the wrong time.

## Where the code is

What survives, after the 2026-08-19 removal:

| | |
|---|---|
| `herald/src/main/java/app/drawbridge/herald/browser/ReaderViewIntegration.kt` | `askWhetherItIsAnArticle`, `recheckWhenPagesFinishLoading`, `LoadSnapshot` |
| `herald/src/test/java/app/drawbridge/herald/browser/ReaderViewIntegrationTest.kt` | the re-check's timing rule, pinned |
| `herald/src/main/java/app/drawbridge/herald/browser/BrowserFragment.kt` | `backHandlers` order — reader view runs before `sessionFeature` |

`onBackPressed`, `leavePageWhenArticleIsBack` and `ArticleReturn` are in the
history rather than the tree; `git log -- herald/.../ReaderViewIntegration.kt`
has them.

## Verified

On the API 36 emulator, and then by reading with it on the Nothing A059 —
which is where all three were reported and where the third was found. What has
*not* been measured is how the delay before reader view appears feels over a
slow connection: the whole mechanism now waits for a page to settle, and every
measurement here was made on a fast one.
