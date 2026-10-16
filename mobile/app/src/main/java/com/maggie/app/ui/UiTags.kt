package com.maggie.app.ui

import java.time.LocalDate

/**
 * The ids the Maestro journeys address the app by (MAG-98).
 *
 * Maestro reads the view hierarchy through UiAutomator, where a Compose node has
 * no resource id of its own — unless its semantics root opts in. That opt-in is
 * [uiTagRoot], and it goes on **every window**, not once on the activity: a
 * `Dialog` or a `ModalBottomSheet` is a separate semantics owner, and a tag added
 * inside one without a root of its own is invisible to Maestro. A `testTag` from
 * this file is then what `id: "…"` matches in `e2e/mobile/flows/`.
 *
 * Why tags and not the visible text: a journey anchored on « Demander à
 * Maggie... » breaks the day the placeholder is reworded, and the failure reads
 * as a broken chat rather than as a renamed string. Tags also disambiguate what
 * the screen says twice — that placeholder is both the collapsed bar and the
 * sheet's text field.
 *
 * Renaming one here means renaming it in the flows: `task e2e:mobile:lint` checks
 * that every id a flow uses is declared below, and that every window carrying one
 * of these has its own [uiTagRoot] — so both mistakes fail in seconds instead of
 * on an emulator.
 *
 * A tag ending in `_PREFIX` is not an id on its own: the calendar needs « this
 * event, on this day » to be addressable, so those ids are a prefix followed by
 * an ISO date, built by the helpers below. A flow spells them with the date it
 * computed (`calendar_day_${DAY}`) or a regex (`calendar_span_.*`), and the lint
 * accepts any id that starts with a declared prefix.
 */
object UiTags {
    /** Login — the single button the screen offers, whatever door is behind it. */
    const val LOGIN_SIGN_IN = "login_sign_in"

    /** Dashboard — the root, so a journey can wait for the screen itself. */
    const val DASHBOARD = "dashboard"

    /**
     * The way into the conversation, on every main screen: the collapsed bar under the
     * content, or — on a window too short for it (MAG-35) — the button at the top of
     * the rail. Same tag either way, so a journey taps it in both layouts.
     */
    const val CHAT_OPEN = "chat_open"

    /** The microphone, which opens the chat sheet in voice mode. */
    const val CHAT_MIC = "chat_mic"

    /**
     * The icon beside the chat's search, which opens the threads sheet (MAG-342). Threads
     * are how the one conversation is filed, so the way in is a discreet icon in its header
     * and not a button of its own on the bar.
     */
    const val CHAT_THREADS = "chat_threads"

    /**
     * The two tabs at the top of the chatbox, and what the Mind one holds (MAG-403): the badge
     * of held actions, Maggie's state, the activity list and one row per tool call.
     */
    const val CHAT_TAB = "chat_tab"
    const val MIND_TAB = "mind_tab"
    const val MIND_BADGE = "mind_badge"
    const val MIND_CONTENT = "mind_content"
    const val MIND_STATE = "mind_state"
    const val MIND_ACTIVITY = "mind_activity"
    const val MIND_TOOL_CALL = "mind_tool_call"

    /** The « Supprimer » of the confirmation that a thread takes its messages with it. */
    const val THREAD_DELETE_CONFIRM = "thread_delete_confirm"

    /** The « Supprimer » of the menu a long press on a message opens. */
    const val MESSAGE_DELETE = "message_delete"

    /** The « Modifier » of the same menu, on the last message of the user only (MAG-320). */
    const val MESSAGE_EDIT = "message_edit"

    /** The thumbs under each answer of Maggie's (MAG-23). */
    const val MESSAGE_THUMB_UP = "message_thumb_up"
    const val MESSAGE_THUMB_DOWN = "message_thumb_down"

    /** The chat sheet: its input and its two buttons. */
    const val CHAT_INPUT = "chat_input"
    const val CHAT_SEND = "chat_send"

    /** The send button while Maggie answers: it stops her and brings the question back to the input (MAG-320). */
    const val CHAT_STOP = "chat_stop"

    /** The assistant overlay's screenshot, which the user circles to pick the zone to send (MAG-316). */
    const val ASSIST_SCREENSHOT = "assist_screenshot"

    /** « Tout l'écran » under it: drops the circled zone. */
    const val ASSIST_SCREENSHOT_RESET = "assist_screenshot_reset"

    /** In place of the screenshot: « en cours… », or why there is none (MAG-214). */
    const val ASSIST_SCREENSHOT_STATUS = "assist_screenshot_status"
    const val CHAT_CLOSE = "chat_close"

    /** The conversation's list, and the « ↓ nouvelle réponse » button shown when the user scrolled away from it (MAG-348). */
    const val CHAT_MESSAGES = "chat_messages"
    const val CHAT_JUMP_TO_LATEST = "chat_jump_to_latest"

    /** The line that keeps the user's answer to a request of Maggie's in the thread (MAG-336). */
    const val CHAT_DECISION = "chat_decision"

    /** « Réessayer », under a question that got no answer (MAG-363). */
    const val CHAT_RETRY = "chat_retry"

    /** The voice bar's state line — « Maggie parle... » and friends. */
    const val VOICE_STATE = "voice_state"

    /** The voice bar's mic — held down to talk. */
    const val VOICE_MIC = "voice_mic"

    /** The voice bar's live text, as the phone's own recognition hears it (MAG-222). */
    const val VOICE_PARTIAL = "voice_partial"

    /** Top bar — the burger that opens the navigation drawer. */
    const val NAV_MENU = "nav_menu"

    /**
     * Settings > Voix — the row that makes Maggie the system assistant (MAG-30).
     * Addressed by its tag and not by its state, which is the point: the journey
     * reads the state off the row.
     */
    const val SETTINGS_ASSISTANT_ROLE = "settings_assistant_role"

    /** Navigation drawer — one entry per destination, suffixed by its route. */
    const val DRAWER_ITEM_PREFIX = "drawer_"

    /**
     * Navigation rail — what replaces the burger and the drawer from 600 dp wide
     * (MAG-35). The rail itself, so a test can say « this window has no rail », and
     * one entry per destination, suffixed by its route like the drawer's.
     */
    const val NAV_RAIL = "nav_rail"
    const val RAIL_ITEM_PREFIX = "rail_"

    /** The conversation kept on screen beside the content on a wide window (MAG-35). */
    const val CHAT_PANEL = "chat_panel"

    /** The qualification component (MAG-479): the « Obligatoire » switch and one chip per type. */
    const val QUALIFICATION_MANDATORY = "qualification_mandatory"
    const val QUALIFICATION_PROVENANCE = "qualification_provenance"
    const val QUALIFICATION_REQUALIFIED = "qualification_requalified"
    fun qualificationKind(name: String) = QUALIFICATION_KIND_PREFIX + name.lowercase()

    /** Category screen — the « Rente » switch (MAG-257). */
    const val CATEGORY_PASSIVE_INCOME = "category_passive_income"

