# drawbridge installeren via USB

[English](install.md) · **Nederlands** · [Français](install.fr.md)

Dit duurt ongeveer een kwartier. U verbindt de telefoon één keer met een
computer, voert de installer uit, en de telefoon regelt de rest zelf — zowel de
inhoudsfilter als de browser.

**Dit werkt op een gewone Android-telefoon die al in gebruik is.** Er is geen
fabrieksreset nodig, en uw foto's, berichten en apps worden niet gewist.

> **Eén ding verdwijnt wel: de apps die drawbridge blokkeert.** Zodra u
> vergrendelt, worden alle apps die het beleid blokkeert van de telefoon
> verwijderd, meteen. Een instelling achteraf weer aanzetten installeert ze niet
> opnieuw. Op een telefoon die al in gebruik is, is dat een echte verandering —
> bekijk [wat er geblokkeerd wordt](blocked-apps.md) voor u begint.

---

## Voor u begint

U hebt nodig:

- **Een computer en een USB-kabel.**
- Een wifinetwerk en het wachtwoord ervan.
- **Een Google-account, of geen** — zie [stap 4](#stap-4--meld-u-weer-aan-of-niet).
  Wat er nu op de telefoon staat, gaat er in stap 1 af.
- Ongeveer 300 MB download, dus gebruik wifi en geen mobiele data.

---

## Stap 1 — Verwijder elk account van de telefoon

**Instellingen → Wachtwoorden, toegangssleutels en accounts**

Tik op elk account en daarna op **Account verwijderen**. Android geeft dit niveau
van controle enkel aan een telefoon waar geen account op staat — dat is de enige
harde voorwaarde, en het is de enige reden waarom de vorige versie van deze gids
u vroeg de telefoon te wissen. Dat hoeft dus niet. In stap 4 meldt u zich weer
aan.

Een account verwijderen wist de mail, contacten en gesynchroniseerde gegevens van
dat account **van de telefoon**. Er wordt niets uit uw Google-account zelf
verwijderd, en alles komt terug zodra u zich opnieuw aanmeldt.

---

## Stap 2 — Zet USB-foutopsporing aan

**Instellingen → Over de telefoon**, en tik zeven keer op **Buildnummer**. De
telefoon meldt dat u nu ontwikkelaar bent.

Daarna **Instellingen → Systeem → Ontwikkelaarsopties → USB-foutopsporing**, en
zet die aan.

---

## Stap 3 — Voer de installer uit vanaf uw computer

Verbind de telefoon met de USB-kabel. Aanvaard de melding *USB-foutopsporing
toestaan* die op de telefoon verschijnt.

**Het eenvoudigst is de installerpagina op de website**, die dit alles vanuit
Chrome of Edge doet zonder iets op te zetten:
<https://drawbridge-project.pages.dev/nl/install/usb/>.

Wilt u liever een terminal, voer dan vanuit een kopie van deze repository uit:

```bash
tools/provision-adb.sh
```

Dat installeert drawbridge en herald en maakt drawbridge eigenaar van de
telefoon. Het weigert te starten zolang er nog een account op de telefoon staat,
en dat is meestal de reden waarom het stopt.

---

## Stap 4 — Meld u weer aan, of niet

**Instellingen → Wachtwoorden, toegangssleutels en accounts → Account
toevoegen**, als u er een wilt.

**Gebruik een account waarvan het u niet stoort dat het kind het heeft — of
helemaal geen.** Welk account er ook aangemeld is, wie de telefoon vasthoudt kan
uit de Play Store installeren. Aanmelden met *uw eigen* account houdt dus niets
tegen; het doet het omgekeerde, want het zet uw mail, foto's, bestanden en
opgeslagen betaalmiddel op een telefoon die iemand anders draagt.

De telefoon zonder enig account laten is de striktere keuze, en een prima keuze.
Zonder account kan de Play Store niets installeren, en drawbridge zelf heeft er
geen nodig.

Doe dit nu, hoe dan ook. Zodra drawbridge vergrendeld is, zijn accountwijzigingen
afgesloten, en ze weer openen kost u de sleutel.

Zorg ook dat de telefoon op wifi zit: in de volgende stap wordt ongeveer 300 MB
browser gedownload.

---

## Stap 5 — Stel de telefoon in in de drawbridge-app

Open de app **drawbridge**. Alles wat u beslist staat op dat ene scherm.

1. **Kies uw taal** — English, Nederlands of Français. Het staat bovenaan het
   scherm, zodat alles eronder in uw taal staat. De app is volledig in het
   Nederlands vertaald.
2. **Lees het beleid.** Er staat bij voor wie het bedoeld is en wat het precies
   blokkeert. Biedt het document er meer dan één, dan verwijdert overschakelen
   naar een strenger beleid de apps die het niet toelaat, meteen — en
   terugschakelen installeert ze niet opnieuw.
3. **Zet de opties eronder goed.** Elke optie laat één ding extra toe bovenop
   het beleid, met de leeftijd ernaast waarvan men die gewoonlijk geschikt acht
   — *WhatsApp toestaan 14+* bijvoorbeeld.

---

## Stap 6 — Vergrendel, en schrijf de sleutel op

Tik op **drawbridge vergrendelen**. Dat is de enige knop die telt: hij past het
beleid toe, start de inhoudsfilter en verzegelt het scherm. Sta de uitzondering
op batterijoptimalisatie toe wanneer daarom gevraagd wordt.

**Dit is het moment waarop de geblokkeerde apps verwijderd worden.** Was de
telefoon al in gebruik, dan verdwijnen ze nu, en een instelling later weer
aanzetten brengt ze niet terug.

Daarna krijgt u een **sleutel** te zien: twintig tekens in vier groepen, zoals
`4XRZS-7QC9N-SPSH9-AWAAE`.

**Schrijf hem op of druk hem af vóór u dat scherm sluit.** Hij wordt maar één
keer getoond, hij wordt nergens bewaard waar u hem nog kunt lezen, en er is geen
herstel — niet via e-mail, door niemand. Dat is met opzet: herstel via e-mail zou
de telefoon aan een account koppelen, en net dat wil dit project vermijden. De
knop *Sleutel afdrukken of bewaren* geeft hem door aan een afdruk- of
notitie-app; hij wordt bewust niet naar het klembord gezet, want dat kan iedereen
lezen die de telefoon vasthoudt.

Bij **elke vergrendeling wordt een nieuwe sleutel gemaakt**, dus een sleutel
waarvan ooit een foto is genomen werkt de volgende keer niet meer.

> **Bent u de sleutel kwijt, dan komt u alleen nog bij de instellingen door de
> telefoon te wissen.** Bewaar hem op een veilige plaats — een lade, een
> wachtwoordbeheerder, geplakt in een kast.
>
> Hem bewust *niet* bewaren is een echte keuze, en de app laat dat toe: de
> telefoon blijft precies zoals u hem hebt ingesteld, voorgoed, en niemand kan er
> nog iets aan wijzigen — u ook niet.

Om later iets te wijzigen: open drawbridge en typ de sleutel in.

---

## Controleren of het gelukt is

Open drawbridge. Nog voor er iets gevraagd wordt, staat er **hoelang de app deze
telefoon al beschermt** — de datum en het uur waarop u vergrendeld hebt.

Die regel is de goedkoopste controle die er is. Ze overleeft een herstart, en ze
overleeft dat u even ontgrendelt om iets te wijzigen. Enkel het verwijderen van
drawbridge in de app zelf en het wissen van de telefoon zetten ze terug op nul.
Staat er over een halfjaar dus dat de bescherming pas sinds vorige dinsdag loopt,
dan is de telefoon teruggezet en opnieuw ingericht — hoe onschuldig hij er ook
uitziet.

Typ uw sleutel in, en op het scherm erachter zou moeten staan:

- *Beheerd: drawbridge is de eigenaar van het toestel*
- *Inhoudsfilter: actief*
- een versienummer van het beleid en een recent tijdstip van bijwerken

Open daarna **herald** en probeer een geblokkeerde site te bezoeken. U zou een
scherm **Page blocked** ("Pagina geblokkeerd") moeten krijgen in plaats van de site.

U ziet ook een klein sleutelpictogram in de statusbalk. Zo toont Android dat de
filter actief is; die kan niet uitgeschakeld worden.

---

## Een fabrieksreset verwijdert drawbridge, en niets houdt dat tegen

Iedereen kan met de aan-uitknop en de volumeknoppen in de herstelmodus geraken en
de telefoon wissen, of dat vanuit Instellingen doen als hij de
schermvergrendeling kent. Geen enkele app kan dat verhinderen, drawbridge
evenmin.

**Factory Reset Protection dekt dit niet, wat u er ook over leest** — ook niet
wat in eerdere versies van deze gids stond. Op een volledig beheerde telefoon
staat het standaard niet aan, en een reset vanuit Instellingen zet het niet in
werking, welke accounts er ook op staan. Dit is op 10 augustus 2026 op echte
hardware getest: de telefoon werd gereset en de installatie vroeg nooit om het
Google-account. Vertrouw er niet op.

Wat u wel krijgt, is **weten dát het gebeurd is**. drawbridge zet de datum
waarop het vergrendeld werd op het vergrendelscherm en in de app. Een telefoon
die gewist en opnieuw ingesteld is, toont geen datum meer die u herkent — de
goedkoopste controle op knoeien die er is, zolang u weet wat de telefoon hoort te
zeggen.

Houd het account van het kind er sowieso af. Het kost niets en het sluit de
makkelijkste weg naar een Play Store die niet de uwe is.

---

## Over de kabel

**Bij het vergrendelen wordt USB-foutopsporing uitgeschakeld.** Ze komt terug
zodra u drawbridge met uw sleutel ontgrendelt — zo zet u later een nieuwere
versie via dezelfde kabel op de telefoon. De kabel is dus geen eenmalige kans,
maar hij blijft dicht tot u de sleutel opnieuw bij de hand hebt.

De technische uitleg staat in [provisioning.md](provisioning.md).

---

## Later verwijderen

Open drawbridge, typ uw sleutel in, en kies daarna **⋮ → drawbridge-beperkingen
uitschakelen**. Het staat in het overloopmenu en niet op het scherm zelf: het
gebeurt één keer in het leven van een telefoon.

Alle beperkingen vervallen, verborgen apps komen terug, en **er wordt niets
gewist**. Vanaf de telefoon zelf kunt u ze niet opnieuw inschakelen — dat vraagt
weer de kabel, vanaf stap 1 — maar een fabrieksreset is niet nodig. Zie
[verwijderen](removal.md).
