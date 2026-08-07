# Installer drawbridge avec un code QR

[English](install.md) · [Nederlands](install.nl.md) · **Français**

Comptez environ un quart d'heure. Vous scannez un seul code, et le téléphone
installe et configure ensuite tout seul l'ensemble — le filtre de contenu comme
le navigateur.

---

## Avant de commencer

> **Cette opération efface le téléphone.** drawbridge ne peut être installé que
> sur un téléphone qui vient d'être réinitialisé, car Android n'accorde ce niveau
> de contrôle qu'avant l'ajout du moindre compte. Sauvegardez d'abord les photos,
> les messages et tout ce que vous souhaitez conserver.

Il vous faudra également :

- Un réseau Wi-Fi et son mot de passe.
- **Le compte Google du parent** — celui de l'enfant ne doit jamais être ajouté.
  C'est ce qui empêche de supprimer le contrôle en effaçant le téléphone (voir
  [Pourquoi le compte est important](#pourquoi-le-compte-est-important)).
- Environ 300 Mo de téléchargement : utilisez le Wi-Fi plutôt que les données
  mobiles.

---

## Étape 1 — Réinitialiser le téléphone

Sur le téléphone que vous allez gérer :

**Paramètres → Système → Options de réinitialisation → Effacer toutes les
données (réinitialisation aux paramètres d'usine)**

Attendez le redémarrage. Vous devriez arriver sur l'écran de bienvenue.

Si le téléphone est neuf et n'a jamais été configuré, passez cette étape.

---

## Étape 2 — Appuyez six fois sur l'écran de bienvenue

Sur ce premier écran de bienvenue, **appuyez six fois au même endroit, au milieu
de l'écran**.

Rien ne semble se produire lors des premières pressions — continuez. Après la
sixième, le téléphone ouvre un lecteur de codes QR. Certains appareils demandent
d'abord de se connecter au Wi-Fi puis téléchargent le lecteur ; c'est normal.

> Si appuyer six fois ne donne rien, l'écran de configuration de votre téléphone
> ne propose pas cette fonction. Voir
> [Si le lecteur de code QR n'apparaît pas](#si-le-lecteur-de-code-qr-napparaît-pas).

---

## Étape 3 — Se connecter au Wi-Fi

Si cela ne vous a pas déjà été demandé, connectez maintenant le téléphone à votre
Wi-Fi. Une connexion internet est nécessaire pour télécharger drawbridge.

---

## Étape 4 — Scannez ce code

<p align="center">
  <img src="img/provisioning-qr.png" alt="Code QR d'installation de drawbridge" width="340">
</p>

Dirigez le lecteur du téléphone vers le code ci-dessus — depuis un autre écran ou
depuis une impression.

Pour une impression nette, utilisez la version vectorielle :
[provisioning-qr.svg](img/provisioning-qr.svg).

Le téléphone effectue ensuite tout seul les opérations suivantes :

1. Télécharger et installer drawbridge.
2. Faire de drawbridge le propriétaire de l'appareil, pour qu'il ne puisse pas
   être supprimé sans votre clé.
3. Télécharger et installer **herald**, le navigateur filtré.
4. Activer le filtre de contenu et masquer tous les autres navigateurs.

Cela prend quelques minutes, principalement le téléchargement du navigateur.
Laissez le téléphone en Wi-Fi jusqu'à la fin.

---

## Étape 5 — Ajoutez votre propre compte Google

Lorsque le téléphone arrive aux écrans de configuration habituels, connectez-vous
avec **votre propre** compte Google — jamais celui de l'enfant.

Vous pouvez aussi ignorer complètement le compte si vous n'avez pas besoin du
Play Store, mais vous perdez alors la protection décrite ci-dessous.

---

## Étape 6 — Configurez le téléphone dans l'application drawbridge

Ouvrez l'application **drawbridge**. Tout ce que vous décidez tient sur cet écran.

1. **Choisissez votre langue** — English, Nederlands ou Français. C'est la
   première chose à l'écran, pour que tout ce qui suit soit dans votre langue.
   L'application est entièrement traduite en français.
2. **Lisez la politique.** Elle indique à qui elle s'adresse et ce qu'elle
   bloque réellement. Si le document en propose plusieurs, passer à une
   politique plus stricte désinstalle immédiatement les applications qu'elle
   n'autorise pas, et revenir en arrière ne les réinstalle pas.
3. **Réglez les options en dessous.** Chacune autorise une chose de plus
   par-dessus la politique, avec à côté l'âge à partir duquel on la juge
   habituellement adaptée — *Autoriser WhatsApp 14+*, par exemple.

---

## Étape 7 — Verrouillez, et notez la clé

Appuyez sur **Verrouiller drawbridge**. C'est le seul bouton qui compte : il
applique la politique, démarre le filtre de contenu et scelle l'écran. Acceptez
l'exemption d'optimisation de la batterie lorsqu'elle est proposée.

Une **clé** vous est ensuite montrée : vingt caractères en quatre groupes, comme
`4XRZS-7QC9N-SPSH9-AWAAE`.

**Notez-la ou imprimez-la avant de fermer cet écran.** Elle n'est affichée
qu'une seule fois, elle n'est stockée nulle part où vous pourriez la relire, et
il n'existe aucune réinitialisation — ni par e-mail, ni par personne. C'est
délibéré : une réinitialisation par e-mail lierait le téléphone à un compte, ce
que ce projet cherche précisément à éviter. Le bouton *Imprimer ou enregistrer la
clé* la transmet à une application d'impression ou de notes ; elle n'est
volontairement pas proposée au presse-papiers, que peut lire quiconque tient le
téléphone.

**Une nouvelle clé est créée à chaque verrouillage**, donc une clé dont on a pris
une photo cesse de fonctionner au verrouillage suivant.

> **Si vous perdez la clé, le seul moyen de revenir aux réglages est d'effacer le
> téléphone.** Rangez-la en lieu sûr — un tiroir, un gestionnaire de mots de
> passe, collée dans une armoire.
>
> Choisir délibérément de *ne pas* la garder est une option légitime, et
> l'application le permet : le téléphone reste exactement tel que vous l'avez
> configuré, définitivement, et plus personne ne pourra le modifier — vous non
> plus.

Pour changer quoi que ce soit plus tard, ouvrez drawbridge et saisissez la clé.

---

## Vérifier que tout fonctionne

Ouvrez drawbridge. Avant même de vous demander quoi que ce soit, l'application
indique **depuis quand elle protège ce téléphone** — la date et l'heure du
verrouillage.

Cette ligne est la vérification la moins coûteuse qui soit. Elle survit à un
redémarrage, et elle survit à un déverrouillage pour changer un réglage. Seuls le
retrait de drawbridge depuis l'application et l'effacement du téléphone la
remettent à zéro. Si dans six mois elle annonce une protection qui ne date que de
mardi dernier, le téléphone a été réinitialisé puis reconfiguré — quelle que soit
son apparence.

Saisissez votre clé, et l'écran derrière devrait indiquer :

- *Géré : drawbridge est propriétaire de l'appareil*
- *Filtre de contenu : actif*
- une version de la politique et une mise à jour récente

Ouvrez ensuite **herald** et essayez de visiter un site bloqué. Vous devriez
obtenir un écran **Page blocked** (« Page bloquée ») au lieu du site.

Une petite icône de clé apparaît également dans la barre d'état. C'est Android
qui signale que le filtre est actif ; il ne peut pas être désactivé.

---

## Pourquoi le compte est important

N'importe qui peut maintenir les boutons d'alimentation et de volume pour
atteindre le mode de récupération et effacer le téléphone. Aucune application ne
peut l'empêcher, drawbridge pas davantage.

Sur un téléphone certifié par Google, ce type d'effacement ne supprime **pas** la
protection de réinitialisation (Factory Reset Protection) : au redémarrage, le
téléphone exige un compte Google qui y était déjà connecté. Si ce n'est jamais
que le vôtre, effacer le téléphone le rend inutilisable plutôt que libre — et
c'est bien l'objectif.

Si le compte de l'enfant y a été ajouté, ne fût-ce qu'un instant, il peut
répondre lui-même à cette demande et se retrouver avec un téléphone propre et
sans restriction.

Sur les téléphones sans services Google (LineageOS, /e/OS), cette protection
n'existe pas, et un effacement en mode de récupération supprime complètement
drawbridge.

---

## Si le lecteur de code QR n'apparaît pas

Certains appareils — surtout ceux dotés d'un système modifié — ont un écran de
configuration sans la fonction des six pressions. Installez alors drawbridge par
USB depuis un ordinateur :

```bash
adb install dpc-release.apk
adb shell dpm set-device-owner app.drawbridge.dpc/app.drawbridge.dpc.admin.DrawbridgeDeviceAdminReceiver
```

Pour que la seconde commande fonctionne, le téléphone ne doit contenir **aucun
compte**.

---

## Le supprimer plus tard

Ouvrez drawbridge, saisissez votre clé, puis choisissez **⋮ → Désactiver les
restrictions drawbridge**. C'est dans le menu de débordement plutôt que sur
l'écran : cela n'arrive qu'une fois dans la vie d'un téléphone.

Toutes les restrictions sont levées, les applications masquées réapparaissent, et
**rien n'est effacé**. C'est aussi **sans retour** : réactiver les restrictions
suppose une réinitialisation d'usine et une nouvelle installation de drawbridge.
Voir [suppression](removal.md) pour les détails.
