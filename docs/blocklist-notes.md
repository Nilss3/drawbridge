# Blocklist notes

What the current policy blocks, and — more usefully — where a requested item
could not be blocked exactly as asked.

Package IDs were verified against the Play Store rather than written from
memory; a wrong package ID is invisible, because the app blocker simply never
matches it.

## Requested items that are not a one-to-one match

### YouTube Shorts

**Cannot be blocked separately.** Shorts is a surface inside YouTube, served from
the same app and the same domains. Blocking YouTube blocks Shorts. There is no
package or hostname that is Shorts and not YouTube.

**All of YouTube is blocked**, at the owner's explicit request: the main app,
Kids, TV, Music, Studio, the Android TV build, `youtu.be` links, embeds via
`youtube-nocookie.com`, and the `googlevideo.com` video CDN. Because matching is
suffix-based, `youtube.com` also covers `m.`, `music.`, `tv.`, `kids.` and
`studio.` without listing them.

The safe-search redirect in `DnsFilter` still maps `youtube.com` to
`restrictmoderate.youtube.com`, but the block check runs first, so it is dormant.
It is left in place deliberately: remove YouTube from `dist/lists/social.txt` and
restricted mode resumes with no code change — the obvious thing to want for an
older child.

### Snapchat My AI

**Cannot be blocked separately.** My AI is a bot inside Snapchat, reachable only
through the app and its own domains. Blocking Snapchat covers it. There is no way
to keep Snapchat while removing My AI from outside the app.

### Grok

**Blocked as a standalone app and site; inside X it was already covered.** Grok
lives in two places. The standalone app (`ai.x.grok`) and its own sites
(`grok.com`, `x.ai`) are now blocked directly. The version built into X needs
nothing new: `com.twitter.android` has always been on the package list and
`x.com` on the social list, so the whole surface goes with them.

It sits on the AI-companion list rather than being left to the "general-purpose
assistants are not blocked" rule, and that is a judgement rather than a
technicality — Grok ships a companion mode with deliberately unfiltered personas,
which is the thing that list exists for. ChatGPT, Claude and Gemini stay
unblocked; if you want them gone, `blocked_domains` is where they go.

### Minecraft: Bedrock servers

**Partially blocked, and this one deserves attention.** Minecraft itself is left
alone — the package `com.mojang.minecraftpe` is not blocked and `minecraft.net`
is not in any list — so single-player and LAN work normally.

`dist/lists/games.txt` blocks Realms and the large public Bedrock servers
(Hive, CubeCraft, Mineplex, Lifeboat, Galaxite, NetherGames, InPvP, Mineville).

It cannot block:

- **servers joined by raw IP address**, which bypass DNS entirely;
- **the long tail of small servers**, which cannot be enumerated.

So this is a speed bump, not a wall. If multiplayer needs to be genuinely off,
block the Minecraft package itself and accept losing single-player with it.

### GTA Online

**Not applicable on Android.** GTA Online is PC and console only; there is no
Android build to block. `rockstargames.com` and the Social Club domain are in
`games.txt`, which stops the web account and companion pages, but nothing on the
phone was ever going to run it.

## Deliberate omissions

- **Minecraft** — see above.
- **WhatsApp** is blocked, and can be allowed again with the "Allow WhatsApp
  (14+)" option on drawbridge's configuration screen. It is the one thing on this
  list a parent can switch back on without editing the policy.
- **Signal** is deliberately left alone, and now explicitly so: it is in
  `exempt_packages` and `signal.org` is in `allowed_domains`, so no upstream
  blocklist can quietly start blocking it.

## Packages not on the Play Store

Three entries could not be confirmed by a Play Store lookup, and are included
deliberately:

| Package | Why |
|---|---|
| `com.epicgames.fortnite`, `com.epicgames.portal` | Fortnite has never been on Play; it is sideloaded through the Epic Games app. Both are blocked so neither the launcher nor the game can install. |
| `com.ss.android.ugc.trill` | A second TikTok build used in some regions. |
| `com.AgainstGravity.RecRoom` | Rec Room's Play listing does not resolve from here; the ID is the one its store URL uses. Worth re-checking if Rec Room ever appears on the device. |

A package ID that turns out to be wrong costs nothing except that the app is not
blocked — the blocker matches on exact package name and ignores everything else.

## Two layers, and what each one catches

Blocking a service well means covering both:

- **`blocked_packages`** stops the *app* — removed within about a second of
  finishing installation, silently, with no prompt.
- **The domain lists** stop the *website*, in herald and in any other app's
  embedded WebView, via DNS.

An app blocked by package can still be reached at its website unless the domain
is listed too, which is why the social, AI-companion and games lists mirror the
package list rather than duplicating it by accident.
