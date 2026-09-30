# Agent Hub : skills, sous-agents, validations — notes de cadrage

## Périmètre

Trois évolutions de l'Agent Hub (`agent/`), inspirées de Claude Code :

1. **Skills chargés à la demande.** Le prompt système contient toujours l'index `name: description` de tous les skills, et le modèle charge le contenu d'un skill avec `get_skill` avant d'agir. Cela remplace l'injection par mots-clés, qui retenait les 3 skills les mieux classés.
2. **Sous-agents.** Ils sont définis en Markdown avec frontmatter (`agent/data/agents/*.md` : prompt, motifs d'outils, modèle). L'agent principal leur confie une tâche avec l'outil natif `delegate` et ne reçoit en retour qu'un résumé.
3. **Validation des actions sensibles.** Une politique côté agent (`agent/data/policy.yaml`) attribue à chaque outil un mode `allow` / `ask` / `deny`. En mode `ask`, l'action est persistée et l'utilisateur la valide ou la refuse dans le chat web et mobile. Si il valide, Maggie exécute les arguments figés puis annonce le résultat.

## Décisions

- **Pas de Claude Agent SDK.** Il lance le CLI Claude Code en sous-processus, stocke ses sessions en local (`~/.claude`, ce qui est incompatible avec k3s sans état et avec la mémoire en Postgres), expose des outils système de fichiers sans sandbox, et ses skills sont lus sur disque. On reprend ses principes dans notre gateway Messages API.
- **Politique côté agent**, et non annotations MCP côté Symfony. C'est plus simple et plus rapide. Les annotations `destructiveHint` pourront servir de valeur par défaut plus tard.
- **Sous-agents en Markdown + frontmatter**, même format que les skills. Pas de récursion : un sous-agent n'a jamais accès à `delegate`.
- **Reprise asynchrone et persistée.** Maggie termine son tour avec un `tool_result` `pending_approval`. Le même mécanisme couvre le chat et les proactions, et il survit aux redémarrages.
- **Arguments figés.** Ce qui est validé est exactement ce qui est exécuté, via `source="approval"` qui contourne la politique.
- **Modes pensés comme la base d'Act / Propose / Silent.** `allow` = Act, `ask` = Propose. Silent et l'ajustement automatique de la confiance viendront plus tard.
- **A2A** : `ask` devient `deny`, car personne ne peut valider.
- **Topics Mercure des validations** : `/users/{uid}/agent/approvals/{id}`, publiés en **private** (les topics agent actuels sont publics et non isolés par utilisateur).
- **Expiration** d'une demande après 24 h.
- **Un refus** ne déclenche pas d'appel au LLM.

## Contexte

- **Visuels :** aucun.
- **Références :** voir `references.md`.
- **Alignement produit :**
  - `mission.md` : « Propose, never impose », « Transparent & controllable ».
  - `roadmap.md` : Phase 2 « Confidence/autonomy system (Act / Propose / Silent) » et Phase 3 « Specialist agents system ».
- **Origine :** brainstorming du 30 sept. 2026 (Maggie comparée à OpenClaw), qui vise à terme l'accès aux mails et aux documents. Les sous-agents en quarantaine, en lecture seule, et la validation des actions en sont les prérequis de sécurité.

## Hors périmètre

- Push FCM (le backend n'existe pas : pas de stockage de tokens ni d'envoi). Spec séparée.
- Refonte de l'A2A (utilisateur « a2a » avec tous les outils).
- Prompt caching (`cache_control`) et montée du modèle par défaut (`claude-sonnet-4-5` dans `config.py`).
- Annotations MCP côté Symfony.
- Doublon d'entrée dans `SkillIndex.create` quand un fichier existant est écrasé (bug noté).

## Standards appliqués

- agent/architecture : gateway, MCP client, personnalité, pydantic-settings.
- agent/testing : pytest async, respx, fixtures.
- admin/react-admin et admin/testing : cartes de validation dans `ChatWidget`, tests Vitest.
- mobile/android-app et mobile/testing : Compose, Koin, Ktor, tests MockK du ViewModel.
- global/real-time : Mercure pour les mises à jour des validations.
- global/testing : un comportement par test, services externes mockés.
