# Agent Hub : skills à la demande, sous-agents, validation des actions sensibles

## Contexte

Suite au brainstorming du 30 sept. 2026 (Maggie comparée à OpenClaw, Claude Agent SDK écarté), on garde notre gateway sur la Messages API et on reprend trois principes de Claude Code :

1. **Skills chargés à la demande.** Aujourd'hui `get_relevant_skills_context()` (`agent/app/skills/index.py:195`) injecte le corps des 3 skills qui ressortent d'une recherche par sous-chaîne sur le message. C'est fragile (« le » ou « de » suffisent à matcher, et « on mange quoi ? » ne trouve pas de recette). En plus, les proactions ne reçoivent aucun skill (`gateway.py:93`).
2. **Sous-agents.** Un outil `delegate` confie une tâche à un agent défini en Markdown. Il a son propre prompt, un sous-ensemble d'outils et son propre modèle, et il ne renvoie qu'un résumé. Objectifs : isoler le contexte et pouvoir donner un accès en lecture seule. C'est une base pour les mails plus tard, et ça réalise « Specialist agents system » de la roadmap.
3. **Validation des actions sensibles.** Une politique côté agent attribue à chaque outil un mode `allow` / `ask` / `deny`, première brique du système de confiance Act / Propose / Silent de la roadmap. En mode `ask`, l'action est persistée et Maggie termine son tour. L'utilisateur autorise ou refuse depuis le web ou le mobile. Si il autorise, Maggie exécute les arguments figés puis annonce le résultat. C'est l'application directe de « Propose, never impose » (mission.md).

Décisions validées avec l'utilisateur : UI web et mobile, politique côté agent (pas d'annotations MCP), sous-agents en Markdown avec frontmatter, reprise asynchrone et persistée, push FCM renvoyée à une spec séparée (le backend FCM n'existe pas : pas de stockage de tokens ni d'envoi dans `api/` ou `agent/`).

---

## Tâche 1 : Enregistrer la spec

