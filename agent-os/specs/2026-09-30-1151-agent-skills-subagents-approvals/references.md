# Références : Agent Hub, skills, sous-agents, validations

## Implémentations existantes

### Boucle d'outils (non-streaming)

- **Emplacement :** `agent/app/llm/gateway.py:154-233` (`_run_tool_loop`)
- **Intérêt :** à extraire dans `app/llm/runner.py`, pour qu'elle serve à la fois à `chat`, `proaction`, aux sous-agents et au message d'annonce après validation.
- **Points clés :** 5 itérations maximum, outils exécutés séquentiellement, `record_llm_usage` et le compteur `TOOL_CALLS`.

### Boucle en streaming (AG-UI)

- **Emplacement :** `agent/app/llm/streaming.py:78-294`
- **Intérêt :** passer `source="chat_stream"` à `call_tool`. Le `CUSTOM tool_result` (`:234`) doit exposer `pending_approval`.
- **Points clés :** événements SSE `data: {json}` ; appel Haiku `_resolve_context` pour router le contexte.

### Routage des outils

- **Emplacement :** `agent/app/llm/tools.py:440-491` (`ToolRouter`), `_NATIVE_HANDLERS` (`:421`)
- **Intérêt :** tous les appels d'outils y passent. C'est là qu'on place la garde de politique et qu'on ajoute `delegate`.
- **Points clés :** handlers `async (arguments, user_id) -> str` en JSON ; les outils MCP sont convertis au format Anthropic.

### Skills

- **Emplacement :** `agent/app/skills/index.py`, `agent/data/skills/*.md`
- **Intérêt :** index à simplifier. Le parsing du frontmatter est à extraire pour le réutiliser dans les sous-agents.
- **Points clés :** rebuild au démarrage (`app/main.py:49-54`), Mercure `/skills/{uid}`, endpoints REST dans `routes.py:305-355`.

### Persistance et publication

- **Emplacement :** `agent/app/db/proaction_repository.py`, `agent/app/db/message_repository.py:40`, `agent/app/mercure/publisher.py`
- **Intérêt :** modèle à suivre pour `pending_action_repository` (`create_all`, publication après chaque changement).

### Exécution en tâche de fond avec message publié

- **Emplacement :** `agent/app/queue/proaction_consumer.py`
- **Intérêt :** même enchaînement pour annoncer le résultat d'une validation : appel au LLM, `message_repo` en `publish=True` sur `/chat/{uid}`.

### Chat web

- **Emplacement :** `admin/src/hooks/useAgUiStream.ts`, `admin/src/components/chat/ChatWidget.tsx` (abonnement Mercure `:486`), `admin/src/components/mind/ToolCallList.tsx`
- **Intérêt :** y intégrer les cartes de validation, le badge et le statut `pending_approval`.

### Chat mobile

- **Emplacement :** `mobile/.../ui/screens/chat/ChatViewModel.kt:188` (`handleStreamEvent`), `ChatMessageList.kt`, `data/api/MaggieApiService.kt`, `MercureService.kt`
- **Intérêt :** état `pendingApprovals` et `ApprovalCard`.
- **Attention :** l'abonnement actuel `/chat/{userId}` est un joker URI-template. Utiliser l'identifiant réel de l'utilisateur.

### Parcours « suggérer puis valider »

- **Emplacement :** `admin/src/modules/finance/RuleSuggestions.tsx`
- **Intérêt :** parcours existant où l'utilisateur valide des propositions, dont on peut reprendre l'UX.

### Tests

- `agent/tests/test_streaming.py` : mock du client Anthropic (`MagicMock` / `AsyncMock`).
- `agent/tests/test_tool_router.py` : patch de `mcp_client`, décompte des outils natifs.
- `agent/tests/test_skill_index.py` : `SkillIndex(tmp_path)`.
- `agent/tests/test_mcp_client.py` : respx.
- `agent/tests/conftest.py` : fixtures `client` et `authed_client`.