    /** Category screen — the parent field, the search it opens, and the deletion at the bottom (MAG-353). */
    const val CATEGORY_NAME = "category_name"
    const val CATEGORY_PARENT = "category_parent"
    const val CATEGORY_PARENT_SEARCH = "category_parent_search"
    const val CATEGORY_DELETE = "category_delete"

    /** Category screen — the icon field and the full-screen grid it opens (MAG-474). */
    const val CATEGORY_ICON = "category_icon"
    const val CATEGORY_ICON_SEARCH = "category_icon_search"
    const val CATEGORY_ICON_TILE_PREFIX = "category_icon_tile_"

    /** Budgets — the « Hors budget mensuel » section, annual and one-off envelopes (MAG-414). */
    const val BUDGET_OUTSIDE_MONTHLY = "budget_outside_monthly"

    /** Budgets — the lines an envelope weighs on, opened by touching it on the phone (MAG-418). */
    const val BUDGET_DETAIL = "budget_detail"

    /** Budgets — the seasons of a new monthly envelope, on the tablet (MAG-415). */
    const val BUDGET_SEASONS = "budget_seasons"

    /**
     * Budgets, on the tablet (MAG-419) — the tree of categories, one row per category suffixed by its
     * name (`budget_node_Obsydian`), and the editor of the one touched, in the right pane.
     */
    const val BUDGET_TREE = "budget_tree"
    const val BUDGET_NODE_PREFIX = "budget_node_"
    const val BUDGET_EDITOR = "budget_editor"

    fun budgetNode(name: String) = BUDGET_NODE_PREFIX + name

    /**
     * Budgets — « Ajuster » (MAG-461): the entry in the top bar, the panel, one row per envelope
     * (suffixed by the category name), its suggestion and « Appliquer », « Tout appliquer » and « Enregistrer ».
     */
    const val BUDGET_ADJUST_OPEN = "budget_adjust_open"
    const val BUDGET_ADJUST = "budget_adjust"
    const val BUDGET_ADJUST_ROW_PREFIX = "budget_adjust_row_"
    const val BUDGET_ADJUST_SUGGESTION_PREFIX = "budget_adjust_suggestion_"
    const val BUDGET_ADJUST_APPLY_PREFIX = "budget_adjust_apply_"
    const val BUDGET_ADJUST_PLUS_PREFIX = "budget_adjust_plus_"
    const val BUDGET_ADJUST_MINUS_PREFIX = "budget_adjust_minus_"
    const val BUDGET_ADJUST_SLIDER_PREFIX = "budget_adjust_slider_"
    const val BUDGET_ADJUST_APPLY_ALL = "budget_adjust_apply_all"
    const val BUDGET_ADJUST_SAVE = "budget_adjust_save"

    fun budgetAdjustRow(name: String) = BUDGET_ADJUST_ROW_PREFIX + name
    fun budgetAdjustApply(name: String) = BUDGET_ADJUST_APPLY_PREFIX + name

    /** A transaction's one-off envelope on its detail screen, and the full-screen choice it opens (MAG-414). */
    const val TRANSACTION_ONE_OFF_ENVELOPE = "transaction_one_off_envelope"
    const val TRANSACTION_ONE_OFF_ENVELOPE_SEARCH = "transaction_one_off_envelope_search"

    /** A transaction's category on its detail screen, and the full-screen search it opens (MAG-411). */
    const val TRANSACTION_CATEGORY = "transaction_category"
    const val TRANSACTION_CATEGORY_SEARCH = "transaction_category_search"

    /** The list and the detail of a screen drawn side by side on a wide window (MAG-263). */
    const val LIST_PANE = "list_pane"
    const val DETAIL_PANE = "detail_pane"

    /** The « Découvert prévu » banner, wherever a screen draws it (MAG-280). */
    const val TREASURY_BANNER = "treasury_banner"

    /** Trésorerie — the advice's « Préparer l'avance », and the field of the overdraft limit. */
    const val TREASURY_PREPARE_ADVANCE = "treasury_prepare_advance"
    const val TREASURY_OVERDRAFT_LIMIT = "treasury_overdraft_limit"
    /** Savings advances — the screen root, the total, the retry of a failed load, the salary day and the « Je l'ai fait » of an advance. */
    const val SAVINGS_ADVANCES = "savings_advances"
    const val SAVINGS_ADVANCES_TOTAL = "savings_advances_total"
    const val SAVINGS_ADVANCES_RETRY = "savings_advances_retry"
    const val SAVINGS_ADVANCES_SALARY_DAY = "savings_advances_salary_day"
    const val SAVINGS_ADVANCES_SAVE_DAY = "savings_advances_save_day"
    const val SAVINGS_ADVANCE_DONE_PREFIX = "savings_advance_done_"

    /**
     * Finance menus (MAG-426) — the tabs row of the phone and the rail of the tablet (one tab or
     * item per menu, suffixed by its key), and on a menu's summary page one block per aspect,
     * suffixed by the route it opens.
     */
    const val FINANCE_MENU_TABS = "finance_menu_tabs"
    const val FINANCE_MENU_RAIL = "finance_menu_rail"
    const val FINANCE_MENU_PREFIX = "finance_menu_"
    const val FINANCE_BLOCK_PREFIX = "finance_block_"
    const val ACCOUNT_LAST_SYNC = "account_last_sync"
    const val REFERENCE_SALARY_LINK = "reference_salary_link"
    const val REFERENCE_SHARE = "reference_share"

    /**
     * Finance dashboard — the two score rings (suffixed `week` / `month`), the reasons they open,
     * the rhythm curve, its Semaine/Mois choice and a debit marked on it
     * (suffixed by its ISO date) (MAG-417).
     */
    const val FINANCE_SCORE_RING_PREFIX = "finance_score_ring_"
    const val FINANCE_SCORE_REASONS = "finance_score_reasons"
    const val FINANCE_RHYTHM_CURVE = "finance_rhythm_curve"
    const val FINANCE_RHYTHM_PERIOD_PREFIX = "finance_rhythm_period_"
    const val FINANCE_RHYTHM_DEBIT_PREFIX = "finance_rhythm_debit_"

    /**
     * Finance dashboard — the three tiles (Comptes, Budget, Planification), each leading to its menu,
     * the figures the Budget tile gives, one bar per envelope (suffixed by its id) and the example
     * « Prochain projet » card (MAG-420).
     */
    const val FINANCE_TILE_ACCOUNTS = "finance_tile_accounts"
    const val FINANCE_TILE_BUDGET = "finance_tile_budget"
    const val FINANCE_TILE_PLANNING = "finance_tile_planning"

