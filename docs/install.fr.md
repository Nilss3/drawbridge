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
   être supprimé sans votre code PIN.
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

## Étape 6 — Terminez la configuration dans l'application drawbridge

Ouvrez l'application **drawbridge** et appuyez sur **Set up parental controls**
(*Configurer le contrôle parental*).

> L'application n'existe pour l'instant qu'en anglais ; les libellés des boutons
> sont donc donnés en anglais, avec la traduction à côté.

1. **Choisissez un code PIN** d'au moins six chiffres. Il vous servira à modifier
   ou à supprimer le contrôle.
2. **Notez le code de récupération.** Il n'est affiché qu'une seule fois. Il n'y a
   pas de réinitialisation par e-mail : cela lierait le téléphone à un compte, ce
   que ce projet cherche précisément à éviter.
3. Acceptez l'exemption d'optimisation de la batterie lorsqu'elle est proposée.

> **Si vous perdez à la fois le code PIN et le code de récupération, le seul moyen
> de supprimer drawbridge est d'effacer à nouveau le téléphone.** Rangez le code
> en lieu sûr — un tiroir, un gestionnaire de mots de passe, collé dans une
> armoire.

---

## Vérifier que tout fonctionne

Ouvrez l'application drawbridge. Elle devrait indiquer :

- *Managed: drawbridge is the device owner* — géré, drawbridge est propriétaire
- *Content filter: running* — le filtre de contenu tourne
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

Ouvrez drawbridge → **Remove parental controls**
(*Supprimer le contrôle parental*) → saisissez votre code PIN
ou le code de récupération.

Toutes les restrictions sont levées, les applications masquées réapparaissent, et
**rien n'est effacé**. Voir [suppression](removal.md) pour les détails.
