# HOME IPTV V2 — lot 1 Android

Prototype natif Android/Fire TV : interface HOME IPTV, navigation à la télécommande, TV en direct, films et séries. Les sources personnelles M3U/M3U8 et Xtream peuvent être ajoutées et testées depuis l’écran Paramètres.

## État de ce lot

- Les profils sont stockés sur l’appareil ; les identifiants Xtream sont chiffrés avec Android Keystore.
- L’app lit les playlists M3U/M3U8 et l’API Xtream côté appareil. Les libellés de groupes français comme FR/French/France sont normalisés et les entrées ayant exactement la même URL sont dédupliquées.
- Les flux sont lus par AndroidX Media3. La lecture dépend des formats réellement servis et décodés par l’appareil.
- Un raccourci ouvre les réglages VPN Android/Fire TV. HOME IPTV ne fournit pas son propre tunnel VPN ; il utilise celui déjà configuré dans le système.
- Les sources M3U, les API Xtream et les flux de lecture doivent utiliser HTTPS. Le trafic HTTP en clair est refusé par l’application, car Xtream place les identifiants dans les requêtes. Demander au fournisseur une adresse HTTPS officielle ; ne pas deviner un port HTTPS alternatif.
- Recherche dans les chaînes/groupes M3U, catégories Xtream, films, séries et épisodes ; les accents sont ignorés.
- Pas d’enregistrement, d’administration, de partage de services ni de bibliothèque personnelle. Pas encore de guide EPG.
- Le logo retenu est intégré à l’application.

## Génération APK

Le workflow GitHub Actions construit l’APK debug automatiquement après l’envoi du code sur la branche principale. L’APK est récupérable comme artefact dans l’exécution correspondante.

## Environnement

JDK 17, Gradle 9.8.0, Android SDK 36 et Android Gradle Plugin 9.5.0. Le poste de préparation ne contient pas Gradle ni Android SDK : la compilation et les essais sur appareil restent à faire dans un environnement Android.
