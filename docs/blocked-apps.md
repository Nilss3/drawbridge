# Blocked apps

The sections below name the apps blocked as of policy 26 (177 of them), plus the AI
companion additions of policy 133. `blocked_packages` now carries 436 packages in all, so
this page is a readable subset, not a complete count. Source of truth is `blocked_packages` in
`dist/policy.json`; package ids and reasoning are in [blocklist-notes](blocklist-notes.md).

## Messaging

- WhatsApp
- WhatsApp Business

## Social networks and video

- Facebook
- Facebook Lite
- Facebook Messenger
- Instagram
- Threads
- TikTok
- TikTok (regional build)
- Snapchat
- X
- Grok
- Reddit
- YouTube
- YouTube Music
- YouTube Kids
- YouTube TV
- YouTube for Android TV
- YouTube Studio
- Discord
- Twitch
- Pinterest
- Tumblr
- Telegram
- BeReal
- Lemon8
- Kik
- WeChat
- LINE
- VK

## Added in policy 25 — apps whose sites were already blocked

These are the reconciliation: every one had its domains on `social.txt` or
`ai-companions.txt` while its package was missing, so the app installed, survived
the blocker, and sat there broken. Found by installing LinkedIn, Instants and
TikTok Lite on a Moto G15 and watching nothing happen, then walking the rest of
the lists the same way. Every id was checked against the Belgian Play storefront
before being added.

- LinkedIn — `com.linkedin.android`
- Instants (Meta) — `com.instagram.moonshot`
- TikTok Lite — `com.tiktok.lite.go`
- Imgur — `com.imgur.mobile`
- 9GAG — `com.ninegag.android.app`
- iFunny — `mobi.ifunny`
- Kick — `com.kick.streaming`
- Rumble — `com.rumble.battles`
- Bigo Live — `sg.bigo.live`
- Weibo — `com.sina.weibo`
- Telegram X — `org.thunderdog.challegram`
- Bluesky — `xyz.blueskyweb.app`
- Mastodon — `org.joinmastodon.android`
- Truth Social — `com.truthsocial.android.app`
- Gettr — `com.gettr.gettr`
- Parler — `com.parler.parler`
- Tinder — `com.tinder`
- Bumble — `com.bumble.app`
- Grindr — `com.grindrapp.android`
- Joyland — `com.joyland.ai`

**Patreon is deliberately not blocked**, as app or as site. It was added in
policy 25 for consistency with its domain and removed again in 26: it is how a
great many podcasts, artists and writers are funded, and it is not a feed
anybody scrolls. Its neighbours on that list — OnlyFans and Fansly — stay.

**Three domains still have no package.** Minds, Yubo and Monkey are blocked as
sites only: no id for them could be confirmed on Play, and an unverified package
id is inert while looking like protection — the `anima.ai` lesson. Better an
honest gap than a line that does nothing.

**The reverse direction is not done.** Packages blocked whose *domains* are not
would leave the web version reachable in herald. Establishing that needs a
hand-built app-to-domain mapping; matching on name tokens produces almost
entirely false positives (`com.tencent.mm` is WeChat, `com.ninegag.android.app`
is 9gag.com).

## AI companions and character chatbots

- Character.AI
- Replika
- Kajiwoto
- Talkie
- AI Dungeon
- Chai
- PolyBuzz
- Nomi
- Nomi (current listing)
- Kindroid
- Anima
- Paradot
- EVA AI
- SimSimi
- Linky AI
- Botify AI
- iGirl

## AI companions and character chatbots — added in policy 133

67 more, found by searching Play for companion terms and by following "similar apps" from named seed apps (`tools/app-ratings.py search` and `related`). Every one is rated PEGI 3 or Parental guidance in Belgium, so the rating rule cannot remove it and the blocklist has to. Each listing was checked to be the app it claims to be. Their own websites are on `dist/lists/ai-companions.txt` (36 domains). The package ids are in `blocked_packages` and the candidate review file is [`tools/corpora/ai-companion-candidates.txt`](../tools/corpora/ai-companion-candidates.txt).

