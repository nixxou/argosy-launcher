# Session sauvegardes du 2026-09-07/08 — ce qu'on a fait, comment, et ce qu'on a trouvé

> Compte rendu d'une session de dépannage en direct sur les sauvegardes LiteBox ↔ Argosy, menée sur une
> tablette réelle (Lenovo TB128FU, Android 13) branchée en ADB sans fil pendant que le jeu tournait.
> Écrit après coup, à partir des journaux de la session elle-même.
>
> Documents voisins : `docs/litebox-fork.md` (ce que le fork ajoute), `docs/save-history-tree-draft.md`
> (le pointeur vers le brouillon de refonte en graphe, qui découle directement de cette session),
> `LbApiHost/docs/saves.md` (le modèle de sauvegardes côté LiteBox, [SOURCE]/[MESURÉ]).

---

## 1. La méthode : monitorer Argosy en direct

C'est ce qui a permis de trouver les défauts ci-dessous ; sans ça, on tournait en rond sur des
suppositions. Reproductible tel quel.

### 1.1 Appairage ADB sans fil

Le débogage USB classique ne s'applique pas ici — la tablette est utilisée en main, à distance du PC.
Android 11+ demande un appairage explicite avant toute connexion :

1. Sur la tablette : *Options pour les développeurs* → **Débogage sans fil** → activer.
2. Appuyer sur le **texte** « Débogage sans fil » (pas seulement l'interrupteur) pour ouvrir la page, qui
   affiche l'**adresse IP et port** de connexion, du type `192.168.1.118:4xxxx`.
3. Dans cette page, **Associer l'appareil avec un code** ouvre une fenêtre avec un **code à 6 chiffres**
   et une **autre** adresse `192.168.1.118:3xxxx` — le port d'appairage est différent du port de
   connexion, c'est le piège principal.
4. Côté PC (le SDK est dans `G:\android-dev\sdk\platform-tools`) :

   ```
   adb pair 192.168.1.118:<port_appairage> <code>
   adb connect 192.168.1.118:<port_connexion>
   ```

L'appairage ne sert qu'une fois ; la connexion, elle, survit tant que le débogage sans fil reste activé
(elle a tenu plusieurs heures et une pause complète pendant cette session). Les deux ports changent à
chaque réactivation du débogage sans fil.

**Découverte automatique** : `adb mdns services` n'a rien renvoyé ici (mDNS bloqué par le pare-feu
Windows) — ne pas compter dessus, demander les ports affichés à l'écran.

**Scan de ports en dernier recours** : un balayage TCP de la tablette (plages 30000-50000 et 5000-6000)
a bien trouvé le port de connexion ouvert, mais **pas** le port d'appairage, qui n'existe que le temps
où la fenêtre du code est ouverte. Sans le code affiché, la connexion échoue : `adb connect` seul sur un
appareil jamais appairé ne suffit pas. Le port ADB historique (5555) est fermé sur ce genre d'appareil.

### 1.2 Suivre les journaux en direct

Filtrer **par tag** plutôt que par PID : le PID change à chaque réinstallation de l'APK, et une session
de dépannage en enchaîne beaucoup. Par tag, le flux survit aux redémarrages de l'appli.

```
adb -s 192.168.1.118:<port> logcat -v time \
  SaveDebugLogger:D SaveStateManager:D SaveCacheManager:D StateCacheManager:D \
  LiteBoxService:D VersionPickerViewModel:D RomMLibrarySyncService:D \
  RestoreCachedSaveUseCase:D GameSessionService:W PlaySessionTracker:W AndroidRuntime:E '*:S'
```

Le `'*:S'` final coupe tout le reste (sinon le bruit système noie les lignes utiles). Filtrer ensuite les
événements trop fréquents pour rester lisible (`UNIFIED_BUILT`, `RESTORE_VERIFY`, `userActivity`…).

