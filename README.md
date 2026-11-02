# HOME IPTV V2 — lot 1 Android

Prototype natif Android/Fire TV : interface HOME IPTV, navigation à la télécommande, TV en direct, films et séries. Les sources personnelles M3U/M3U8 et Xtream peuvent être ajoutées et testées depuis l’écran Paramètres.

## État de ce lot

- Les profils sont stockés sur l’appareil ; les identifiants Xtream sont chiffrés avec Android Keystore.
- L’app lit les playlists M3U/M3U8 et l’API Xtream côté appareil. Les libellés de groupes français comme FR/French/France sont normalisés et les entrées ayant exactement la même URL sont dédupliquées.
- Les flux sont lus par AndroidX Media3. La lecture dépend des formats réellement servis et décodés par l’appareil.
- Un raccourci ouvre les réglages VPN Android/Fire TV. HOME IPTV ne fournit pas son propre tunnel VPN ; il utilise celui déjà configuré dans le système.
- Les sources M3U, l’API Xtream et les flux acceptent HTTP ou HTTPS. En HTTP, les identifiants Xtream et le contenu des flux circulent sans chiffrement ; privilégier HTTPS lorsque le fournisseur le propose.
- Recherche dans les chaînes/groupes M3U, catégories Xtream, films, séries et épisodes ; les accents sont ignorés.
- Pas d’enregistrement, d’administration, de partage de services ni de bibliothèque personnelle. Pas encore de guide EPG.
- Le logo retenu est intégré à l’application.

## Mises à jour APK

- Chaque modification du code applicatif sur `main` déclenche une compilation.
- Quand la clé de signature est configurée dans les secrets GitHub Actions, le workflow crée aussi une **Release en brouillon**. Elle n’est pas visible par l’application tant que le propriétaire ne l’a pas vérifiée et publiée.
- HOME IPTV vérifie les Releases publiées au démarrage et depuis Paramètres. L’application affiche la version disponible, attend l’accord de l’utilisateur, télécharge l’APK en HTTPS et vérifie son empreinte SHA-256 avant d’ouvrir l’installateur Android.
- Android demande ensuite une confirmation d’installation. Si le téléchargement, l’empreinte ou l’installation échoue, la version installée reste en place.
- La clé de signature doit rester dans les secrets GitHub Actions ; elle ne doit jamais être ajoutée au dépôt. Les secrets requis sont `HOME_IPTV_KEYSTORE_BASE64`, `HOME_IPTV_KEYSTORE_PASSWORD`, `HOME_IPTV_KEY_ALIAS` et `HOME_IPTV_KEY_PASSWORD`.
- Les APK debug déjà distribuées utilisent une clé CI temporaire. Le passage à la nouvelle signature stable nécessitera une réinstallation manuelle unique ; Android ne peut pas mettre à jour une app signée avec une autre clé.

Le build debug reste récupérable comme artefact dans l’exécution GitHub Actions.

## Environnement

JDK 17, Gradle 9.8.0, Android SDK 36 et Android Gradle Plugin 9.5.0. Le poste de préparation ne contient pas Gradle ni Android SDK : la compilation et les essais sur appareil restent à faire dans un environnement Android.
