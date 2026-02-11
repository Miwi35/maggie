# Personal AI Agent Platform — Requirements & Functional Specification

> **Version:** 1.0 — Initial Design  
> **Date:** February 2026  
> **Status:** Draft — Base for development

---

## 1. Vision & Objectives

### 1.1 Vision

Build a fully integrated personal AI assistant platform that acts as a central hub for managing daily life: meals & groceries, budget & investments, agenda & family planning, and fitness. The agent is proactive, learns from the user over time, and behaves as a consistent, personalized companion accessible through a unified chat interface in a mobile/web application.

### 1.2 Core Principles

- **Agent as a client, not embedded** — The AI agent is an external consumer of the API, just like the mobile app. It has no special backend coupling.
- **MCP as the universal interface** — All agents (personal + external) interact with the system via Model Context Protocol servers.
- **Proactive & adaptive** — The agent anticipates needs, learns preferences, and adjusts its behavior over time.
- **Transparent & controllable** — The user can inspect, modify, and override the agent's learned rules, memory, and scheduled tasks at any time.
- **Propose, never impose** — The agent suggests actions; the user decides. Confidence levels determine whether the agent acts directly, proposes, or stays silent.

---

## 2. High-Level Architecture

### 2.1 Architecture Overview
```
┌─────────────────────────────────────────────────────────────┐
│                     Mobile / Web App                        │
│         (Chat widget + Business views + Agent SDK)          │
└──────────────┬──────────────────────┬───────────────────────┘
               │ Mercure (SSE)        │ REST/GraphQL
               │                      │
┌──────────────┴──────────────────────┴───────────────────────┐
│                  API Symfony + API Platform                  │
│               (Business logic + Data layer)                  │
│                        + MCP Server 1                        │
└──────────────┬──────────────────────────────────────────────┘
               │ MCP Protocol
               │
┌──────────────┴──────────────────────────────────────────────┐
│                  Agent Hub (Python)                          │
│    (Orchestrator + Memory + Scheduler + LLM Gateway)        │
│                        + MCP Server 2                        │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 Component Summary

| Component | Tech Stack | Role |
|---|---|---|
| **API Backend** | Symfony 7 + API Platform | Business logic, data persistence, Mercure event publishing |
| **MCP Server 1 (Business)** | PHP (Symfony bundle or sidecar) | Exposes business API as MCP tools for any agent |
| **Agent Hub** | Python | Orchestrator, memory management, scheduler, LLM calls, channel routing |
| **MCP Server 2 (Agent Self-Management)** | Python | Exposes scheduling, memory, rules as MCP tools |
| **Mobile/Web App** | TBD (PWA or native) | UI for business modules + embedded chat + Agent SDK |
| **Mercure Hub** | Mercure (Caddy-based) | Real-time event distribution (SSE) |

### 2.3 Infrastructure

- Fully **Dockerized** — each component in its own container
- Monorepo structure for Symfony API + MCP Server 1
- Separate repository or service for the Python Agent Hub + MCP Server 2
- Shared Docker Compose for local development
- One database per service (or separate schemas)

---

## 3. Business Modules (Symfony API)

### 3.1 Meals & Groceries

**Entities:** Recipe, Ingredient, WeeklyMenu, ShoppingList, ShoppingListItem, Season

**Features:**
- Create, browse, and manage recipes with ingredients and nutritional data
- Plan weekly menus (7 days, multiple meals per day)
- Auto-generate shopping lists from selected menus
- Track ingredient seasonality (fruits, vegetables)
- Menu history — track what was eaten and when
- Agent-driven suggestions: propose menus based on history (avoid repetition), seasonality, nutritional goals, and user preferences

### 3.2 Budget & Finance

**Entities:** Account, Transaction, Category, Budget, BudgetRule, Investment, InvestmentGoal

**Features:**
- Track income and expenses with categorization
- Define monthly/weekly budgets per category
- Budget alerts (approaching or exceeding limits)
- Investment tracking — portfolio, goals, maturity dates, performance
- Financial planning — savings goals, projections
- Agent-driven insights: spending pattern analysis, budget optimization suggestions, investment maturity reminders

### 3.3 Agenda & Family Planning

**Entities:** Event, Calendar, Recurrence, ChildCustodySchedule, Reminder

**Features:**
- Full calendar management (CRUD events, recurring events)
- Child custody schedule (week parity-based or custom)
- Conflict detection (overlapping events, events during custody periods)
- Reminders with configurable lead times
- Agent-driven planning: pre-fill weekly schedules, detect conflicts proactively, suggest optimizations

### 3.4 Fitness & Health

**Entities:** Workout, WorkoutPlan, Exercise, HealthGoal, BodyMetric

**Features:**
- Track workouts and exercise sessions
- Define health/fitness goals (weight loss, muscle gain, endurance)
- Track body metrics over time (weight, measurements, performance)
- Link with nutrition module for holistic health management
- Agent-driven coaching: adapt meal suggestions based on training schedule, suggest workout adjustments based on progress

---

## 4. MCP Server 1 — Business Tools

### 4.1 Purpose

Expose the Symfony API as MCP tools consumable by any MCP-compatible agent (the personal agent, Claude Desktop, third-party agents).

### 4.2 Tool Categories

**Agenda Tools:**
- `get_upcoming_events(days?: int)` — List upcoming events
- `get_events_by_date(date: string)` — Events for a specific date
- `create_event(title, date, time, duration?, description?)` — Create an event
- `update_event(id, ...)` — Modify an event
- `delete_event(id)` — Remove an event
- `check_conflicts(date, time, duration)` — Detect scheduling conflicts
- `get_custody_schedule(week?: int)` — Get custody information

**Meals & Groceries Tools:**
- `suggest_weekly_menu(preferences?)` — AI-assisted menu suggestion
- `get_menu_history(weeks?: int)` — Past menus
- `create_shopping_list(menu_id?)` — Generate shopping list from menu
- `get_shopping_list(id?)` — Retrieve current shopping list
- `add_to_shopping_list(items[])` — Add items
- `get_seasonal_ingredients(month?: int)` — Current seasonal produce

**Budget & Finance Tools:**
- `get_budget_status(month?: string)` — Current budget overview
- `add_expense(amount, category, description)` — Record an expense
- `add_income(amount, source, description)` — Record income
- `get_spending_by_category(period?)` — Spending breakdown
- `get_investments_overview()` — Portfolio summary
- `get_investment_alerts()` — Upcoming maturities, performance alerts

**Fitness & Health Tools:**
- `get_workout_plan(week?: int)` — Current training plan
- `log_workout(type, duration, details)` — Record a session
- `get_health_goals()` — Current goals and progress
- `get_body_metrics(period?)` — Metrics history

### 4.3 Authentication & Scopes

- All MCP tools require authentication (API tokens / OAuth2)
- **Internal scope** (personal agent): full read/write access to all tools
- **External scope** (third-party agents): configurable per-agent, default read-only
- Scope management via the web/mobile app admin panel

---

## 5. Agent Hub (Python)

### 5.1 Purpose

Central orchestrator that serves as the brain of the personal assistant. Manages personality, memory, scheduling, specialist agents, and communication with the LLM.

### 5.2 Core Components

#### 5.2.1 LLM Gateway

- Calls Claude API (Anthropic) as the primary reasoning engine
- Constructs prompts: **system prompt (personality)** + **memory context** + **conversation history** + **user message**
- Injects available MCP tools as Claude tool definitions
- Handles tool call execution: routes tool calls to the appropriate MCP server
- Model-agnostic design: can switch to another LLM provider without architectural changes

#### 5.2.2 Personality Engine

- Centralized system prompt defining the agent's identity, tone, style, and behavior
- Consistent across all interactions regardless of context
- Configurable parameters: name, language, formality level, humor, verbosity
- Channel-aware formatting: adapts output length and style to the interaction context (chat = concise, email = structured) while maintaining the same personality

#### 5.2.3 Memory System

**Technology:** Mem0 (or equivalent) for factual/episodic memory + relational DB for behavioral rules

**Memory Types:**

| Type | Content | Storage | Example |
|---|---|---|---|
| **Factual** | User preferences, habits, personal info | Mem0 (vector + graph) | "Vegetarian on Mondays", "Child custody on even weeks" |
| **Episodic** | Past interactions, events, decisions | Mem0 (vector) | "Last week we ate pasta 3 times", "Exceeded restaurant budget in January" |
| **Behavioral Rules** | Learned and manual confidence rules | Relational DB (PostgreSQL) | "Grocery additions = act directly", "Agenda changes = propose first" |
| **Conversation** | Recent chat history | In-memory + DB | Last N exchanges for context continuity |

**Memory Lifecycle:**
- Auto-extraction: after every N exchanges, the LLM summarizes key facts and stores them
- Contradiction detection: new facts that contradict existing ones trigger updates
- Decay: episodic memories can fade over time (configurable)
- User control: full CRUD on all memory types via the app dashboard

#### 5.2.4 Scheduler / Task Engine

- Manages self-planned tasks and routines
- Cron-based job runner that depiles scheduled tasks
- On trigger: loads the task context, calls the LLM with appropriate personality + memory, executes resulting actions via MCP
- Supports one-shot tasks ("remind me in 3 days") and recurring routines ("every Friday at 2pm suggest a weekly menu")
- Tasks are first-class entities: the agent can create, modify, cancel, and list its own tasks via MCP Server 2

#### 5.2.5 Specialist Agents System

**Architecture:** Single LLM, multiple role configurations stored as YAML files
```
agents/
  principal.yaml      → Main orchestrator personality + general tools
  nutrition.yaml      → Nutrition specialist prompt + health tools
  courses.yaml        → Meal planning specialist + grocery tools
  budget.yaml         → Financial advisor prompt + finance tools
  sport.yaml          → Fitness coach prompt + workout tools