Attention au tampon historique : `logcat` rejoue d'abord ce qui est déjà en mémoire — le premier
« plantage » vu au démarrage pouvait dater de plusieurs jours et concerner une autre appli
(l'installateur système, dans notre cas).

### 1.3 Capture d'écran

```
adb -s 192.168.1.118:<port> exec-out screencap -p > sortie.png
```

Utile pour lever une ambiguïté que le journal ne tranche pas — par exemple distinguer l'écran natif de
sélection de sauvegarde du jeu SNES (« GAME SELECT / NEW GAME / GAME FILE ») de l'écran de gestion des
sauvegardes d'Argosy, qui n'ont rien à voir.

### 1.4 Installer un correctif sans quitter la tablette

```
adb -s 192.168.1.118:<port> install -r <apk>
```

L'APK universel de debug fait ~78 Mo, trop pour passer par une messagerie ; l'installation ADB évite le
transfert manuel. **Effet de bord mesuré** : la réinstallation tue l'appli, et si une synchro était en
cours, elle est interrompue — c'est comme ça qu'un envoi de sauvegarde en vol a été perdu au milieu de
la session, avant d'être retrouvé et restauré ensuite.

### 1.5 Vérifier l'état réel plutôt que ce que le client raconte

Trois sondes qui ont chacune tranché une question que le journal client seul laissait ouverte :

- **Le fichier sur disque** : `adb shell run-as com.nendo.argosy.debug md5sum 'files/…/jeu.srm'` et
  `stat -c '%s %y'` — c'est ce qui a prouvé qu'un fichier live avait été ramené en arrière, contre ce
  que le dernier événement du journal laissait croire.
- **Le journal de requêtes du serveur** : `G:\LB1326\Core\litebox\romm-requests.log` (option
  `[RommServer] LogRequests`) — il a montré que le serveur envoyait bien le nouveau contenu à chaque
  appel, ce qui a déplacé le diagnostic du serveur vers la fusion côté client.
- **Le hash du binaire déployé** : comparer `LiteBox.dll` entre l'installation sœur (celle qui a passé
  les tests) et `G:\LB1326` après chaque déploiement, plutôt que de croire le « OK » du script.

---

## 2. Ce qui a été construit : le journal détaillé des restaurations

Ajouté à `SaveDebugLogger` (option *Save Debug Logging*, puis, depuis, sous-option *Detailed Restore
Logging* — voir §5) : chaque chemin de restauration écrit désormais ce qu'il lit et ce qu'il écrit, avec
chemin complet, taille, md5 et date de modification.

| Événement | Ce qu'il répond |
|---|---|
| `RESTORE_SAVE_BEGIN` / `_DONE` / `_FAILED` | restauration manuelle : source choisie, cible, hash avant/après, `changed`, ce qui devient actif |
| `CACHE_RESTORE_FILES` | quel fichier de cache a été copié vers quelle cible, avec le hash attendu |
| `SAVE_LANDED` | une sauvegarde serveur posée sur le disque, via réseau ou via cache |
| `RESUME_STATE_GUARD` | la décision du garde-fou anti-état obsolète **et la raison d'un saut** |
| `LIVE_STATE_DELETED` / `_SWEEP` | quels `.state.auto` / `.state.resume` ont été supprimés, dans quels dossiers |
| `STATE_RESTORE_BEGIN` / `STATE_RESTORED` / `_FAILED` | état du cache → emplacement émulateur, avec la vérification de version du cœur |
| `STATE_CACHED` / `STATE_DOWNLOADED` | état live → cache, et serveur → cache |
| `BUILTIN_SRAM_RESTORE` / `_WRITE` | quelle source SRAM au lancement (cache actif, cache le plus récent, `.srm` sur disque, départ à neuf), octets, md5, cible |
| `BUILTIN_STATE_LOAD` / `_SAVE` | slot, fichier, octets, md5, accepté ou refusé par le cœur |
| `BUILTIN_AUTO_RESTORE` | chaque décision du lancement : resume chargé, auto chargé, obsolète et effacé, sauté et pourquoi |

Les hash ne sont calculés que si l'option est active.

---

## 3. Les défauts trouvés, et comment

Ordre chronologique de découverte. Tous sont corrigés et déployés, sauf le dernier.

### 3.1 Le code affiché n'était pas celui du bureau

**Symptôme** : demande d'afficher, sous la date de chaque sauvegarde, « le même code que sur mes game
saves dans LiteBox » — supposé être un CRC32.

**Trouvé en lisant** `EditGameWindowSaves.cs` : ce n'est pas un CRC32 mais le **MD5 tronqué aux 8
premiers caractères hexadécimaux, en majuscules, préfixé `#`**. `saves.md` §1.5 le confirme et donne
même la comparaison mesurée (`C791049A` = MD5 tronqué ; le vrai CRC32 vaut `992A7EBF`, rien à voir).

**Correction** : l'affichage Argosy reproduit exactement ce format, au lieu des 16 caractères bruts
qu'il montrait d'abord.

### 3.2 Comparaison de hash sensible à la casse

**Symptôme** : le garde-fou anti-état obsolète se déclenchait sur des téléchargements dont le contenu
n'avait pas changé.

**Trouvé dans le journal** : `previous=c791049a5e84 / new=C791049A5E84, decision=delete auto/resume
states` — même contenu, décision de suppression quand même.

