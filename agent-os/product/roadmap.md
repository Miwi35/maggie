# Product Roadmap

## Phase 1: Foundation (MVP)

- Symfony 7 API + API Platform with the Agenda business module
- MCP Server 1 with agenda tools (events, conflicts, custody schedule)
- Agent Hub (Python) with basic LLM gateway, personality engine, and simple memory
- Web app using API Platform Admin with agenda view + embedded chat widget
- Basic native Android app with agenda view + chat
- Mercure integration for real-time sync between all components
- Mode Act only (agent executes directly via API)

## Phase 2: Core Modules

- Add Meals & Groceries module (recipes, weekly menus, shopping lists, seasonality) — **done** (`api/modules/cookbook`, `api/modules/grocery`)
- Add Budget & Finance module (expenses, income, budgets, investments) — **in progress**: MVP foundation (Account, Category, Transaction) shipped full-stack in `api/modules/finance`; Envelope is the remaining slice. See [finance-roadmap](finance-roadmap.md) and the [finance-mvp spec](../specs/2026-07-07-finance-mvp/shape.md)
- MCP Server 2 with scheduling and memory self-management tools
- Scheduler with basic routines (weekly menu suggestion, shopping list reminder)
- Confidence/autonomy system (Act / Propose / Silent levels) — **started**: a tool policy (`agent/data/policy.yaml`) gives each call `allow` / `ask` / `deny`, and an `ask` waits for the user's answer as a pending action on web and mobile (Act and Propose; Silent and learned confidence remain). See [agent architecture](../standards/agent/architecture.md) and the [agent-skills-subagents-approvals spec](../specs/2026-09-30-1151-agent-skills-subagents-approvals/plan.md)
- Mode Plan implementation (visible UI piloting) + Agent Front-End SDK

## Phase 3: Intelligence

- Mem0 integration for advanced factual/episodic memory management
- Two-tier event filtering (small model quick filter + large model full reasoning)
- Specialist agents system (nutrition, budget, sport specialists as YAML configs) — **started**: sub-agents are Markdown files (`agent/data/agents/*.md`) Maggie hands tasks to through `delegate`; one read-only `researcher` ships, the specialists themselves remain
- Behavioral rules learning (auto-confidence adjustment from user feedback)
- Agent dashboard (memory inspector, rules manager, activity log, personality settings)

## Phase 4: Fitness & Advanced Features

- Fitness & Health module (workouts, goals, body metrics)
- Cross-domain specialist collaboration (menu considering sport + nutrition + budget)
- Investment planning features (portfolio tracking, maturity reminders)
- Routine self-creation by the agent
- Voice input in chat (speech-to-text)
- Advanced agent team pattern for complex multi-domain workflows