```

**Each specialist file defines:**
- A specialized system prompt
- A list of authorized MCP tools
- Specific memory scopes (what context to inject)

**Orchestration Pattern (default):** Hub & spoke
1. Main agent receives user request
2. Identifies which specialists are needed
3. Calls each specialist sequentially with appropriate context
4. Synthesizes results into a unified response

**Agent Team Pattern (future):** For complex cross-domain decisions (e.g., weekly menu considering nutrition + sport + budget + preferences), specialists can exchange information in a structured multi-turn discussion orchestrated by the main agent. To be implemented when hub & spoke shows limitations.

#### 5.2.6 Confidence & Autonomy System

**Three levels of agent autonomy:**

| Level | Behavior | Trigger |
|---|---|---|
| **Act** | Execute directly, notify user of result | High confidence — routine, reversible, low-risk actions |
| **Propose** | Show the user what it wants to do, wait for validation | Medium confidence — impactful but not critical actions |
| **Silent** | Do nothing, filter out the event | Event deemed not relevant or not actionable |

**Confidence Rules:**
- Each action type has a default confidence level
- Rules are learned automatically: if the user validates the same type of proposal 5+ times without modification, confidence increases toward Act
- If the user says "stop asking me this", the rule is updated immediately
- User can manually set, modify, or delete confidence rules via the app dashboard
- Rules stored in relational DB: action_type, confidence_level, source (learned/manual), last_updated, trigger_count

#### 5.2.7 Event Listener (Mercure Subscriber)

- The Agent Hub subscribes to Mercure topics for real-time awareness of system changes
- Every event from the API (appointment created, expense added, etc.) is received by the agent

**Two-tier filtering system:**

| Tier | Model | Purpose | Cost |
|---|---|---|---|
| **Tier 1 — Quick Filter** | Small model (Claude Haiku or local via Ollama) | Decide: is this event worth reacting to? yes/no | Very low |
| **Tier 2 — Full Reasoning** | Large model (Claude Sonnet/Opus) | Reason about the event, decide on action, generate response | Higher |

95% of events are filtered out at Tier 1. Only relevant events reach Tier 2. The filter uses behavioral rules and memory for informed decisions.

---

## 6. MCP Server 2 — Agent Self-Management Tools

### 6.1 Purpose

Expose the agent's internal capabilities as MCP tools. Only accessible by the personal agent itself (not external agents).

### 6.2 Tools

**Scheduling Tools:**
- `schedule_task(action, trigger_time, context?, recurrence?)` — Plan a future task
- `list_scheduled_tasks(status?: active|completed|cancelled)` — View planned tasks
- `update_task(id, ...)` — Modify a scheduled task
- `cancel_task(id)` — Cancel a task
- `create_routine(name, schedule, actions[])` — Create a recurring multi-step workflow

**Memory Tools:**
- `store_memory(type, content, metadata?)` — Save a new memory/fact
- `search_memory(query, type?)` — Retrieve relevant memories
- `update_memory(id, content)` — Correct a memory
- `delete_memory(id)` — Remove a memory
- `get_user_profile()` — Full summary of known user facts

**Behavioral Rules Tools:**
- `get_confidence_rules(action_type?)` — List current rules
- `update_confidence_rule(action_type, level)` — Modify a rule
- `create_confidence_rule(action_type, level, conditions?)` — Add a manual rule
- `delete_confidence_rule(id)` — Remove a rule

**UI Control Tools:**
- `get_current_page()` — Know where the user is in the app
- `get_available_actions()` — Actions available on current page
- `navigate_to(route, params?)` — Navigate the user to a page
- `fill_form(form_id, data)` — Pre-fill a form
- `show_notification(message, type?)` — Display an in-app notification
- `request_confirmation(message, actions[])` — Show a confirmation dialog

---

## 7. Agent Interaction Modes

### 7.1 Mode Plan (Visible UI Piloting)

- The agent takes control of the UI
- Navigates between pages, fills forms, highlights elements
- The user sees every action in real-time
- A final validation step is required before committing
- Commands sent via Mercure as structured UI events
- **Use case:** Complex actions, new action types, medium-confidence operations, user preference

### 7.2 Mode Act (Background Execution)

- The agent calls the API directly via MCP Server 1
- Changes are persisted immediately
- The UI updates in real-time via Mercure (standard data sync, same as any API change)
- User sees the result appear live without manual refresh
- A brief confirmation message is sent in the chat
- **Use case:** Routine actions, high-confidence operations, user preference

### 7.3 Mode Selection

- Default mode determined by confidence level (High → Act, Medium → Plan)
- User can override at any time via a toggle in the chat interface
- The agent can suggest a mode based on context
- User preferences for mode per action type are stored in behavioral rules

---

## 8. Front-End — Mobile/Web Application

### 8.1 Business Views

- **Dashboard** — Overview of upcoming events, budget status, today's menu, fitness summary
- **Agenda** — Calendar view with custody indicators, event management
- **Meals** — Weekly menu planner, recipe browser, shopping lists
- **Budget** — Expense tracking, category breakdowns, investment overview
- **Fitness** — Workout log, goals tracking, metrics charts

### 8.2 Agent Chat Interface

- Embedded chat widget accessible from any screen
- Text input + optional voice input (speech-to-text)
- Real-time message streaming via Mercure
- Push notifications when the agent initiates contact (proactive messages)
- Mode toggle (Plan / Act) visible in the chat UI
- Typing indicator when the agent is processing

### 8.3 Agent Dashboard (Admin Panel)

- **Memory Inspector** — View, edit, delete all stored memories and facts
- **Behavioral Rules Manager** — View learned and manual rules, adjust confidence levels, delete rules
- **Scheduled Tasks Viewer** — See all planned tasks and routines, modify or cancel them
- **Activity Log** — History of agent actions: what it did, why, which mode, outcome
- **Personality Settings** — Adjust tone, verbosity, name, behavioral guidelines
- **Specialist Agents Config** — View and edit specialist YAML configurations

### 8.4 Agent Front-End SDK

A reusable JavaScript/TypeScript library integrated into the front-end application.

**Responsibilities:**
- Connect to Mercure and listen for agent UI commands (Mode Plan)
- Maintain a registry of available actions per page/screen and report to the agent
- Execute UI commands: navigation, form filling, highlighting, notifications, confirmations
- Manage the chat widget: message display, input, streaming, push notifications
- Handle real-time data sync for standard Mercure events (Mode Act)
- Provide hooks/events for the host application to customize behavior

**Interface:**
```typescript
interface AgentSDK {
  // Initialization
  init(config: { mercureUrl: string, authToken: string, agentEndpoint: string }): void