**Cause** : LiteBox produit ses hash avec `Convert.ToHexString` (majuscules), Argosy avec `%02x`
(minuscules). Comparés bruts, tous les téléchargements au contenu inchangé lisaient comme un changement.
Une chaîne vide (`content_hash` absent côté serveur) était en plus traitée comme un contenu différent
plutôt que comme un hash inconnu.

**Correction** : comparaison en minuscules des deux côtés, chaîne vide = hash inconnu.
**À retenir pour la suite** : ce piège existera à l'identique pour toute comparaison de hash dans la
future refonte en graphe.

### 3.3 Un seul fichier physique, plusieurs canaux qui écrivent dessus

**Symptôme, mesuré en direct** : le `.srm` d'une partie en cours est revenu à un état antérieur. Vérifié
par `md5sum` sur la tablette : le fichier portait le hash d'une sauvegarde plus ancienne que la partie
qui venait d'être jouée.

**Cause** : le cœur intégré n'a **qu'un seul fichier `.srm` par jeu**, mais `checkForServerUpdates` /
`downloadPendingServerSaves` et `syncSavesForNewDownload` traitaient chaque canal comme indépendant et
téléchargeaient chacun sur ce même fichier dès qu'il avait du nouveau côté serveur. Le dernier arrivé
gagnait, sans rapport avec le canal sélectionné dans l'interface. Le choix d'un canal ne pilotait que
l'**envoi**, jamais la **réception**.

**Correction** : nouveau `SaveSyncApiClient.channelsMatch` (autosave/null équivalents, sinon nom exact) ;
un canal différent du canal actif n'alimente plus que le cache, via `downloadAndCacheSave` — le même
chemin que le pré-chargement de la fiche de jeu, qui n'écrit jamais le fichier live. `forceSaveCheck` a
perdu au passage un heuristique en dur (null/autosave/nom de rom uniquement) qui aurait au contraire
ignoré les mises à jour d'un canal **nommé** pourtant actif.

### 3.4 Un identifiant stable qui masque un changement de contenu

**Symptôme** : une sauvegarde faite sur le bureau n'apparaissait jamais dans l'historique de la
tablette, même en rouvrant l'écran des sauvegardes plusieurs fois.

**Écarté par mesure** : le journal de requêtes du serveur montrait que la réponse envoyée à la tablette
était passée de `live 09-07 23:09` à `live 09-07 23:41` dès l'écriture du bureau, et le confirmait à
chaque appel. Le serveur servait donc déjà le bon contenu ; le problème était côté client.

**Cause** : l'entrée « live » d'un groupe porte un identifiant **synthétique et stable**
(`live|jeu|groupe|type` dans `RommAssetsApi`) qui ne change jamais, même quand le contenu du fichier
change — il nomme « le fichier vivant du groupe », pas une version. Or `GetUnifiedSavesUseCase` appariait
un cache local au serveur **par cet identifiant** et, dès qu'il trouvait une correspondance, affichait la
date du cache local sans jamais comparer les hash.

**Correction, des deux côtés** :
- serveur : quand une copie du coffre a exactement le même contenu que le fichier live, c'est **elle**
  qui est servie (identifiant réel, lié au contenu), au lieu de l'entrée live instable. L'ancien code
  faisait l'inverse — il masquait la copie du coffre pour garder l'entrée live en avant.
- client : la fusion compare aussi les hash (insensible à la casse, cf. §3.2) ; un écart traite le cache
  comme non apparié, ce qui fait réapparaître l'entrée serveur avec sa propre date et déclenche son
  téléchargement.

**Vérifié en direct** : après déploiement, la sauvegarde `d0199233f14b…` du bureau est apparue dans le
cache de la tablette, puis a été restaurée sur demande.

### 3.5 « Autosave par défaut » n'était appliqué qu'à l'affichage

**Symptôme, soulevé par Mehdi de mémoire** : une règle avait été posée le 2026-09-05 disant que sans
canal explicitement choisi, `autosave` est le canal par défaut « dans tous les cas ».

**Trouvé** : la règle existe bien, dans `GetUnifiedSavesUseCase.resolveActiveEntry`, avec son
commentaire d'origine — mais elle ne gouverne que ce que l'interface **affiche** comme sauvegarde active.
Le chargement réel au lancement (`SaveStateManager.restoreResumeSave`, sans ligne active enregistrée)
passait par `getMostRecentSave`, qui balaie **tous** les canaux. Un jeu jamais ouvert depuis l'écran des
sauvegardes pouvait donc démarrer sur le contenu d'une branche nommée, en contradiction avec ce que
l'interface appelait « active » pour ce même jeu.

