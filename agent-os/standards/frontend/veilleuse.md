# Veilleuse — l'identité de l'admin (MAG-311)

L'admin porte l'identité des écrans d'activité, **cohérence, pas copie** : il prend les couleurs, la police, les formes et l'unique accent ; il garde sa densité, sa disposition et son responsive. Aucun écran n'est recomposé, aucune taille d'écran d'activité n'est reprise.

Les valeurs sont dans `design/tokens.json` (voir `global/design-system.md`) ; `admin/src/theme.ts` les lit et exporte `veilleuseLightTheme` / `veilleuseDarkTheme`.

## Règles

- **Un seul accent violet** (`brand.primary` en sombre, `primaryLight` en clair). La seule autre couleur vive est la teinte du module (`theme.palette.module.cuisine|comptes|sport|travail`), jamais pour un bouton.
- **Surfaces** : la carte se détache du fond par la couleur (`paper`, `raised`), pas par une bordure ni une ombre. Séparateurs : `divider` (blanc 6 % sombre, noir 8 % clair).
- **Formes** : boutons, puces et onglets en pilule (999) ; champs 12 ; cartes 16 ; dialogues 20.
- **Police** : Geist 300–600 (jamais un poids non chargé, cf. `index.html`), Geist Mono pour le code, **chiffres tabulaires partout** (`MuiCssBaseline`). Les tailles et hauteurs de ligne restent celles de MUI : une ligne de liste ne grandit pas.
- **Contraste** : texte ≥ 4,5:1 dans les deux modes, sur fond, carte et surface surélevée ; `theme.test.ts` le calcule. Toute nouvelle couleur de texte passe par ce test.
- **Responsive** : le thème n'enlève rien aux cibles de 44 px sous `md` (`NARROW_QUERY`) ni au `minWidth: 0` du cadre.
- **Couleur lue dans le thème**, jamais en hexadécimal (`theme.palette…` ou `'primary.main'` dans `sx`).

## Pièges de la fusion avec radiant

`createTheme(radiant, options)` fusionne en profondeur, mais **remplace** la clé dès que la valeur entrante n'est pas un objet : un `styleOverrides` écrit en fonction sur un slot que radiant style déjà (`MuiPaper`, `MuiAppBar`, `MuiTableCell`, `RaDatagrid`, `RaMenuItemLink`, `RaToolbar`, `RaLayout`…) supprime sa règle sans bruit. Ces slots restent des objets ; media query en clé `@media ${NARROW_QUERY}`. `theme.test.ts` le vérifie.

## La parole de Maggie

Un seul composant pour toute parole spontanée : `components/maggie/MaggieInterruption` (voile en dégradé radial 500 ms, avatar `public/maggie.png` — cadrage `object-position: 50% 12%`, zoom 1,2 —, pop-in 760 ms puis respiration et halo 2,4 s, bulle `maggie.bubble` à coins 30/30/8/30, deux boutons pilules : action proposée + « Plus tard »).

- `role="alertdialog"`, focus sur l'action, **Esc = « Plus tard »**, ouvrir le chat la ferme ; « Plus tard » la ramène plus tard si rien n'a été fait.
- Carillon : sinus 880 Hz puis 1318,5 Hz à 140 ms, fondu ~0,9 s ; coupé par le réglage « Son » des Préférences (`localStorage`, côté client).
- `prefers-reduced-motion` : fondu simple, ni pop-in, ni halo, ni glissement.
- Source : le flux Mercure `/proactions/{userId}` (statut `completed` avec réponse) ; aucun changement d'API.

Le chat (`components/chat/ChatWidget`) porte le même habillage : panneau `maggie.panel`, bulles utilisateur accent 18 % et Maggie `maggie.reply`, mode vocal en orbe violette. Sa place ne change pas (à droite, tiroir sous `md`, MAG-38).

## Tests

`theme.test.ts` (tokens des deux modes, contrastes calculés, slots en objets), un rendu par mode pour chaque composant d'identité, l'interruption (apparition, action, « Plus tard », Esc, son coupé), parcours e2e `e2e/web/tests/maggie-interruption.spec.ts`.