    /** What is to do, as pills of the Comptes and Budget tiles, and the « Revue ce soir » shortcut (MAG-421). */
    const val FINANCE_PILL_TO_SORT = "finance_pill_to_sort"
    const val FINANCE_PILL_REJECTIONS = "finance_pill_rejections"
    const val FINANCE_PILL_OWED_EXAMPLE = "finance_pill_owed_example"
    const val FINANCE_PILL_ADVANCE = "finance_pill_advance"
    const val FINANCE_REVIEW_SHORTCUT = "finance_review_shortcut"
    const val FINANCE_TILE_EXPECTED_SALARY = "finance_tile_expected_salary"
    const val FINANCE_TILE_BALANCE_BEFORE_SALARY = "finance_tile_balance_before_salary"
    const val FINANCE_TILE_ENVELOPE_PREFIX = "finance_tile_envelope_"
    const val FINANCE_NEXT_PROJECT = "finance_next_project"
    const val FINANCE_TAB_OVERVIEW = "finance_tab_overview"
    const val MONTH_BALANCE_EMPTY = "month_balance_empty"
    const val MONTH_BALANCE_ENVELOPES = "month_balance_envelopes"
    const val MONTH_BALANCE_ENVELOPES_EMPTY = "month_balance_envelopes_empty"
    const val MONTH_BALANCE_SATISFACTION = "month_balance_satisfaction"
    const val MONTH_BALANCE_SATISFACTION_EMPTY = "month_balance_satisfaction_empty"
    const val MONTH_BALANCE_SAVINGS = "month_balance_savings"
    const val MONTH_BALANCE_CUSHION = "month_balance_cushion"
    const val MONTH_BALANCE_ENVELOPE_PREFIX = "month_balance_envelope_"

    /**
     * Finance dashboard — the three tabs, « Vue d'ensemble », « État courant » (the balance of a
     * month, MAG-457) and « À venir », and the blocks of the last: the week and the month after, the envelopes of the month after (one bar per category,
     * suffixed by its id), what is spent outside them, what is left toward the projects, and the
     * error with its retry (MAG-463).
     */
    const val FINANCE_TAB_CURRENT = "finance_tab_current"
    const val FINANCE_TAB_UPCOMING = "finance_tab_upcoming"
    const val UPCOMING_WEEK = "upcoming_week"
    const val UPCOMING_MONTH = "upcoming_month"
    const val UPCOMING_DAYS_PREFIX = "upcoming_days_"
    const val UPCOMING_ENVELOPES = "upcoming_envelopes"
    const val UPCOMING_ENVELOPE_PREFIX = "upcoming_envelope_"
    const val UPCOMING_UNBUDGETED = "upcoming_unbudgeted"
    const val UPCOMING_REMAINING = "upcoming_remaining"
    const val UPCOMING_ERROR = "upcoming_error"
    const val UPCOMING_RETRY = "upcoming_retry"

    /**
     * Banques — the fetch. Tagged rather than tapped by its label because the
     * label changes while the fetch runs (« Récupération… »), and a journey that
     * taps the text would hit a button that is no longer there.
     */
    const val BANK_SYNC = "bank_sync"

    /**
     * Suggestions — the yes of a card, which is refused while no heading is
     * chosen. Tagged because that refusal is what the journey asserts, and a
     * `Button`'s label is a node of its own in the hierarchy Maestro reads: the
     * `enabled` of a `Text` is always true, so `text: "Créer la règle",
     * enabled: false` matches nothing even on a button that is greyed out. The
     * tag lands on the button itself, which does carry the state.
     */
    const val SUGGESTION_ACCEPT = "suggestion_accept"

    /**
     * Opérations récurrentes (MAG-309) — the two badges of a series, the yes of a proposal and
     * of a suggestion, the « + », and the fields of the form. Tagged because the journey asserts
     * that a badge is there, not what it says.
     */
    const val RECURRING_NEW = "recurring_new"
    const val RECURRING_MISSED_BADGE = "recurring_missed_badge"
    const val RECURRING_PRICE_BADGE = "recurring_price_badge"
    const val RECURRING_PROPOSAL_ACCEPT = "recurring_proposal_accept"
    const val RECURRING_SUGGESTION_ACCEPT = "recurring_suggestion_accept"
    const val RECURRING_LABEL = "recurring_label"
    const val RECURRING_COUNTERPARTY = "recurring_counterparty"
    const val RECURRING_LABEL_PATTERN = "recurring_label_pattern"
    const val RECURRING_AMOUNT = "recurring_amount"
    const val RECURRING_ACCOUNT = "recurring_account"
    const val RECURRING_CATEGORY = "recurring_category"
    const val RECURRING_PICK_SEARCH = "recurring_pick_search"
    const val RECURRING_SAVE = "recurring_save"
    const val RECURRING_DELETE = "recurring_delete"
    const val RECURRING_ATTACH_CONFIRM = "recurring_attach_confirm"

    /** The transaction detail's « En faire une opération récurrente ». */
    const val RECURRING_FROM_TRANSACTION = "recurring_from_transaction"
    const val RECURRING_ATTACHED = "recurring_attached"

    /** The lines a series recognised, on its editor (MAG-438): a row, its « Détacher », the empty message. */
    const val RECURRING_LINE = "recurring_line"
    const val RECURRING_LINE_DETACH = "recurring_line_detach"
    const val RECURRING_LINES_EMPTY = "recurring_lines_empty"
    /** The lines the form would recognise, before saving: the total and a row each. */
    const val RECURRING_TOLERANCE = "recurring_tolerance"
    const val RECURRING_PREVIEW_COUNT = "recurring_preview_count"
    const val RECURRING_PREVIEW_MATCH = "recurring_preview_match"

    /** Calendar — the « + » button and its « Événement » entry. */
    const val CALENDAR_CREATE = "calendar_create"
    const val CALENDAR_CREATE_EVENT = "calendar_create_event"

    /** The new event form (MAG-322) — its title, its date and time fields, its button. */
    const val EVENT_TITLE = "event_title"
    const val EVENT_START_DATE = "event_start_date"
    const val EVENT_START_TIME = "event_start_time"
    const val EVENT_END_DATE = "event_end_date"
    const val EVENT_SAVE = "event_save"

    /** The « OK » of the date and time pickers — a window of their own. */
    const val PICKER_CONFIRM = "picker_confirm"

    /** Calendar toolbar — the « next » arrow and the Mois / Semaine switches. */
    const val CALENDAR_NEXT = "calendar_next"
    const val CALENDAR_VIEW_WEEK = "calendar_view_week"
    const val CALENDAR_VIEW_MONTH = "calendar_view_month"

    /** Calendar — the options menu and its Google import entry. */
    const val CALENDAR_OPTIONS = "calendar_options"
    const val CALENDAR_MEALS_FILTER = "calendar_meals_filter"
    const val CALENDAR_PROACTIONS_FILTER = "calendar_proactions_filter"