Créer `agent-os/specs/2026-09-30-HHMM-agent-skills-subagents-approvals/` (HHMM = heure de l'enregistrement) avec :
- **plan.md** : ce plan ;
- **shape.md** : périmètre, décisions ci-dessus, contexte (brainstorming, SDK écarté, alignement avec mission et roadmap), hors-périmètre (FCM, A2A, prompt caching, montée de modèle, annotations MCP) ;
- **standards.md** : contenu complet de `agent/architecture`, `agent/testing`, `admin/react-admin`, `admin/testing`, `mobile/android-app`, `mobile/testing`, `global/real-time`, `global/testing` ;
- **references.md** : les références listées en fin de plan ;
- pas de dossier `visuals/` (aucune maquette).

## Tâche 2 : Skills à la demande

- `agent/app/skills/index.py` : remplacer `get_relevant_skills_context(message)` par `get_skills_index()`. Il renvoie l'index complet de tous les skills (`- name: description`), trié par nom pour qu'il soit stable et puisse être mis en cache, et n'injecte plus aucun corps. Supprimer `search()` s'il n'a plus d'appelant.
- `gateway.py` (`_build_system_prompt`) et `streaming.py` (`_build_system_prompt`) : utiliser l'index sans dépendre du message, ce qui donne aussi les skills aux proactions.
- `tools.py` : nouvelle description de `get_skill` : « charge une compétence AVANT d'exécuter une tâche qu'elle couvre ».
- `app/personality/default.yaml` (puce COMPÉTENCES) : le prompt ne dit plus « chargées automatiquement ». Il dit de consulter l'index et d'appeler `get_skill` avant d'agir.
- Mettre à jour `tests/test_skill_index.py` : contenu de l'index, aucun corps injecté, ordre stable, index vide.

## Tâche 3 : Boucle d'outils partagée et contexte d'appel

Prérequis pour `delegate` et pour la garde de validation.
- Extraire `LLMGateway._run_tool_loop` (`gateway.py:154-233`) dans `agent/app/llm/runner.py` : `run_tool_loop(system, messages, tools, *, user_id, model, max_iterations, max_tokens, call_type, source)`. `tools=None` doit être accepté, pour une réponse sans outils. `LLMGateway.chat` et `LLMGateway.proaction` l'utilisent. `streaming.py` garde sa propre boucle, parce qu'il émet des événements SSE.
- `ToolRouter.call_tool(name, arguments, user_id, source="chat")` (`tools.py:474`) : ajouter le paramètre `source`. Valeurs : `chat`, `chat_stream`, `proaction`, `subagent:<nom>`, `a2a`, `approval`. Le passer depuis `runner`, depuis `streaming.py:197` et depuis l'exécuteur A2A.
- Nouveau `tests/test_runner.py`, car la boucle n'a aucun test aujourd'hui : réponse texte, un tour avec outil puis texte, limite d'itérations, erreur API, `tools=None`. Pour le mock, reprendre le style `MagicMock` / `AsyncMock` de `tests/test_streaming.py`.

## Tâche 4 : Sous-agents et outil `delegate`

- Extraire le parsing du frontmatter de `skills/index.py:_parse_file` dans un helper partagé (`app/common/frontmatter.py`), puis le réutiliser.
- `agent/app/agents/registry.py` :
  - dataclass `SubagentDefinition(name, description, model, tools: list[str], max_iterations=8, prompt)`, le prompt étant le corps du fichier ;
  - singleton `subagent_registry` chargé depuis `/app/data/agents/*.md` au démarrage, dans le lifespan de `app/main.py` à côté de `skill_index.rebuild()`.
- Format : `agent/data/agents/<nom>.md`.
  ```
  ---
  name: researcher
  description: Recherche transverse en lecture seule (agenda, recettes, courses, finance, mémoire) et synthèse
  model: haiku            # haiku | sonnet | opus → alias dans config.py
  tools: ["search*", "get_*", "list_*", "search_memory", "get_skill"]
  max_iterations: 8
  ---
  <prompt système>
  ```
- `config.py` : ajouter `model_aliases`. `haiku` pointe vers `claude-haiku-4-5-20251001`, `sonnet` vers `anthropic_model` et `opus` vers `claude-opus-5-5`. Ajouter à `PRICING` (`metrics.py`) les modèles manquants, en vérifiant la grille officielle.
- Outil natif `delegate(agent, task)` :
  - il est construit dynamiquement dans `get_tool_definitions`, avec la liste des agents dans l'`enum` et leurs descriptions dans le texte de l'outil ;
  - il n'est exposé que si le registre n'est pas vide.
- Handler `app/agents/delegate.py` (import paresseux pour éviter un cycle avec `tools.py`) :
  1. filtrer les outils avec `fnmatch` selon les motifs de l'agent ;
  2. toujours retirer `delegate`, pour empêcher la récursion ;
  3. appeler `run_tool_loop(system=prompt + contexte mémoire, messages=[task], model=alias, call_type="subagent", source=f"subagent:{name}")` ;
  4. renvoyer `{"agent", "result", "tool_calls": [noms]}`.

  Les appels d'outils du sous-agent passent par `call_tool`, donc la politique de la tâche 5 s'applique aussi à eux.
- Livrer un agent d'exemple, `data/agents/researcher.md`, en lecture seule.
- `default.yaml` : ajouter une puce SOUS-AGENTS, du genre « délègue via delegate quand une tâche demande beaucoup de lectures ou de recherches ; ne délègue pas les actions simples ».
- Tests :
  - `tests/test_subagent_registry.py` : parsing, frontmatter invalide ignoré, alias de modèle inconnu ;
  - `tests/test_delegate.py` : filtrage des outils, exclusion de `delegate`, `source` transmis, agent inconnu → erreur JSON ;
  - `tests/test_tool_router.py` : `delegate` présent ou absent selon le registre.

## Tâche 5 : Politique d'outils et actions en attente

- `agent/data/policy.yaml`. La première règle qui correspond l'emporte, sinon le `default` s'applique.
  ```yaml
  # allow = Act · ask = Propose (validation) · deny = interdit
  default: allow
  rules:
    - tools: ["delete_memory", "delete_instruction", "update_*memory*"]
      mode: allow
    - tools: ["delete_*"]
      mode: ask
    - tools: ["manage_*"]
      when: { action: ["delete"] }
      mode: ask
  ```
  Filtre optionnel `sources: [...]` sur une règle.
- `agent/app/policy/engine.py` : `evaluate(tool_name, arguments, source) -> Mode`.
  - `source == "approval"` donne toujours `allow` : c'est l'exécution après validation.
  - `source == "a2a"` transforme `ask` en `deny`, car personne n'est là pour valider.
- Modèle `agent/app/db/pending_action_model.py`, table `agent_pending_action` créée via le même `create_all` que les autres repositories. Colonnes :
  - `id` : String(26), ULID, comme `agent_message` ;
  - `user_id`, `tool_name`, `arguments` (JSONB), `source`, `context_id` (nullable) ;
  - `status` : `pending` / `approved` / `denied` / `expired` / `failed` ;
  - `result` (Text), `created_at`, `decided_at`, `expires_at` (création + 24 h).
- `pending_action_repository.py` : `create`, `get_for_user`, `find_pending(user_id)`, `decide`, `expire_overdue`. Après chaque changement, il publie sur Mercure `/users/{uid}/agent/approvals/{id}` avec **`private=True`**.
  - `MercurePublisher.publish` (`app/mercure/publisher.py`) gagne un paramètre `private: bool = False`, qui ajoute `private=on`.
  - Vérifier que le claim `subscribe` du cookie admin (`GoogleAuthController.php:227-248`) et du token mobile couvre bien ce topic.
- La garde se trouve dans `ToolRouter.call_tool`, avant le routage natif ou MCP :
  - `deny` renvoie `{"error": "Action interdite par la politique"}` ;
  - `ask` crée l'action en attente, ou réutilise une action identique déjà en attente (même outil, mêmes arguments), et renvoie `{"status":"pending_approval","approval_id":…,"message":"En attente de validation par l'utilisateur. Ne réessaie pas ; explique-lui ce que tu t'apprêtes à faire."}`.
- `streaming.py:234` : le `CUSTOM tool_result` expose `status: "pending_approval"`, que le Mind panel affiche.
- `gateway.py:86-91` : le préambule des proactions dit « ne demande pas de confirmation ». Préciser que les actions soumises à validation seront proposées automatiquement.
- Expiration : ajouter l'appel `expire_overdue()` à la boucle du scheduler (`queue/scheduler.py`, `_execution_loop`).
- Tests :
  - `tests/test_policy_engine.py` : ordre des règles, `when` sur `action`, `sources`, `approval` → `allow`, `a2a` → `deny` ;
  - `tests/test_tool_router.py` : `ask` ne route pas l'appel et crée l'action, `deny`, déduplication ;
  - repository : style mock existant ;
  - publisher : `private` transmis (respx).
- **E2E : N/A — rien d'accostable tant que la tâche 6 n'est pas livrée.** La garde retient l'action et Maggie annonce qu'elle attend, mais aucune surface ne permet encore de répondre : un parcours ne pourrait qu'affirmer qu'une suppression n'a pas eu lieu, ce que `test_tool_router.py` prouve déjà, et il serait réécrit dès que la carte existe. Le Given/When/Then est posé avec les endpoints et les cartes (tâches 6 et 7) : *Étant donné un événement dans l'agenda, quand je demande à Maggie de le supprimer, alors l'événement est toujours là et une carte en attente s'affiche ; quand je clique Autoriser, alors l'événement disparaît et Maggie confirme* — il étend le parcours de chat MAG-99.

## Tâche 6 : Endpoints de validation et reprise

Dans `agent/app/api/routes.py`, avec l'auth `Depends(get_current_user_id)` :
- `GET /approvals?status=pending` : liste des actions de l'utilisateur ;
- `POST /approvals/{id}/approve`, qui déroule ces étapes :
  1. vérifier le propriétaire (404 sinon) et le statut `pending` (409 sinon), et que l'action n'a pas expiré (410) ;
  2. appeler `tool_router.call_tool(tool_name, arguments, user_id, source="approval")` avec les **arguments figés** ;
  3. passer le statut à `approved`, ou `failed` si le résultat est une erreur, stocker `result` et publier ;
  4. en tâche de fond (`BackgroundTasks`), lancer `run_tool_loop(tools=None, …)` avec le prompt « L'utilisateur a validé <outil>(<args>). Résultat : <result>. Annonce-le brièvement. », puis `message_repo` enregistre le message assistant en `publish=True` sur `/chat/{uid}`, sur le modèle de `queue/proaction_consumer.py` ;
- `POST /approvals/{id}/deny` : statut `denied` et publication, sans appel au LLM.
- Tests (`tests/test_routes.py`, style `authed_client` + patch) : 401, 404 pour l'action d'un autre utilisateur, 409 pour une action déjà décidée, 410 pour une action expirée, validation réussie avec `call_tool` appelé avec `source="approval"` et les bons arguments, statut persisté, publication appelée, refus sans exécution.

## Tâche 7 : Admin (web)

- `admin/src/components/chat/ApprovalCard.tsx` : outil et arguments lisibles, dates de création et d'expiration, boutons Autoriser et Refuser, états (en attente, en cours, validée, refusée, échouée, expirée).
- Hook `useApprovals` : `GET /agent/approvals?status=pending` au montage, et abonnement Mercure au topic `/users/{uid}/agent/approvals/{id}`, sur le modèle des abonnements `/contexts/${userId}` de `ChatWidget.tsx:486`.
- `ChatWidget.tsx` : cartes en attente en bas du fil de chat, et badge du nombre d'actions en attente sur l'onglet Mind.
- `mind/ToolCallList.tsx` et `mind/types.ts` : icône « en attente de validation » pour `status: "pending_approval"`.
- Tests Vitest co-localisés : rendu, clic Autoriser (appel POST), mise à jour via un message Mercure simulé, gestion d'erreur.

## Tâche 8 : Mobile (Android)

- `MaggieApiService.kt` : `getPendingApprovals()`, `approve(id)`, `deny(id)`, et le modèle `PendingApproval`.
- `ApprovalRepository` et son abonnement Mercure (via `MercureService`, avec le topic utilisateur réel, pas le joker `{userId}`), déclarés dans Koin.
- `ChatViewModel.kt` : état `pendingApprovals`, chargé à l'ouverture et mis à jour en temps réel ; actions `approve` et `deny`.
- `ui/components/ApprovalCard.kt` (Material 3), affiché dans `ChatMessageList.kt` au-dessus du champ de saisie.
- Tests JUnit + MockK du ViewModel : chargement, validation (état optimiste puis confirmé), erreur réseau.

## Tâche 9 : Documentation

- `agent-os/standards/agent/architecture.md` : section Skills (index et `get_skill`), section Sous-agents (format, `delegate`, sans récursion), section Politique et validations. Corriger aussi les affirmations périmées (« Conversation history kept in memory »).
- `agent-os/product/roadmap.md` : marquer comme amorcés « Confidence/autonomy system » et « Specialist agents system ».

---

## Références (réutiliser, ne pas réécrire)

- Boucle d'outils : `agent/app/llm/gateway.py:154-233` ; boucle en streaming : `agent/app/llm/streaming.py:120-294`
- Routage des outils (point de passage unique) : `agent/app/llm/tools.py:440-491` ; handlers natifs : `_NATIVE_HANDLERS` (`:421`)
- Skills et frontmatter : `agent/app/skills/index.py` ; exemples : `agent/data/skills/*.md`
- Persistance et publication : `agent/app/db/proaction_repository.py`, `agent/app/db/message_repository.py:40`, `agent/app/mercure/publisher.py`
- Exécution en tâche de fond avec message publié : `agent/app/queue/proaction_consumer.py`
- Événements AG-UI côté clients : `admin/src/hooks/useAgUiStream.ts`, `admin/src/components/chat/ChatWidget.tsx`, `mobile/.../ui/screens/chat/ChatViewModel.kt:188`
- Revue « suggérer puis valider » : `admin/src/modules/finance/RuleSuggestions.tsx`
- Tests : `agent/tests/test_streaming.py` (mock Anthropic), `test_tool_router.py`, `test_skill_index.py`, `test_mcp_client.py` (respx)

## Hors périmètre (specs suivantes)

- Push FCM : entité de tokens, envoi, notification d'une validation en attente.
- A2A : l'exécuteur agit en tant qu'utilisateur « a2a » avec tous les outils. Ici on se contente de `ask` → `deny`.
- Prompt caching (`cache_control`, date à sortir du préfixe stable) et montée du modèle par défaut.
- Annotations MCP `destructiveHint` côté Symfony.
- Bug de `SkillIndex.create`, qui ajoute une entrée en double quand il écrase un fichier existant (à noter).

## Vérification

1. `task agent:lint`, `task agent:format:check`, `task agent:test` ; `task admin:lint`, `task admin:typecheck`, `task admin:test` ; `cd mobile && ./gradlew testProdReleaseUnitTest` (lint mobile via la CI).
2. De bout en bout en local (`https://maggie.local/admin`) :
   - **Skills** : poser la question « on mange quoi ce soir ? ». Le Mind panel montre un appel à `get_skill(recipe-grocery-link)`, et aucun corps de skill ne figure dans le prompt (log du system prompt en debug).
   - **Sous-agent** : demander « fais-moi un point sur ma semaine : agenda, courses, budget ». On voit un appel `delegate(researcher)`, la métrique `maggie_llm_requests_total{call_type="subagent"}` augmente et la réponse est une synthèse.
   - **Validation** : dire « supprime l'événement X ». Maggie annonce qu'elle attend la validation et une carte apparaît sur le web et sur le mobile (installProdRelease). Cliquer Autoriser supprime l'événement (vérifier dans l'agenda) et Maggie envoie un message de confirmation. Recommencer en refusant : l'événement reste en place. Une validation laissée plus de 24 h passe à `expired`.
   - **Proaction** : programmer une proaction qui supprime quelque chose. Au déclenchement, une carte en attente apparaît au lieu d'une suppression directe.
3. Scénario prompt-lab (`scripts/prompt-lab/scenarios/`) : suppression soumise à validation, et question de recherche déléguée.