**Correction** : nouvelle requête `SaveCacheDao.getMostRecentAutosave` (`channelName IS NULL`, le même
seau que `resolveActiveEntry`), utilisée par ce chemin de lancement.

### 3.6 Non corrigé — l'upload « Own branch » peut viser la mauvaise ligne physique

**Symptôme** : le contenu joué sous `lb-ra-snes9x` s'est retrouvé archivé dans le coffre de cette ligne
(correct) mais posé en **live sur la ligne `lb-ra-bsnes`**.

**Cause identifiée dans `RommAssetsApi.LandUpload`** : pour un client dont la politique de push est
« Own branch » (le cas normal), le serveur choisit la ligne physique à écrire **uniquement par branche
du client** (`FirstOrDefault` sur `BranchOf(g.GroupId) == "cN"`). Le nom de canal annoncé par le client
n'est utilisé que si la politique est « locked » ou « view ». Si le client a joué le même jeu via deux
cœurs différents, le bureau a deux lignes physiques pour la même branche, et le serveur prend celle qu'il
trouve en premier.

**Statut** : diagnostiqué, pas corrigé, pré-existant à cette session. À reprendre.

---

## 4. Autres observations, non traitées

- **Envoi d'état en échec permanent** : `stateId=2` échoue en boucle contre un `409` du serveur
  (« Nothing was stored: the upload held only saves this game already has »). Le serveur a raison de
  refuser un doublon, mais le client traite ce refus comme un échec à retenter. Borné à 3 tentatives par
  passe (compteur décroissant, non persistant), donc pas de boucle infinie — mais rejoué à chaque passe
  déclenchée par une écriture de sauvegarde. Un `409` de ce type devrait compter comme « déjà
  synchronisé », pas comme un échec.
- **Pointeur de sauvegarde active orphelin** : au lancement, `getActiveRow` a renvoyé vide (« No active
  save ») alors qu'une sauvegarde avait été activée peu avant. Hypothèse non confirmée : le
  dédoublonnage (`dedupeIdenticalCaches`) a supprimé la ligne de cache que ce pointeur visait, sans le
  reporter sur le survivant. Le repli a choisi un contenu identique par chance, donc sans perte cette
  fois.
- **Doublons de cache à chaque synchro** : plusieurs entrées identiques créées puis supprimées par
  `dedupeIdenticalCaches` à chaque cycle. Fonctionne comme prévu, mais c'est du travail refait à chaque
  passe.
- **`content_hash` vide côté serveur** sur certaines entrées (`SAVE_LANDED … serverHash=`) — le fichier
  arrive correctement, mais sans hash le nouvel affichage du code court n'a rien à montrer pour cette
  entrée.

---

## 5. La sous-option de journalisation

Les événements du §2 hachent des fichiers sur le chemin de restauration. Ils sont donc passés sous une
**seconde option**, *Detailed Restore Logging*, sous l'option *Save Debug Logging* existante (Réglages →
À propos → Debug), désactivée par défaut. L'option parente continue de journaliser ce qu'elle
journalisait avant (synchro, cache, canaux) sans rien hacher de plus.

Concrètement : `SaveDebugLogger.isVerbose` = les deux interrupteurs à `true` ; chaque point d'entrée
`logRestore*` / `logState*` / `logBuiltin*` / `logSaveLanded` / `logResumeStateGuard` /
`logLiveState*` / `logCacheRestoreFiles` sort immédiatement quand il est à `false`.

---

## 6. Ce que la session a changé, en résumé

Sept commits sur `litebox-version-switch` côté Argosy, plus une modification serveur côté LbApiHost
(non commitée, en attente de validation) :

| # | Portée | Objet |
|---|---|---|
| 1 | Argosy | journal détaillé des restaurations (§2) |
| 2 | Argosy | libellé LiteBox + code court sous la date dans la liste des sauvegardes |
| 3 | Argosy | code court au format du bureau (8 hex majuscules) au lieu de 16 caractères (§3.1) |
| 4 | Argosy | seul le canal actif écrit le fichier physique (§3.3) |
| 5 | Argosy | comparaison de hash insensible à la casse (§3.2) |
| 6 | Argosy | la fusion ne fait plus confiance à un identifiant stable si le hash a changé (§3.4) |
| 7 | Argosy | « autosave par défaut » respecté au lancement (§3.5) |
| — | LiteBox | priorité au coffre sur le live à contenu identique (§3.4), champ `litebox_label` |

La refonte en graphe qui a émergé de cette session est documentée à part :
`LbApiHost/docs/save-history-tree-draft.md`.