    /** The detail of one of Maggie's proactions, opened from the agenda (MAG-328). */
    const val PROACTION_DETAIL_TITLE = "proaction_detail_title"
    const val PROACTION_DETAIL_STATUS = "proaction_detail_status"
    const val PROACTION_DETAIL_EDIT = "proaction_detail_edit"
    const val PROACTION_DETAIL_DELETE = "proaction_detail_delete"
    const val PROACTION_DETAIL_DATE = "proaction_detail_date"
    const val PROACTION_DETAIL_TIME = "proaction_detail_time"
    const val PROACTION_DETAIL_SAVE = "proaction_detail_save"
    const val CALENDAR_MODULE_TITLE = "calendar_module_title"
    const val CALENDAR_IMPORT_GOOGLE = "calendar_import_google"

    /** Calendar › options menu — the « Tâches » entry that opens the tasks screen (MAG-408). */
    const val CALENDAR_OPEN_TASKS = "calendar_open_tasks"

    /** Google import dialog — the « Importer » button of one calendar, suffixed by its Google id. */
    const val GOOGLE_IMPORT_PREFIX = "google_import_"

    /** Week view — a day's header cell, suffixed by the ISO date it stands for. */
    const val CALENDAR_DAY_PREFIX = "calendar_day_"

    /**
     * Week view — the bar of an all-day or multi-day event, suffixed by the first
     * and last day it covers **in the displayed week** (`<first>_<last>`, ISO
     * dates). A bar that continues past Sunday ends on Sunday, and the next week
     * starts a new bar on Monday: the dates say which days are drawn, and the
     * visible text says which event.
     */
    const val CALENDAR_SPAN_PREFIX = "calendar_span_"

    /** Day view — a timed event block, suffixed by the ISO date of the day shown. */
    const val CALENDAR_EVENT_PREFIX = "calendar_event_"

    /**
     * Day view — the view itself and one all-day event of its top row, suffixed by the
     * ISO date of the day shown (MAG-382). The view's tag says which day is drawn, so a
     * flow can wait on it before asking whether an event is absent from that day.
     */
    const val CALENDAR_DAY_VIEW_PREFIX = "calendar_day_view_"
    const val CALENDAR_ALL_DAY_PREFIX = "calendar_all_day_"

    /**
     * Month view — a day's cell, one slot of its column (a bar or the blank that keeps
     * the bars of its neighbours aligned) and its « +N » counter, suffixed by the ISO
     * date (and `_<slot>` for a slot). Read by screen tests only: the position of the
     * same slot in two columns is what makes a multi-day bar look continuous (MAG-335).
     */
    const val CALENDAR_MONTH_DAY_PREFIX = "calendar_month_day_"

    /** Month view — the number at the top of a day's cell: tapping it opens that day, never an event of the cell. */
    const val CALENDAR_MONTH_DATE_PREFIX = "calendar_month_date_"
    const val CALENDAR_MONTH_SLOT_PREFIX = "calendar_month_slot_"
    const val CALENDAR_MONTH_MORE_PREFIX = "calendar_month_more_"

    /** Cuisine › Repas — one cell of the week: `meal_slot_<lunch|dinner>_<0 = Monday … 6 = Sunday>`. */
    const val MEAL_SLOT_PREFIX = "meal_slot_"

    /** Dashboard — the box that ticks a task, suffixed by the task's id (MAG-405). */
    const val DASHBOARD_TASK_CHECK_PREFIX = "dashboard_task_check_"

    fun dashboardTaskCheck(id: String) = DASHBOARD_TASK_CHECK_PREFIX + id

    /**
     * Tasks screen (MAG-408) — the root, one tab per view suffixed by the view's key (`today`,
     * `upcoming`, `someday`, `done`), the quick-add field and its button, the empty line, and the
     * box that ticks a task, suffixed by the task's id.
     */
    const val TASKS = "tasks"
    const val TASKS_TAB_PREFIX = "tasks_tab_"
    const val TASKS_ADD_INPUT = "tasks_add_input"
    const val TASKS_ADD_SUBMIT = "tasks_add_submit"
    const val TASKS_EMPTY = "tasks_empty"
    const val TASKS_CHECK_PREFIX = "tasks_check_"

    fun tasksTab(key: String) = TASKS_TAB_PREFIX + key

    fun tasksCheck(id: String) = TASKS_CHECK_PREFIX + id

    /**
     * « Aujourd'hui » is the day (MAG-497): the « Maintenant » card, its box and « Juste 10 minutes »;
     * the ring; each slot suffixed by its position (1 to 5), its box and its menu by the task's id,
     * « Remettre dans la réserve » in that menu; the reserve's header, each of its tasks and the
     * button that puts one in the day by the task's id, and when the five are taken the menu's
     * « À la place de … » entries by the slot's position; the day's own add field and its button.
     */
    const val TASKS_NOW = "tasks_now"
    const val TASKS_NOW_CHECK = "tasks_now_check"
    const val TASKS_NOW_FOCUS = "tasks_now_focus"
    const val TASKS_RING = "tasks_ring"
    const val TASKS_SLOT_PREFIX = "tasks_slot_"
    const val TASKS_SLOT_CHECK_PREFIX = "tasks_slot_check_"
    const val TASKS_SLOT_MENU_PREFIX = "tasks_slot_menu_"
    const val TASKS_SLOT_REMOVE = "tasks_slot_remove"
    const val TASKS_RESERVE_TOGGLE = "tasks_reserve_toggle"
    const val TASKS_RESERVE_ITEM_PREFIX = "tasks_reserve_item_"
    const val TASKS_RESERVE_ADD_PREFIX = "tasks_reserve_add_"
    const val TASKS_REPLACE_PREFIX = "tasks_replace_"
    const val TASKS_DAY_ADD_INPUT = "tasks_day_add_input"
    const val TASKS_DAY_ADD_SUBMIT = "tasks_day_add_submit"

    /**
     * « Ta journée » (MAG-498): the morning's proposal, its time bar, each row and its swap button by
     * position (1 to 5), the reserve line, « C'est parti » and « Plus tard »; « Changer » on the day.
     */
    const val TASKS_MORNING = "tasks_morning"
    const val TASKS_MORNING_TIME = "tasks_morning_time"
    const val TASKS_MORNING_SLOT_PREFIX = "tasks_morning_slot_"
    const val TASKS_MORNING_SWAP_PREFIX = "tasks_morning_swap_"
    const val TASKS_MORNING_RESERVE = "tasks_morning_reserve"
    const val TASKS_MORNING_GO = "tasks_morning_go"
    const val TASKS_MORNING_LATER = "tasks_morning_later"
    const val TASKS_CHANGE = "tasks_change"

