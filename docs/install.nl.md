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
2. drawbridge eigenaar van het toestel maken, zodat het niet zonder uw pincode
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

## Stap 6 — Rond de installatie af in de drawbridge-app

Open de app **drawbridge** en tik op **Set up parental controls** (*Ouderlijk toezicht instellen*).

> De app zelf is voorlopig enkel in het Engels; de knopteksten hieronder staan
> daarom in het Engels, met de vertaling ernaast.

1. **Kies een pincode** van minstens zes cijfers. U hebt die nodig om het toezicht
   te wijzigen of te verwijderen.
2. **Schrijf de herstelcode op.** Die wordt maar één keer getoond. Er is geen
   herstel via e-mail — dat zou de telefoon aan een account koppelen, en net dat
   wil dit project vermijden.
3. Sta de uitzondering op batterijoptimalisatie toe wanneer daarom gevraagd wordt.

> **Bent u zowel de pincode als de herstelcode kwijt, dan kunt u drawbridge enkel
> verwijderen door de telefoon opnieuw te wissen.** Bewaar de code op een veilige
> plaats — een lade, een wachtwoordbeheerder, geplakt in een kast.

---

## Controleren of het gelukt is

Open de drawbridge-app. Er zou moeten staan:

- *Managed: drawbridge is the device owner* — beheerd, drawbridge is eigenaar
- *Content filter: running* — de inhoudsfilter draait
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

Open drawbridge → **Remove parental controls** (*Ouderlijk toezicht verwijderen*) → geef uw pincode of de
herstelcode in.

Alle beperkingen vervallen, verborgen apps komen terug, en **er wordt niets
gewist**. Zie [verwijderen](removal.md) voor de details.