- AI Boyfriend (two listings)
- AI Boyfriend 18+
- AI Boyfriend Maker: Moshas
- AI Boyfriend:Love Chat Partner
- AI Character Chat
- AI Chat - Your AI Friend
- AI Girlfriend
- AI Girlfriend - Idolchat
- AI GirlFriend AI Story ChatJoy
- AI Romance Chat: AI Soulmate
- Anime AI Waifu Chat: Chika
- Anna AI: Character Chat & Call
- Aria: AI Girlfriend ChatJoy
- BagelChat: AI Character Story
- Chacha - AI Anime Chat
- Char.ai : Chat & Roleplay
- Charland - Character AI Chat
- Charmsy: AI Chat Text Stories
- CharPal: AI Character Roleplay
- CherryTalk: AI Chat & Story
- DeepLove AI：Dream AI Love Chat
- Dewy: Chat with AI Characters
- DigitalSoul: AI Character Chat
- Everen: AI Boyfriend Chat
- Fandy: Chat with AI Characters
- Friends – Raise AI Companions
- Hangjam: AI Chat and Roleplay
- HisStory: AI Boyfriend Chat
- Imate - AI boyfriend who cares
- Imiss: Sweet AI Girlfriend
- Inlyth: AI Companion Chat
- Intimate - AI Girlfriend
- ISEKAI ZERO: Anime AI Roleplay
- Jack - AI Boyfriend
- JustMet: AI Girlfriend
- Kinda: AI Character Chat
- KUN: Anime AI Boyfriend Chat
- Laixen: Chat with AI Character
- Lizlis - AI Character Chat
- lookatmy.ai: AI Companion
- Lore: Chat With AI Characters
- LoveyDovey - Dream Chats
- Lovie - Chat with AI Character
- Luna: Realistic AI Soulmate
- Lust: AI Girlfriend Simulator
- Maisai：AI虛擬戀人
- Meco - AI Character Chat
- Moescape - Character AI & Art
- OshiKoi - 3D AI Companion
- Paluma: AI Character Chat
- Raki: Chat with AI Characters
- Rochat - AI Character Chat
- Saen-D: AI Companion
- simulate: AI World & Character
- Siya - AI Soulmate
- Soulmate
- Soulmate: AI Dating & Chat
- Talefy: AI Character Roleplay
- Talk AI Character Roleplay
- Tipsy Chat: Live Your Story
- Underfiction: AI Roleplay
- Winkio: Chat With Character
- Your Boyfriend AI Chat
- Your Girlfriend - AI Chat
- YourWaifus: AI Girlfriend
- ZingChat: Charming AI Roleplay

## Games — crime sandbox

- Grand Theft Auto: San Andreas
- Grand Theft Auto: Vice City
- Grand Theft Auto III
- GTA: Chinatown Wars
- Bully: Anniversary Edition
- Gangstar Vegas
- Dude Theft Wars
- Real Gangster Crime
- MadOut 2
- Payback 2

## Games — shooters

- Fortnite
- Epic Games launcher
- Call of Duty: Mobile
- PUBG Mobile
- PUBG Mobile (KR)
- PUBG Mobile (VN)
- BGMI
- Free Fire
- Free Fire MAX
- Standoff 2
- Modern Combat 5
- World War Heroes
- Special Forces Group 2
- Bullet Force
- Critical Ops
- Combat Master
- Arena Breakout
- Warface GO
- Sniper 3D
- Sniper Strike
- Hitman Sniper
- Cover Fire
- Guns of Boom

## Games — zombie and survival

- Dead Trigger 2
- Into the Dead 2
- Dead Target
- Zombie Frontier 3
- Left to Survive
- Dead Effect 2
- Slaughter 2
- Last Day on Earth
- State of Survival
- The Walking Dead: Season One
- The Walking Dead: A New Frontier
- The Walking Dead: No Man's Land
- This War of Mine
- Frostpunk: Beyond the Ice
- Don't Starve: Pocket Edition

## Games — horror

- Five Nights at Freddy's
- Five Nights at Freddy's 4
- Five Nights at Freddy's: Sister Location
- Granny
- Slendrina: The Cellar
- Evil Nun
- Ice Scream 1
- Ice Scream 4
- Poppy Playtime Chapter 1
- Poppy Playtime Chapter 3
- Bendy and the Ink Machine
- Bendy: Lone Wolf
- Baldi's Basics Classic
- Horrorfield
- Identity V
- Suspects: Mystery Mansion

## Games — fighting

- Mortal Kombat
- Shadow Fight 2
- Shadow Fight 3
- Shadow Fight 4: Arena
- WWE Mayhem
- Marvel Contest of Champions

## Games — gacha and loot box

- Genshin Impact
- Honkai: Star Rail
- Fate/Grand Order
- Diablo Immortal
- RAID: Shadow Legends
- Dislyte
- Coin Master
- MONOPOLY GO!

## Games — sports and competitive

- EA SPORTS FC Mobile
- eFootball
- Clash of Clans
- Clash Royale
- Brawl Stars
- Rise of Kingdoms
- Mobile Legends: Bang Bang
- League of Legends: Wild Rift
- Garena AOV

## Games — avatar worlds and open chat

- Roblox
- Rec Room
- VRChat
- Avakin Life
- Hotel Hideaway
- PK XD
- Stumble Guys
- Among Us

## Games — interactive story and life sim

- Episode
- Choices
- Chapters
- BitLife
- The Sims FreePlay
- Life is Strange
- Lifeline

## Games — roguelike and premium ports

- Dead Cells
- Buriedbornes 2
- Slay the Spire

## Games — casual and simulation

- 8 Ball Pool
- Subway Surfers
- Toca Boca World
- SimCity BuildIt
- EVE Echoes

Minecraft is **not** blocked — only Realms and the large public Bedrock servers.