    /**
     * « Le soir » (MAG-500): the review, its list of what is done, each task left by id with its three
     * ways out and « Annuler », each « Qu'est-ce qui t'a aidé ? » choice by value, « Bonne nuit » and
     * « Plus tard »; « Le soir » on the day.
     */
    const val TASKS_EVENING = "tasks_evening"
    const val TASKS_EVENING_DONE = "tasks_evening_done"
    const val TASKS_EVENING_LEFT_PREFIX = "tasks_evening_left_"
    const val TASKS_EVENING_OUTCOME_PREFIX = "tasks_evening_outcome_"
    const val TASKS_EVENING_UNDO_PREFIX = "tasks_evening_undo_"
    const val TASKS_EVENING_HELPED_PREFIX = "tasks_evening_helped_"
    const val TASKS_EVENING_GOOD_NIGHT = "tasks_evening_good_night"
    const val TASKS_EVENING_LATER = "tasks_evening_later"
    const val TASKS_EVENING_OPEN = "tasks_evening_open"

    fun tasksEveningLeft(taskId: String) = TASKS_EVENING_LEFT_PREFIX + taskId

    /** [outcome] is `tomorrow`, `later` or `aside`. */
    fun tasksEveningOutcome(taskId: String, outcome: String) = "$TASKS_EVENING_OUTCOME_PREFIX${outcome}_$taskId"

    fun tasksEveningUndo(taskId: String) = TASKS_EVENING_UNDO_PREFIX + taskId

    fun tasksEveningHelped(value: String) = TASKS_EVENING_HELPED_PREFIX + value

    fun tasksMorningSlot(position: Int) = TASKS_MORNING_SLOT_PREFIX + position

    fun tasksMorningSwap(position: Int) = TASKS_MORNING_SWAP_PREFIX + position

    fun tasksSlot(position: Int) = TASKS_SLOT_PREFIX + position

    fun tasksSlotCheck(id: String) = TASKS_SLOT_CHECK_PREFIX + id

    fun tasksSlotMenu(id: String) = TASKS_SLOT_MENU_PREFIX + id

    fun tasksReserveItem(id: String) = TASKS_RESERVE_ITEM_PREFIX + id

    fun tasksReserveAdd(id: String) = TASKS_RESERVE_ADD_PREFIX + id

    fun tasksReplace(position: Int) = TASKS_REPLACE_PREFIX + position

    /**
     * The Focus (MAG-499): the screen, the timer's ring and its time, the button that starts, pauses,
     * resumes or restarts it, the first step, each petit pas' box suffixed by its id, « Je bloque »
     * with its add field, « C'est fait » and « Plus tard »; from the day, the slot menu's « Focus ».
     */
    const val FOCUS = "focus"
    const val FOCUS_RING = "focus_ring"
    const val FOCUS_TIME = "focus_time"
    const val FOCUS_START = "focus_start"
    const val FOCUS_PAUSE = "focus_pause"
    const val FOCUS_RESUME = "focus_resume"
    const val FOCUS_RESTART = "focus_restart"
    const val FOCUS_FIRST_STEP = "focus_first_step"
    const val FOCUS_STEP_PREFIX = "focus_step_"
    const val FOCUS_BLOCKED = "focus_blocked"
    const val FOCUS_ADD_INPUT = "focus_add_input"
    const val FOCUS_ADD_SUBMIT = "focus_add_submit"
    const val FOCUS_DONE = "focus_done"
    const val FOCUS_LATER = "focus_later"
    const val TASKS_SLOT_FOCUS = "tasks_slot_focus"

    fun focusStep(id: String) = FOCUS_STEP_PREFIX + id

    /** A task's urgency, its icon and its word (MAG-406): on the dashboard's rows and in the detail. */
    const val TASK_URGENCY = "task_urgency"

    /** A task's create and edit screens (MAG-497): « Durée estimée » and « Premier pas ». */
    const val TASK_ESTIMATE = "task_estimate"
    const val TASK_FIRST_STEP = "task_first_step"

    /** Chat — a held action's card and its buttons, suffixed by the approval's id (MAG-7). */
    const val APPROVAL_CARD_PREFIX = "approval_card_"
    const val APPROVAL_ALLOW_PREFIX = "approval_allow_"
    const val APPROVAL_DENY_PREFIX = "approval_deny_"
    const val APPROVAL_DISMISS_PREFIX = "approval_dismiss_"
    const val APPROVAL_DETAILS_PREFIX = "approval_details_"

    fun approvalCard(id: String) = APPROVAL_CARD_PREFIX + id

    fun approvalAllow(id: String) = APPROVAL_ALLOW_PREFIX + id

    fun approvalDeny(id: String) = APPROVAL_DENY_PREFIX + id

    fun approvalDismiss(id: String) = APPROVAL_DISMISS_PREFIX + id

    fun approvalDetails(id: String) = APPROVAL_DETAILS_PREFIX + id

    fun drawerItem(route: String) = DRAWER_ITEM_PREFIX + route

    fun railItem(route: String) = RAIL_ITEM_PREFIX + route

    fun financeMenu(key: String) = FINANCE_MENU_PREFIX + key

    fun financeBlock(route: String) = FINANCE_BLOCK_PREFIX + route

    fun financeScoreRing(period: String) = FINANCE_SCORE_RING_PREFIX + period

    fun financeRhythmPeriod(period: String) = FINANCE_RHYTHM_PERIOD_PREFIX + period

    fun financeRhythmDebit(date: String) = FINANCE_RHYTHM_DEBIT_PREFIX + date

    fun financeTileEnvelope(envelopeId: String) = FINANCE_TILE_ENVELOPE_PREFIX + envelopeId

    fun upcomingDays(period: String) = UPCOMING_DAYS_PREFIX + period

    fun upcomingEnvelope(categoryId: String) = UPCOMING_ENVELOPE_PREFIX + categoryId

    fun monthBalanceEnvelope(envelopeId: String) = MONTH_BALANCE_ENVELOPE_PREFIX + envelopeId

    fun savingsAdvanceDone(advanceId: String) = SAVINGS_ADVANCE_DONE_PREFIX + advanceId

    fun googleImport(calendarId: String) = GOOGLE_IMPORT_PREFIX + calendarId

    fun sheetSection(key: String) = SHEET_SECTION_PREFIX + key

    fun calendarDay(date: LocalDate) = CALENDAR_DAY_PREFIX + date

    fun calendarSpan(first: LocalDate, last: LocalDate) = "$CALENDAR_SPAN_PREFIX${first}_$last"

    fun calendarEvent(date: LocalDate) = CALENDAR_EVENT_PREFIX + date

    fun calendarMonthDay(date: LocalDate) = CALENDAR_MONTH_DAY_PREFIX + date

    fun calendarMonthDate(date: LocalDate) = CALENDAR_MONTH_DATE_PREFIX + date

    fun calendarDayView(date: LocalDate) = CALENDAR_DAY_VIEW_PREFIX + date

    fun calendarAllDay(date: LocalDate) = CALENDAR_ALL_DAY_PREFIX + date

    fun calendarMonthSlot(date: LocalDate, slot: Int) = "$CALENDAR_MONTH_SLOT_PREFIX${date}_$slot"

    fun calendarMonthMore(date: LocalDate) = CALENDAR_MONTH_MORE_PREFIX + date

