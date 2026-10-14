# IPTV Ultimate — Android prototype 0.3.0

Projet Android natif Kotlin / Jetpack Compose. Il s'agit d'un prototype enrichi, pas encore d'une version de production ni d'un APK validé sur une box réelle.

## Fonctions présentes dans le code
- Connexion Xtream Codes via serveur, identifiant et mot de passe, avec tentative de validation de la connexion.
- Chargement des chaînes en direct, du catalogue VOD et des séries depuis l'API Xtream.
- Catégories, recherche, favoris enregistrés localement et affiches/logos quand le fournisseur les fournit.
- Guide TV utilisant `get_short_epg` Xtream pour les chaînes sélectionnées.
- Lecture Media3 / ExoPlayer des chaînes et films ; sélection d'épisodes pour les séries lorsque le serveur expose `get_series_info`.
- Reprise locale de la position pour les contenus dont le flux fournit une position exploitable.
- Thèmes de couleur, nom de profil local, filtre parental rudimentaire par mots-clés et PIN local.
- Export manuel d'un petit JSON de préférences/favoris affiché à l'écran.
- Synchronisation périodique WebDAV/Nextcloud configurable (WorkManager, HTTPS obligatoire), pour thèmes, profil, favoris, PIN parental et positions de lecture ; les identifiants Xtream ne sont pas synchronisés.
- Onglet Replay/Catch-up basé sur les indicateurs `tv_archive` / `tv_archive_duration` et les URL Xtream `/timeshift/`; dépend du format pris en charge par le fournisseur.
- Multiview expérimental avec deux instances Media3 côte à côte.
- Action d'ouverture dans un lecteur externe Android si un lecteur compatible est installé.
- Workflow GitHub Actions pour tenter de compiler un APK de débogage dans le cloud.

## Limites importantes — non implémenté ou non validé
- **Pas d'APK compilé/testé fourni à ce stade.** Le workflow cloud doit être exécuté dans un dépôt GitHub et sa réussite vérifiée.
- **Catch-up / Replay : implémentation générique ajoutée, non testée.** Elle essaie les fenêtres 1 h, 2 h et 3 h via `/timeshift/`; certains serveurs utilisent un autre format ou ne permettent pas le Replay.
- **Synchronisation automatique : implémentation WebDAV ajoutée, non testée.** Elle nécessite ton propre compte WebDAV/Nextcloud et une URL HTTPS de destination vers un fichier JSON. La synchronisation utilise une règle simple de dernière modification ; ce n'est pas un service cloud fourni par l'application. Les mots de passe Xtream ne sont pas synchronisés.
- **mpv natif intégré : toujours non implémenté.** L'application tente seulement de transmettre un flux à un lecteur externe Android ; une intégration libmpv exige encore un composant natif et des tests sur appareils.
- **Contrôle parental : basique uniquement.** Filtrage par mots-clés et PIN local, pas une sécurité robuste ; les noms de catégories ne sont pas fiables à 100 %.
- **Profils : étiquette locale simple**, pas des espaces de profil entièrement isolés avec paramètres séparés.
- Le multiview est expérimental et peut surcharger une box d'entrée de gamme.
- Le guide dépend de l'EPG disponible chez le fournisseur ; la plupart des serveurs ne fournissent pas nécessairement tous les champs.
- La reprise fonctionne uniquement si le flux est seekable et que le fournisseur accepte la reprise à une position donnée. Pour la TV en direct, elle peut ne pas fonctionner.
- Les identifiants sont enregistrés dans les préférences Android locales sans chiffrement applicatif dédié. Ne pas utiliser cette version comme produit fini avec des secrets sensibles.
- Interface encore rudimentaire, pas encore testée sur différentes tailles d'écran ni à la télécommande sur une Vontar X3.

## Obtenir l'APK sans PC — compilation cloud GitHub Actions
Le workflow `.github/workflows/build-apk.yml` compile un APK de débogage sur un runner cloud GitHub et publie le fichier comme artefact. Il faut placer les fichiers du projet dans un dépôt GitHub ; le ZIP seul ne peut pas déclencher une compilation.

1. Téléchargez et décompressez ce ZIP sur le téléphone.
2. Sur GitHub, créez un dépôt **privé** nommé `iptv-ultimate`.
3. Envoyez tous les fichiers et dossiers du projet à la racine du dépôt, en conservant `.github/workflows/build-apk.yml`.
4. Ouvrez l'onglet **Actions** et lancez **Build IPTV Ultimate APK** si le workflow ne démarre pas automatiquement.
5. Une fois l'exécution terminée, vérifiez qu'elle est verte, puis téléchargez l'artefact `IPTV-Ultimate-debug-APK`.
6. Décompressez l'artefact et installez `app-debug.apk` sur la box. Android peut demander d'autoriser l'installation depuis la source utilisée.

**Ne mettez jamais vos identifiants IPTV dans GitHub.** Saisissez-les uniquement dans l'application. Gardez le dépôt privé.

## Paramètres techniques
- Android minSdk 23, compileSdk/targetSdk 35, Java 17.
- Kotlin, Jetpack Compose, AndroidX Media3 ExoPlayer, Coil pour charger les affiches, WorkManager pour la synchronisation périodique.
- `usesCleartextTraffic=true` est activé pour compatibilité avec certains serveurs IPTV HTTP ; HTTPS est préférable.
- La compilation et le fonctionnement sur appareil réel n'ont pas été confirmés depuis cet environnement.