  // Page context
  registerPage(pageId: string, availableActions: Action[]): void
  
  // UI command handlers
  onNavigate(handler: (route: string, params: object) => void): void
  onFillForm(handler: (formId: string, data: object) => void): void
  onNotification(handler: (message: string, type: string) => void): void
  onConfirmation(handler: (message: string, actions: object[]) => void): void

  // Chat
  sendMessage(text: string): void
  onAgentMessage(handler: (message: AgentMessage) => void): void
  
  // Mode
  setMode(mode: 'plan' | 'act'): void
  getMode(): 'plan' | 'act'
}
```

---

## 9. Real-Time Architecture (Mercure)

### 9.1 Role

Mercure is the central nervous system of the platform. All real-time communication flows through it.

### 9.2 Event Flows

| Producer | Event | Consumers |
|---|---|---|
| API Symfony | appointment.created/updated/deleted | Mobile App, Agent Hub |
| API Symfony | expense.created, budget.threshold_reached | Mobile App, Agent Hub |
| API Symfony | menu.validated, shopping_list.generated | Mobile App, Agent Hub |
| Agent Hub | agent.message (chat response) | Mobile App |
| Agent Hub | agent.ui_command (navigate, fill_form...) | Mobile App (SDK) |
| Agent Hub | agent.notification (proactive alert) | Mobile App |
| Mobile App | user.page_changed (current context) | Agent Hub |

### 9.3 Topic Structure
```
/api/events/{entity_type}/{id}          → Business data changes
/agent/chat/{user_id}                   → Chat messages
/agent/ui/{user_id}                     → UI commands (Mode Plan)
/agent/notifications/{user_id}          → Proactive notifications
/app/context/{user_id}                  → App state (current page, available actions)
```

---

## 10. Proactive Agent Behaviors

### 10.1 Scheduled Routines (Examples)

| Routine | Schedule | Behavior |
|---|---|---|
| Weekly menu suggestion | Friday 2:00 PM | Analyze last 3 weeks of menus + seasonal ingredients + nutritional goals → propose menu |
| Shopping list reminder | Saturday 8:00 AM | If menu validated, send list. If not, remind to validate menu first |
| Weekly budget review | Sunday evening | Summarize week spending, compare to budget, highlight overruns |
| Agenda preview | Sunday evening | Summarize upcoming week, flag custody days, detect conflicts |
| Investment check | 1st of month | Review portfolio performance, flag upcoming maturities |
| Fitness check-in | Based on workout plan | After expected workout day, ask how it went if no log recorded |

### 10.2 Event-Driven Reactions (Examples)

| Event | Potential Reaction |
|---|---|
| New appointment during custody week | "This falls during your custody week, want me to check alternatives?" |
| Large expense recorded | If approaching limit: "You've used 85% of your dining budget this month" |
| Menu validated | Auto-generate shopping list |
| No workout logged by end of planned day | Gentle check-in: "Did you manage to train today?" |
| Investment maturity in 30 days | Reminder with reinvestment options |

---

## 11. Security & Authentication

### 11.1 API Authentication

- OAuth2 / JWT-based authentication for all API access
- Separate tokens for: mobile app, personal agent, external agents
- Token scopes define access levels per module and per action (read/write)

### 11.2 MCP Authentication

- MCP Server 1 (Business): accepts internal and external agent tokens with scope validation
- MCP Server 2 (Agent Self-Management): accepts only the personal agent token, never exposed externally

### 11.3 Mercure Authentication

- JWT-based authorization for subscriptions and publications
- Topic-level access control: personal agent subscribes to all business events, external agents cannot

---

## 12. Development Phases

### Phase 1 — Foundation
- Symfony API + API Platform with one business module (Agenda)
- MCP Server 1 with agenda tools
- Agent Hub (Python) with basic LLM gateway, personality, simple memory
- Basic mobile/web app with agenda view + chat widget
- Mercure integration for real-time sync
- Mode Act only

### Phase 2 — Core Modules
- Add Meals & Groceries module
- Add Budget & Finance module
- MCP Server 2 with scheduling and memory tools
- Scheduler with basic routines (menu suggestion, shopping list)
- Confidence/autonomy system
- Mode Plan implementation + Agent SDK

### Phase 3 — Intelligence
- Mem0 integration for advanced memory management
- Two-tier event filtering (small model + large model)
- Specialist agents system (nutrition, budget, sport)
- Behavioral rules learning (auto-confidence adjustment)
- Agent dashboard (memory inspector, rules manager, activity log)

### Phase 4 — Fitness & Advanced Features
- Fitness & Health module
- Cross-domain specialist collaboration (menu considering sport + nutrition + budget)
- Investment planning features
- Routine self-creation by the agent
- Voice input in chat
- Advanced agent team pattern for complex workflows

---

## 13. Technical Constraints & Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Backend framework | Symfony 7 + API Platform | Proven, scalable, excellent API tooling, developer expertise |
| Real-time | Mercure | Native API Platform integration, SSE-based, lightweight |
| Agent language | Python | Best AI/ML ecosystem, mature LLM SDKs, agent tooling |
| LLM Provider | Claude API (Anthropic) | Strong reasoning, native tool-use, model-agnostic design allows switching |
| Small model (filter) | Claude Haiku or Ollama local | Cost-effective filtering, fast inference |
| Memory | Mem0 + PostgreSQL | Mem0 for factual/episodic, PostgreSQL for behavioral rules |
| MCP Protocol | Model Context Protocol | Emerging standard, interoperable, future-proof |
| Infrastructure | Docker Compose | Simple, reproducible, service isolation |
| Async processing | Symfony Messenger (API) + Python task queue (Agent) | Battle-tested async per stack |