    fun mealSlot(slot: String, dayIndex: Int) = "$MEAL_SLOT_PREFIX${slot}_$dayIndex"

    /**
     * The drawer entry the grocery journeys open. Covered by [DRAWER_ITEM_PREFIX]
     * already; kept as the constant the flows and [UiTagsTest] name.
     */
    const val DRAWER_GROCERY = "drawer_grocery"

    /** The grocery screen — the root, so a journey can wait for the screen itself. */
    const val GROCERY = "grocery"

    /** The search field of the product picker, a window of its own. */
    const val PRODUCT_SEARCH = "product_search"

    /** The grocery screen's tabs: the list, and the recurring items (MAG-369). */
    const val GROCERY_TAB_LIST = "grocery_tab_list"
    const val GROCERY_TAB_RECURRING = "grocery_tab_recurring"

    /** The recurring tab's « Ajouter un produit », which opens the product search. */
    const val RECURRING_ADD = "recurring_add"

    /** The repeat icon and frequency under a list line whose product comes back by itself. */
    const val RECURRING_INDICATOR = "recurring_indicator"

    /** One choice of the « Récurrent » setting: `recurring_option_none`, `_weekly`, `_biweekly`, `_monthly`. */
    const val RECURRING_OPTION_PREFIX = "recurring_option_"

    fun recurringOption(value: String) = RECURRING_OPTION_PREFIX + value

    /** The grocery screen's history icon, the « Courses passées » screen and one errand's sheet (MAG-488). */
    const val PAST_ERRANDS_OPEN = "past_errands_open"
    const val PAST_ERRANDS = "past_errands"
    const val PAST_ERRAND_SHEET = "past_errand_sheet"

    /**
     * « Souvent pris » at the top of the grocery list (MAG-495): the banner, its fold toggle,
     * and per product its add and « Pas besoin » buttons, suffixed by the product id.
     */
    const val GROCERY_SUGGESTIONS = "grocery_suggestions"
    const val GROCERY_SUGGESTIONS_TOGGLE = "grocery_suggestions_toggle"
    const val GROCERY_SUGGESTION_ADD_PREFIX = "grocery_suggestion_add_"
    const val GROCERY_SUGGESTION_SNOOZE_PREFIX = "grocery_suggestion_snooze_"

    fun grocerySuggestionAdd(productId: String) = GROCERY_SUGGESTION_ADD_PREFIX + productId

    fun grocerySuggestionSnooze(productId: String) = GROCERY_SUGGESTION_SNOOZE_PREFIX + productId

    /** A transaction line's « Virement interne » badge, in the list and on the detail screen. */
    const val TRANSFER_BADGE = "transfer_badge"

    /** The detail screen's toggle: « C'est un virement interne » or « Ce n'est pas… ». */
    const val TRANSFER_TOGGLE = "transfer_toggle"

    /** The search field of the counterpart picker, a screen of its own. */
    const val TRANSFER_SEARCH = "transfer_search"

    /**
     * The transaction sheet's six sections (MAG-464), suffixed by the section key — nature,
     * classement, recurrence, partage, jugement, note — built by [sheetSection].
     */
    const val SHEET_SECTION_PREFIX = "sheet_section_"

    /** Built by [qualificationKind]: qualification_kind_repayment, … */
    const val QUALIFICATION_KIND_PREFIX = "qualification_kind_"

    /** The pills of a line in the lists; « Virement interne » and « Rejeté » keep [TRANSFER_BADGE] and [REJECTION_BADGE]. */
    const val PILL_SERIES = "pill_series"
    const val PILL_SHARED = "pill_shared"
    const val PILL_TO_SORT = "pill_to_sort"

    /** The sheet's « Détacher de la série », its note field and « Enregistrer la note ». */
    const val RECURRING_DETACH = "recurring_detach"
    const val TRANSACTION_NOTE = "transaction_note"
    const val TRANSACTION_NOTE_SAVE = "transaction_note_save"

    /** « Créer un filtre » (MAG-464): the sheet's action, then the screen's pattern, category, lines caught and save. */
    const val RULE_DRAFT_OPEN = "rule_draft_open"
    const val RULE_DRAFT_PATTERN = "rule_draft_pattern"
    const val RULE_DRAFT_AMOUNT = "rule_draft_amount"
    const val RULE_DRAFT_TOLERANCE = "rule_draft_tolerance"
    const val RULE_DRAFT_CATEGORY = "rule_draft_category"
    const val RULE_DRAFT_MATCH = "rule_draft_match"
    const val RULE_DRAFT_SAVE = "rule_draft_save"

    /**
     * « À qualifier » (MAG-423): the card of the line open, the counter, the three answers, the switch, the full-screen
     * category search, the transfer choice (« sans contrepartie »), a row of the tablet's list, the empty pile.
     */
    const val TO_SORT_CARD = "to_sort_card"
    const val TO_SORT_REMAINING = "to_sort_remaining"
    const val TO_SORT_VALIDATE = "to_sort_validate"
    const val TO_SORT_OTHER = "to_sort_other"
    const val TO_SORT_SEARCH = "to_sort_search"
    const val TO_SORT_ALWAYS = "to_sort_always"
    const val TO_SORT_TRANSFER = "to_sort_transfer"
    const val TO_SORT_TRANSFER_NONE = "to_sort_transfer_none"
    const val TO_SORT_ROW = "to_sort_row"
    const val TO_SORT_EMPTY = "to_sort_empty"

    /** « À qualifier » (MAG-458): the Qualification block, « Créer un filtre », « Voir la fiche », the two satisfactions. */
    const val TO_SORT_QUALIFICATION = "to_sort_qualification"
    const val TO_SORT_RULE = "to_sort_rule"
    const val TO_SORT_OPEN_SHEET = "to_sort_open_sheet"
    const val TO_SORT_KEEP = "to_sort_keep"
    const val TO_SORT_AVOIDABLE = "to_sort_avoidable"

    /** The « À qualifier · n · montant » pill of the finance dashboard and the budget screen. */
    const val TO_QUALIFY_PILL = "to_qualify_pill"

    /** The card's « Associer à un contact ? » and the contact a line is attached to (MAG-450). */
    const val TO_SORT_CONTACT = "to_sort_contact"
    const val TO_SORT_CONTACT_NAME = "to_sort_contact_name"

    /**
     * Contacts (MAG-450), reached from « Comptes »: the accounts' entry, the list's search, « Nouveau contact »
     * and its name and « Créer », a row, the empty list; the page's name, notes, « Enregistrer », « Supprimer »,
     * a label of « Ses comptes » and its « Retirer », the « + » of a suggestion.
     */
    const val ACCOUNTS_CONTACTS = "accounts_contacts"
    const val CONTACT_SEARCH = "contact_search"
    const val CONTACT_ADD = "contact_add"
    const val CONTACT_NEW_NAME = "contact_new_name"
    const val CONTACT_NEW_CONFIRM = "contact_new_confirm"
    const val CONTACT_ROW = "contact_row"
    const val CONTACT_EMPTY = "contact_empty"
    const val CONTACT_NAME = "contact_name"
    const val CONTACT_NOTES = "contact_notes"
    const val CONTACT_SAVE = "contact_save"
    const val CONTACT_DELETE = "contact_delete"
    const val CONTACT_COUNTERPARTY = "contact_counterparty"
    const val CONTACT_COUNTERPARTY_REMOVE = "contact_counterparty_remove"
    const val CONTACT_SUGGESTION_ADD = "contact_suggestion_add"

