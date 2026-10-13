# Socle de la mémoire — Plan (MAG-195, ADR-011)

Spec détaillée : Linear « Spec : Mémoire vivante ». Contrat court pour coder : `agent-os/standards/agent/memory.md`.

## Livré
- Dossier `<ULID>/Mémoire/` par utilisateur, fichiers générés (LISEZMOI, AGENTS, sommaire, journal) scellés.
- Client S3 seul (`MEMORY_*`), bucket simulé `MEMORY_PROVIDER=fake` avec pannes injectables.
- Synchro dans les deux sens, outbox Postgres, le propriétaire gagne (`Conflits/`), mode dégradé.
- Index Postgres jetable (cartes, évoque, alias, liens, FTS français, corps), compteurs à part, `task agent:memory:rebuild`.

## Hors périmètre
Contenu écrit (MAG-17), cartes et rappel (MAG-16), maintenance nocturne (MAG-159), vue web (MAG-20). L'ancienne table `memory` et ses outils restent.

## E2E
Parcours `e2e/web/tests/memory-folder.spec.ts`, étend MAG-99 :
- Given un dossier synchronisé, When le propriétaire corrige un fichier dans le bucket, Then la synchro le reprend et l'index le reflète.
- Given le bucket coupé, When Maggie écrit une fiche et qu'on lui demande si sa mémoire est à jour, Then elle répond avec sa copie locale, dit qu'elle est peut-être en retard, et l'écriture attend.
- Given le bucket de retour, When la synchro passe, Then le fichier est poussé et la mémoire n'est plus en retard.
- Then l'index vidé puis reconstruit retrouve tout.

## Garde-fou
La PR touche `infra/` : `needs-human`, un humain fusionne.
