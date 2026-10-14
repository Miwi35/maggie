# Les deux couches : conversations entières, fiches comme savoir — Plan (MAG-17, ADR-011)

Contrat court : `agent-os/standards/agent/memory.md`. Socle : MAG-195 (dossier, synchro, index). Ce ticket décide **ce qu'on écrit dans les fichiers**.

## Livré

- **Conversations** (`agent/app/memory/conversations.py`) : chaque message est ajouté, tout de suite et sans outil (à la réponse stockée par `message_repo.create`, quand le routeur fixe le fil — `update_context` —, et avant tout outil mémoire — `ToolRouter.call_tool` —, pour qu'une fiche écrite pendant le tour puisse citer l'échange en cours), à `Conversations/AAAA/MM/AAAA-MM-JJ HHhMM — <sujet>.md` (un fichier par fil). Chaque message a son heure à la minute, un ancrage `<!-- msg: <id> -->`. Le fichier ne fait que **grandir** : le service refuse toute écriture dont le texte ne commence pas par l'ancien.
- **Fiches** (`agent/app/memory/fiches.py`) : outil `memory_write_fiche` — titre = phrase vraie, dossiers choisis par le modèle, une liste d'**affirmations** `{texte, provenance dit|observé|déduit, source, heure}`. Le code rend le Markdown et la carte ; il refuse une affirmation sans provenance, une source-conversation qui n'existe pas, et la **disparition** d'une affirmation d'une version à l'autre (elle doit être marquée remplacée, datée).
- **Outils** : `memory_search`, `memory_open` (entier ou passage ; annonce les éléments `déduit`), `memory_write_fiche` remplacent `store/search/update/delete_memory`. Tout passe par `MemoryService`.
- **Contexte** : le prompt reçoit la liste compacte des fiches (chemin — résumé) lue dans l'index, donc ce que le propriétaire demande de retenir est **retrouvable au tour suivant**, sans attendre la synchro ni la nuit.
- **Prompt** : paragraphe MÉMOIRE réécrit (écrire tout de suite, une demi-phrase ; rien d'affirmé sans fiche ouverte ; `déduit` annoncé ; jamais d'ajout de son chef posé comme fait). `LISEZMOI.md` et `AGENTS.md` disent les règles.
- **Reprise** (`agent/app/memory/legacy.py`, `task agent:memory:import-legacy`, et au premier tour de chaque utilisateur) : l'historique des messages devient des conversations, les lignes `memory` des fiches `déduit` « sans source ». Idempotente.
- La table `memory` reste en lecture seule pour la reprise ; son enum `MemoryType` et la valeur par défaut `factual` disparaissent du code (aucune catégorie de mémoire dans `agent/app/`).

## Décisions

1. **Un fichier de conversation par fil (`context_id`)**. Dilemme : à quoi correspond « un échange » ? Options : par message ; par plage de silence ; par fil. Choix : par fil, ancré à la minute du premier message ; les messages sans fil vont dans un fichier du jour. Pourquoi : le fil porte déjà le sujet (`label`) et la logique de routage ; le nom du fichier reste stable.
2. **Écriture dès que la réponse est stockée, sans outil**. Dilemme : quand écrire ? Options : à chaque message ; en fin de tour ; à la réponse. Choix : quand `message_repo.create` stocke la réponse de Maggie, le code ajoute au fichier tous les messages du fil que celui-ci ne contient pas encore (la question y compris), local d'abord. Pourquoi : la question est stockée avant que son fil soit connu (le routeur le fixe pendant le tour) ; à la réponse, le fil est connu et rien ne dépend d'un appel d'outil. Une réponse interrompue est stockée aussi : la question n'est jamais perdue.
3. **Pas d'outil « écrire une conversation » pour le modèle**. L'écriture est automatique (point 2) ; un outil dépendrait de ce que le modèle pense à appeler, ce que le ticket veut éviter. Le service expose `conversations.record`.
4. **Pas d'outil de suppression de fiche**. Une information fausse est marquée remplacée ; le propriétaire supprime dans le dossier.
5. **Affirmations structurées**, pas du Markdown libre. Pourquoi : provenance et source se vérifient dans le code ; le texte reste lisible (`- texte — dit · Conversations/… (18h14)`).
6. **Reprise : provenance `déduit`, source « ancienne mémoire »** pour les lignes `memory` : leur origine est inconnue (l'UBU « Nantes »), donc annoncées comme déduites tant que personne ne les confirme.

## Parcours e2e (étend MAG-99, fake LLM + bucket simulé)

`e2e/web/tests/memory-layers.spec.ts` (scénarios `agent/fixtures/fake-llm/50-52-memory-*.yaml`, rejoués aussi par `agent/tests/memory/test_shipped_scenarios.py`) :
- Given le propriétaire raconte une anecdote (« Les concerts commencent à 19 heures et la salle s'appelle l'UBU »), When Maggie répond sans appeler d'outil, Then le fichier de conversation du bucket contient les deux messages en entier, chacun sous son heure à la minute.
- Given cette conversation, When il dit « Retiens ce que je t'ai dit… » (le parcours nomme le fichier et la minute, un modèle scripté ne pouvant pas les chercher), Then la fiche est écrite dans le même tour, cherchable sans synchro, et cite la conversation et sa minute, provenance `dit` ; le fichier de conversation n'a été que prolongé.
- Given la fiche écrite, When le tour suivant, Then elle est dans le prompt de Maggie, qui l'ouvre (`memory_open`) avant de répondre.

## Hors périmètre

Cartes et rappel (MAG-16), entretien nocturne qui fusionne (MAG-159), rythme à 5 minutes (MAG-15), consultation web (MAG-20).