    /** A contact's « En cours » (MAG-451): one open créance, and its « te doit … ». */
    const val CONTACT_DEBT = "contact_debt"
    const val CONTACT_DEBT_REMAINING = "contact_debt_remaining"

    /**
     * The sheet's « Partage » (MAG-451): the switch « Dépense partagée », « Ta part » with its − / + and its field,
     * « On te doit », a person of « Qui te doit » with their amount and « Retirer », « Ajouter une personne »,
     * « Objet », « Notes », « Enregistrer », what blocks it or what the server refused, and « Voir tout ce qu'on te doit ».
     */
    const val SHARE_SWITCH = "share_switch"
    const val SHARE_MINE = "share_mine"
    const val SHARE_MINE_LESS = "share_mine_less"
    const val SHARE_MINE_MORE = "share_mine_more"
    const val SHARE_OWED = "share_owed"
    const val SHARE_PERSON = "share_person"
    const val SHARE_PERSON_AMOUNT = "share_person_amount"
    const val SHARE_PERSON_REMOVE = "share_person_remove"
    const val SHARE_ADD_PERSON = "share_add_person"
    const val SHARE_SUBJECT = "share_subject"
    const val SHARE_NOTES = "share_notes"
    const val SHARE_SAVE = "share_save"
    const val SHARE_PROBLEM = "share_problem"
    const val SHARE_ALL_OWED = "share_all_owed"

    /**
     * The sheet's « Ce virement est » on a received transfer (MAG-452): « Une recette » / « À rembourser », the person
     * (opens the full-screen contact picker), the amount with its − / +, « Objet », « Notes », the sentence of what
     * « Enregistrer » creates, what blocks it or what the server refused, and « Enregistrer ».
     */
    const val TRANSACTION_DEBT_RECEIPT = "transaction_debt_receipt"
    const val TRANSACTION_DEBT_TOGGLE = "transaction_debt_toggle"
    const val TRANSACTION_DEBT_PERSON = "transaction_debt_person"
    const val TRANSACTION_DEBT_AMOUNT = "transaction_debt_amount"
    const val TRANSACTION_DEBT_AMOUNT_LESS = "transaction_debt_amount_less"
    const val TRANSACTION_DEBT_AMOUNT_MORE = "transaction_debt_amount_more"
    const val TRANSACTION_DEBT_SUBJECT = "transaction_debt_subject"
    const val TRANSACTION_DEBT_NOTES = "transaction_debt_notes"
    const val TRANSACTION_DEBT_SUMMARY = "transaction_debt_summary"
    const val TRANSACTION_DEBT_PROBLEM = "transaction_debt_problem"
    const val TRANSACTION_DEBT_SAVE = "transaction_debt_save"

    /**
     * « Ce virement est » of a transfer to or from a contact (MAG-453), on the sheet and the « À trier » card: one
     * chip per kind; « Il couvre »'s créances (or dettes) — one row each, its amount — and « Non affecté »; the
     * person and amount of « À rembourser » / « Nouvelle créance »; what blocks « Enregistrer », and « Enregistrer ».
     */
    const val PEER_KIND_INCOME = "peer_kind_income"
    const val PEER_KIND_REPAYMENT = "peer_kind_repayment"
    const val PEER_KIND_NEW_DEBT = "peer_kind_new_debt"
    const val PEER_KIND_NEW_CLAIM = "peer_kind_new_claim"
    const val PEER_KIND_OTHER = "peer_kind_other"
    const val PEER_CANDIDATE = "peer_candidate"
    const val PEER_CANDIDATE_AMOUNT = "peer_candidate_amount"
    const val PEER_UNALLOCATED = "peer_unallocated"
    const val PEER_PERSON = "peer_person"
    const val PEER_AMOUNT = "peer_amount"
    const val PEER_SUBJECT = "peer_subject"
    const val PEER_NOTES = "peer_notes"
    const val PEER_SUMMARY = "peer_summary"
    const val PEER_PROBLEM = "peer_problem"
    const val PEER_SAVE = "peer_save"

    /** The chip of one « Ce virement est » choice. */
    fun peerKind(kind: String): String = when (kind) {
        "income" -> PEER_KIND_INCOME
        "repayment" -> PEER_KIND_REPAYMENT
        "new_debt" -> PEER_KIND_NEW_DEBT
        "new_claim" -> PEER_KIND_NEW_CLAIM
        else -> PEER_KIND_OTHER
    }

    /** The full-screen contact picker — a window of its own: its field, one result, « + Créer « … » ». */
    const val CONTACT_PICKER_SEARCH = "contact_picker_search"
    const val CONTACT_PICKER_RESULT = "contact_picker_result"
    const val CONTACT_PICKER_CREATE = "contact_picker_create"

    /** A rejected line's « Rejeté » / « Rejet de … » badge, in the list and on the detail screen (MAG-350). */
    const val REJECTION_BADGE = "rejection_badge"

    /** The account screen's tabs: the transactions, and « Incidents (n) » for the rejected payments (MAG-375). */
    const val TRANSACTIONS_TAB = "transactions_tab"
    const val INCIDENTS_TAB = "incidents_tab"

    /** One rejection of the Incidents tab. */
    const val INCIDENT_ROW = "incident_row"

    /** « Régularisé » on a rejection of the pile, and the folded « Régularisés (n) » section (MAG-424). */
    const val INCIDENT_SETTLE = "incident_settle"
    const val SETTLED_INCIDENTS_HEADER = "settled_incidents_header"
    const val SETTLED_INCIDENT_ROW = "settled_incident_row"
    const val INCIDENT_UNSETTLE = "incident_unsettle"

    /** The detail screen's « Ce n'est pas un rejet »: there is no marking from the phone, only this release. */
    const val REJECTION_RELEASE = "rejection_release"

    /**
     * Maggie's interruption over the app (MAG-314) — a window of its own: the bubble, the
     * action she proposes, a second answer beside it (« Refuser »), and « Plus tard ».
     */
    const val INTERRUPTION = "interruption"
    const val INTERRUPTION_ACTION = "interruption_action"
    const val INTERRUPTION_SECOND = "interruption_second"
    const val INTERRUPTION_LATER = "interruption_later"

    /** Settings > Notifications — the switch of the chime and the vibration that announce her. */
    const val SETTINGS_INTERRUPTION_SOUND = "settings_interruption_sound"

