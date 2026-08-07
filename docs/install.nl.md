# drawbridge installeren met een QR-code

[English](install.md) · **Nederlands** · [Français](install.fr.md)

Dit duurt ongeveer een kwartier. U scant één code, en de telefoon installeert en
configureert daarna alles zelf — zowel de inhoudsfilter als de browser.

---

## Voor u begint

> **Hierdoor wordt de telefoon gewist.** drawbridge kan alleen geïnstalleerd
> worden op een telefoon die net is teruggezet naar de fabrieksinstellingen,
> omdat Android dit niveau van controle enkel toekent voordat er een account op
> staat. Maak eerst een back-up van foto's, berichten en al wat u wilt bewaren.

U hebt verder nodig:

- Een wifinetwerk en het wachtwoord ervan.
- **Het Google-account van de ouder** — het account van het kind mag er nooit op
  komen. Dat is wat verhindert dat de controle wordt verwijderd door de telefoon
  te wissen (zie [Waarom het account belangrijk is](#waarom-het-account-belangrijk-is)).
- Ongeveer 300 MB download, dus gebruik wifi en geen mobiele data.

---

## Stap 1 — Zet de telefoon terug naar fabrieksinstellingen

Op de telefoon die u wilt beheren:

**Instellingen → Systeem → Opties voor resetten → Alle gegevens wissen
(fabrieksinstellingen terugzetten)**

Wacht tot de telefoon opnieuw opstart. U zou op het welkomstscherm moeten
uitkomen.

Is de telefoon gloednieuw en nog nooit ingesteld, sla deze stap dan over.

---

## Stap 2 — Tik zes keer op het welkomstscherm

Tik op dat eerste welkomstscherm **zes keer op dezelfde plek in het midden van
het scherm**.

Bij de eerste tikken lijkt er niets te gebeuren — ga gewoon door. Na de zesde tik
opent de telefoon een QR-scanner. Sommige toestellen vragen eerst om verbinding
met wifi en downloaden de scanner daarna; dat is normaal.

> Gebeurt er niets na zes keer tikken, dan heeft het instelscherm van uw telefoon
> deze functie niet. Zie [Als de QR-scanner niet verschijnt](#als-de-qr-scanner-niet-verschijnt).

---

## Stap 3 — Maak verbinding met wifi

Als dat nog niet gevraagd is, verbind de telefoon nu met uw wifi. Er is internet
nodig om drawbridge te downloaden.

---

## Stap 4 — Scan deze code

<p align="center">
  <img src="img/provisioning-qr.png" alt="drawbridge QR-code voor installatie" width="340">
</p>

Richt de scanner van de telefoon op de code hierboven — vanaf een ander scherm of
vanaf een afdruk.

Om netjes af te drukken, gebruik de vectorversie:
[provisioning-qr.svg](img/provisioning-qr.svg).

De telefoon doet daarna vanzelf het volgende:

1. drawbridge downloaden en installeren.
2. drawbridge eigenaar van het toestel maken, zodat het niet zonder uw sleutel
   verwijderd kan worden.
3. **herald**, de gefilterde browser, downloaden en installeren.
4. De inhoudsfilter inschakelen en elke andere browser verbergen.

Dit duurt enkele minuten, grotendeels het downloaden van de browser. Laat de
telefoon op wifi tot alles klaar is.

---

## Stap 5 — Voeg uw eigen Google-account toe

Wanneer de telefoon bij de gewone instelschermen komt, meld u aan met **uw eigen**
Google-account — nooit dat van het kind.

U kunt het account ook volledig overslaan als u de Play Store niet nodig hebt,
maar dan verliest u de bescherming die hieronder beschreven staat.

---

## Stap 6 — Stel de telefoon in in de drawbridge-app

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

## Stap 7 — Vergrendel, en schrijf de sleutel op

Tik op **drawbridge vergrendelen**. Dat is de enige knop die telt: hij past het
beleid toe, start de inhoudsfilter en verzegelt het scherm. Sta de uitzondering
op batterijoptimalisatie toe wanneer daarom gevraagd wordt.

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

## Waarom het account belangrijk is

Iedereen kan met de aan-uitknop en de volumeknoppen in de herstelmodus geraken en
de telefoon wissen. Geen enkele app kan dat verhinderen, drawbridge evenmin.

Op een door Google gecertificeerde telefoon wist dat soort reset de
fabrieksinstellingsbeveiliging (Factory Reset Protection) **niet**: bij het
opnieuw opstarten vraagt de telefoon om een Google-account dat er eerder op
aangemeld was. Is dat enkel uw account, dan maakt wissen de telefoon onbruikbaar
in plaats van vrij — en dat is precies de bedoeling.

Is het account van het kind er ooit op gezet, al was het maar even, dan kan het
kind die vraag zelf beantwoorden en houdt het een propere telefoon zonder
beperkingen over.

Op telefoons zonder Google-diensten (LineageOS, /e/OS) bestaat die bescherming
niet, en verwijdert een reset via de herstelmodus drawbridge volledig.

---

## Als de QR-scanner niet verschijnt

Sommige toestellen — vooral die met aangepaste software — hebben een instelscherm
zonder de zes-tik-functie. Installeer drawbridge dan via USB vanaf een computer:

```bash
adb install dpc-release.apk
adb shell dpm set-device-owner app.drawbridge.dpc/app.drawbridge.dpc.admin.DrawbridgeDeviceAdminReceiver
```

Voor dat tweede commando mag er **geen enkel account** op de telefoon staan.

---

## Later verwijderen

Open drawbridge, typ uw sleutel in, en kies daarna **⋮ → drawbridge-beperkingen
uitschakelen**. Het staat in het overloopmenu en niet op het scherm zelf: het
gebeurt één keer in het leven van een telefoon.

Alle beperkingen vervallen, verborgen apps komen terug, en **er wordt niets
gewist**. Het is ook **onomkeerbaar**: de beperkingen opnieuw activeren vraagt
een fabrieksreset en een nieuwe drawbridge-installatie. Zie
[verwijderen](removal.md) voor de details.
