# Installer drawbridge par USB

[English](install.md) · [Nederlands](install.nl.md) · **Français**

Comptez environ un quart d'heure. Vous reliez le téléphone à un ordinateur une
seule fois, vous lancez l'installeur, et le téléphone configure le reste tout
seul — le filtre de contenu comme le navigateur.

**Cela fonctionne sur un téléphone Android ordinaire déjà utilisé.** Aucune
réinitialisation d'usine n'est nécessaire, et vos photos, messages et
applications ne sont pas effacés.

> **Une chose disparaît quand même : les applications que drawbridge bloque.**
> Dès que vous verrouillez, toutes les applications bloquées par la politique
> sont désinstallées du téléphone, immédiatement. Réactiver un réglage ensuite ne
> les réinstalle pas. Sur un téléphone déjà utilisé, c'est un vrai changement —
> regardez [ce qui est bloqué](blocked-apps.md) avant de commencer.

---

## Avant de commencer

Il vous faudra :

- **Un ordinateur et un câble USB.**
- Un réseau Wi-Fi et son mot de passe.
- **Un compte Google, ou aucun** — voir
  [l'étape 4](#étape-4--reconnectez-vous-ou-non). Ce qui se trouve sur le
  téléphone aujourd'hui en sortira à l'étape 1.
- Environ 300 Mo de téléchargement : utilisez le Wi-Fi plutôt que les données
  mobiles.

---

## Étape 1 — Retirez tous les comptes du téléphone

**Paramètres → Mots de passe, clés d'accès et comptes**

Touchez chaque compte, puis **Supprimer le compte**. Android n'accorde ce niveau
de contrôle qu'à un téléphone ne portant aucun compte — c'est la seule condition
stricte, et c'est la seule raison pour laquelle la version précédente de ce guide
vous demandait d'effacer le téléphone. Ce n'est pas nécessaire. Vous vous
reconnecterez à l'étape 4.

Supprimer un compte efface les courriers, contacts et données synchronisées de ce
compte **du téléphone**. Rien n'est supprimé de votre compte Google lui-même, et
tout revient dès que vous vous reconnectez.

---

## Étape 2 — Activez le débogage USB

**Paramètres → À propos du téléphone**, puis touchez sept fois **Numéro de
build**. Le téléphone vous annonce que vous êtes désormais développeur.

Ensuite **Paramètres → Système → Options pour développeurs → Débogage USB**, et
activez-le.

---

## Étape 3 — Lancez l'installeur depuis votre ordinateur

Reliez le téléphone avec le câble USB. Acceptez l'invite *Autoriser le débogage
USB* qui s'affiche sur le téléphone.

**Le plus simple est la page d'installation du site**, qui fait tout cela depuis
Chrome ou Edge sans rien à mettre en place :
<https://drawbridge-project.pages.dev/fr/install/usb/>.

Si vous préférez un terminal, depuis une copie de ce dépôt :

```bash
tools/provision-adb.sh
```

Cela installe drawbridge et herald, et fait de drawbridge le propriétaire du
téléphone. La commande refuse de démarrer tant qu'un compte se trouve encore sur
le téléphone, ce qui en est la raison d'arrêt habituelle.

---

## Étape 4 — Reconnectez-vous, ou non

**Paramètres → Mots de passe, clés d'accès et comptes → Ajouter un compte**, si
vous en voulez un.

**Utilisez un compte que cela ne vous dérange pas de laisser à l'enfant — ou
aucun.** Quel que soit le compte connecté, celui qui tient le téléphone peut
installer depuis le Play Store : vous connecter avec *votre* compte ne lui
retire donc rien. Cela fait l'inverse, en plaçant vos courriers, vos photos, vos
fichiers et votre moyen de paiement enregistré sur un téléphone porté par
quelqu'un d'autre.

Laisser le téléphone sans aucun compte est l'option la plus stricte, et une très
bonne option : sans compte, le Play Store ne peut rien installer, et drawbridge
lui-même n'en a pas besoin.

Vous pourrez aussi ajouter ou retirer des comptes plus tard, verrouillé ou non :
drawbridge ne l'empêche pas. Ce qui reste bloqué définitivement, c'est l'ajout
d'un second *utilisateur* au téléphone, qui disposerait sinon de son propre accès
internet non filtré.

Assurez-vous également que le téléphone est en Wi-Fi : l'étape suivante télécharge
environ 300 Mo de navigateur.

---

## Étape 5 — Transférez vos favoris vers herald

**herald s'installe juste après drawbridge**, sur le même Wi-Fi, et il est donc
là avant que vous verrouilliez. Laissez-lui quelques minutes lors d'une première
installation.

Faites-le maintenant : c'est le verrouillage qui supprime les autres
navigateurs — et leurs favoris partent avec eux.

1. Dans le navigateur que vous quittez, exportez les favoris vers un fichier
   HTML. Dans Chrome : ⋮ → Favoris → Gestionnaire de favoris → ⋮ → Exporter les
   favoris.
2. Ouvrez **herald** → ⋮ → Favoris → ⋮ → Importer, et choisissez ce fichier.

herald lit le même format que celui écrit par Chrome et Firefox. Ce qu'il ne peut
pas rendre sûr — les entrées `javascript:`, par exemple — est écarté plutôt
qu'importé.

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

**C'est le moment où les applications bloquées sont désinstallées.** Si le
téléphone était déjà utilisé, elles disparaissent maintenant, et réactiver un
réglage plus tard ne les ramène pas.

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

## Une réinitialisation supprime drawbridge, et rien ne l'empêche

N'importe qui peut maintenir les boutons d'alimentation et de volume pour
atteindre le mode de récupération et effacer le téléphone. Aucune application ne
peut l'empêcher, drawbridge pas davantage.

**La protection de réinitialisation (Factory Reset Protection) ne couvre pas
cela, quoi que vous puissiez lire** — y compris dans les versions précédentes de
ce guide. Sur un téléphone entièrement géré, elle n'est pas activée par défaut,
et une réinitialisation depuis les Paramètres ne la déclenche pas, quels que
soient les comptes présents. Cela a été testé sur du matériel réel le 10 août
2026 : le téléphone a été réinitialisé et la configuration n'a jamais demandé le
compte Google. Ne comptez pas dessus.

Ce que vous obtenez à la place, c'est **de le savoir**. drawbridge inscrit la
date de son verrouillage sur l'écran de verrouillage et dans l'application. Un
téléphone effacé puis reconfiguré cesse d'afficher une date que vous reconnaissez
— le contrôle d'altération le moins coûteux qui soit, pour autant que vous
sachiez ce que le téléphone est censé afficher.

Gardez de toute façon le compte de l'enfant hors du téléphone. Cela ne coûte rien
et cela ferme le chemin le plus simple vers un Play Store qui n'est pas le vôtre.

---

## À propos du câble

**Le verrouillage désactive le débogage USB.** Il revient dès que vous
déverrouillez drawbridge avec votre clé : c'est ainsi que vous installerez plus
tard une version plus récente par le même câble. Le câble n'est donc pas une
occasion unique, mais il reste fermé jusqu'à ce que vous ayez de nouveau la clé
en main.

Le détail technique se trouve dans [provisioning.md](provisioning.md).

---

## Le supprimer plus tard

Ouvrez drawbridge, saisissez votre clé, puis choisissez **⋮ → Désactiver les
restrictions drawbridge**. C'est dans le menu de débordement plutôt que sur
l'écran : cela n'arrive qu'une fois dans la vie d'un téléphone.

Toutes les restrictions sont levées, les applications masquées réapparaissent, et
**rien n'est effacé**. Vous ne pouvez pas les réactiver depuis le téléphone
lui-même — cela suppose de reprendre le câble, à partir de l'étape 1 — mais
aucune réinitialisation d'usine n'est nécessaire. Voir
[suppression](removal.md).