    /**
     * Settings > Tâches (MAG-407): the row of the list, « Heure du matin », the chips of the
     * days before the due day ([settingsTasksChip]), « En retard » ([settingsTasksOverdue]),
     * « Valeurs par défaut » and the « Enregistré » that follows every save — on both screens.
     */
    const val SETTINGS_TASKS = "settings_tasks"
    const val SETTINGS_TASKS_MORNING = "settings_tasks_morning"
    const val SETTINGS_TASKS_EVENING = "settings_tasks_evening"
    const val SETTINGS_TASKS_CHIP_PREFIX = "settings_tasks_chip_"
    const val SETTINGS_TASKS_OVERDUE_PREFIX = "settings_tasks_overdue_"
    const val SETTINGS_TASKS_DEFAULTS = "settings_tasks_defaults"
    const val SETTINGS_TASKS_SAVED = "settings_tasks_saved"

    /** A chip of [urgency]: `d7`, `d3`, `d2`, `d1` for J-n ([days]), `due` for « Jour J » ([days] null). */
    fun settingsTasksChip(urgency: String, days: Int?): String =
        SETTINGS_TASKS_CHIP_PREFIX + urgency + "_" + (days?.let { "d$it" } ?: "due")

    fun settingsTasksOverdue(urgency: String): String = SETTINGS_TASKS_OVERDUE_PREFIX + urgency

    /**
     * Settings > « Ce qui passe en premier » (MAG-407): the row of the list, a slider per group
     * (`timePressure`, `criticality`, `priority`), the preview of five tasks and « Valeurs par défaut ».
     */
    const val SETTINGS_TASK_ORDER = "settings_task_order"
    const val SETTINGS_TASK_ORDER_PREVIEW = "settings_task_order_preview"
    const val SETTINGS_TASK_ORDER_DEFAULTS = "settings_task_order_defaults"

    fun settingsTaskOrder(group: String): String = SETTINGS_TASK_ORDER + "_" + group

    /**
     * Settings > Repas (MAG-483): the row of the list, the stepper of the default number of guests
     * (`settings_meals_guests_minus`, `_value`, `_plus`), « Enregistrer » and the line that says it went through.
     */
    const val SETTINGS_MEALS = "settings_meals"
    const val SETTINGS_MEALS_GUESTS = "settings_meals_guests"
    const val SETTINGS_MEALS_SAVE = "settings_meals_save"
    const val SETTINGS_MEALS_SAVED = "settings_meals_saved"

    /**
     * The meal being planned (MAG-378): the « Menu » field that opens the recipe search, the
     * recipes chosen under it and their remove buttons, and « Créer ».
     */
    const val MEAL_MENU_FIELD = "meal_menu_field"
    const val MEAL_MENU_RECIPE = "meal_menu_recipe"
    const val MEAL_MENU_RECIPE_REMOVE = "meal_menu_recipe_remove"
    const val MEAL_CREATE_SUBMIT = "meal_create_submit"

    /** « Convives » of the meal being planned: the stepper (`meal_guests_minus`, `_value`, `_plus`) and the « par défaut » mark. */
    const val MEAL_GUESTS = "meal_guests"
    const val MEAL_GUESTS_DEFAULT = "meal_guests_default"
    const val MEAL_GUESTS_PLUS = "meal_guests_plus"
    const val MEAL_GUESTS_VALUE = "meal_guests_value"

    /** The people a planned meal in the week view is cooked for, and « Quantités pour N convives » on the ingredient choice. */
    const val MEAL_ROW_GUESTS = "meal_row_guests"
    const val MEAL_CHOICE_GUESTS = "meal_choice_guests"

    /**
     * « Pour N personnes » on the recipe sheet (MAG-483): the stepper (`recipe_guests_minus`, `_value`,
     * `_plus`), « Parts de la recette », the line that says a simulation is shown, its error, and the
     * quantity of each ingredient.
     */
    const val RECIPE_GUESTS = "recipe_guests"
    const val RECIPE_GUESTS_RESET = "recipe_guests_reset"
    const val RECIPE_GUESTS_SIMULATION = "recipe_guests_simulation"
    const val RECIPE_GUESTS_ERROR = "recipe_guests_error"
    const val RECIPE_INGREDIENT_QUANTITY = "recipe_ingredient_quantity"

    /** The « de saison » / « hors saison » badge of a product, a grocery line or an ingredient (MAG-494), the product's season chips, and its button. */
    const val SEASON_BADGE = "season_badge"
    const val SEASON_MONTHS = "season_months"
    const val PRODUCT_SEASON_EDIT = "product_season_edit"

    /**
     * The equipment and the steps of a recipe (MAG-492). On the sheet: the utensil chips and one step row
     * (`recipe_step`, `recipe_step_duration`, `recipe_step_waits`). On the edit sheet: « Ajouter une étape »,
     * a step's text, its « Ustensile » button, « Enregistrer », and the full-screen utensil search with its
     * field, one result and the « Créer » row.
     */
    const val RECIPE_UTENSIL = "recipe_utensil"
    const val RECIPE_STEP = "recipe_step"
    const val RECIPE_STEP_DURATION = "recipe_step_duration"
    const val RECIPE_STEP_WAITS = "recipe_step_waits"
    const val RECIPE_STEP_ADD = "recipe_step_add"
    const val RECIPE_STEP_TEXT = "recipe_step_text"
    const val RECIPE_STEP_ERROR = "recipe_step_error"
    const val RECIPE_UTENSIL_ADD = "recipe_utensil_add"
    const val RECIPE_UTENSIL_SEARCH = "recipe_utensil_search"
    const val RECIPE_UTENSIL_RESULT = "recipe_utensil_result"
    const val RECIPE_UTENSIL_CREATE = "recipe_utensil_create"
    const val RECIPE_SAVE = "recipe_save"

    /** The recipe search a menu is chosen in — a window of its own: its field, one result, and « Terminer ». */
    const val MEAL_RECIPE_SEARCH_FIELD = "meal_recipe_search_field"
    const val MEAL_RECIPE_RESULT = "meal_recipe_result"
    const val MEAL_RECIPE_SEARCH_DONE = "meal_recipe_search_done"

    /** The week view's meal (a long press asks to delete it) and the « Supprimer » of the confirmation. */
    const val MEAL_ROW = "meal_row"
    const val MEAL_DELETE_CONFIRM = "meal_delete_confirm"

    /** « + » in a slot that already holds a meal: the only way to plan a second one there. */
    const val MEAL_ADD = "meal_add"

    /** The meal's sheet (MAG-378): its title, « Enregistrer », « Supprimer » and the ingredients. */
    const val MEAL_DETAIL_TITLE = "meal_detail_title"
    const val MEAL_DETAIL_SAVE = "meal_detail_save"
    const val MEAL_DETAIL_DELETE = "meal_detail_delete"
    const val MEAL_DETAIL_INGREDIENTS = "meal_detail_ingredients"
}
