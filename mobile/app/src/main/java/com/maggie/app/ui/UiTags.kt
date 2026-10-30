package com.maggie.app.ui

import java.time.DayOfWeek
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

    /** Accueil — the root, so a journey can wait for the screen itself (MAG-560). */
    const val HOME = "home_screen"

    /** Accueil — the « Aujourd'hui · Demain » selector, and its two segments. */
    const val HOME_DAY_SELECTOR = "home_day_selector"
    const val HOME_DAY_TODAY = "home_day_today"
    const val HOME_DAY_TOMORROW = "home_day_tomorrow"

    /** Accueil — the one calm sentence said when every card is empty (MAG-594). */
    const val HOME_EMPTY = "home_empty"

    /** Accueil — a card (suffixed by its module: `tasks`, `agenda`…) and the header that opens its module. */
    const val HOME_CARD_PREFIX = "home_card_"
    const val HOME_CARD_HEADER_PREFIX = "home_header_"

    fun homeCard(module: String) = HOME_CARD_PREFIX + module

    fun homeCardHeader(module: String) = HOME_CARD_HEADER_PREFIX + module

    /** Accueil — the « ▶ Lancer » of an activity, suffixed by its module (MAG-568). */
    const val HOME_LAUNCH_PREFIX = "home_launch_"

    fun homeLaunch(module: String) = HOME_LAUNCH_PREFIX + module

    /** Accueil › Courses card (MAG-563): the « Pour les recettes » line, a shop, a line, a shop's « + n », the empty and error states. */
    const val HOME_GROCERY_RECIPES = "home_grocery_recipes"
    const val HOME_GROCERY_STORE = "home_grocery_store"
    const val HOME_GROCERY_ITEM = "home_grocery_item"
    const val HOME_GROCERY_MORE = "home_grocery_more"
    const val HOME_GROCERY_ERROR = "home_grocery_error"
    const val HOME_GROCERY_RETRY = "home_grocery_retry"
    /** Accueil › Cuisine card (MAG-564): a slot's row, a meal, its missing line, the error and its retry. */
    const val HOME_CUISINE_SLOT = "home_cuisine_slot"
    const val HOME_CUISINE_MEAL = "home_cuisine_meal"
    const val HOME_CUISINE_MISSING = "home_cuisine_missing"
    const val HOME_CUISINE_EMPTY = "home_cuisine_empty"
    const val HOME_CUISINE_ERROR = "home_cuisine_error"
    const val HOME_CUISINE_RETRY = "home_cuisine_retry"
    /** Accueil · Agenda (MAG-562): a row of the day (suffixed by the event id), the « maintenant » line, « Voir la journée ». */
    const val HOME_AGENDA_ROW_PREFIX = "home_agenda_row_"
    const val HOME_AGENDA_NOW_LINE = "home_agenda_now_line"
    const val HOME_AGENDA_SEE_DAY = "home_agenda_see_day"

    fun homeAgendaRow(eventId: String) = HOME_AGENDA_ROW_PREFIX + eventId

    /** Accueil · Agenda — the fiche of an event touched on the card, and what it offers. */
    const val HOME_AGENDA_SHEET = "home_agenda_sheet"
    const val HOME_AGENDA_SHEET_TITLE = "home_agenda_sheet_title"
    const val HOME_AGENDA_OPEN_EVENT = "home_agenda_open_event"
    const val HOME_AGENDA_LAUNCH = "home_agenda_launch"

    /** Accueil › Finance card (MAG-565): a ring by period, the « n à qualifier » pill, the next project, the error state. */
    const val HOME_FINANCE_RING_PREFIX = "home_finance_ring_"
    const val HOME_FINANCE_TO_QUALIFY = "home_finance_to_qualify"
    const val HOME_FINANCE_NEXT_PROJECT = "home_finance_next_project"
    const val HOME_FINANCE_ERROR = "home_finance_error"
    const val HOME_FINANCE_RETRY = "home_finance_retry"

    fun homeFinanceRing(period: String) = HOME_FINANCE_RING_PREFIX + period

    /**
     * Accueil › Tâches (MAG-561) — the ring « x sur n », the « n en retard » line, the « + n autres » link,
     * the saga line, the bar of routines, the failure message and the retry of a failed read.
     */
    const val HOME_TASKS_RING = "home_tasks_ring"
    const val HOME_TASKS_OVERDUE = "home_tasks_overdue"
    const val HOME_TASKS_MORE = "home_tasks_more"
    const val HOME_TASKS_SAGA = "home_tasks_saga"
    const val HOME_TASKS_ROUTINES = "home_tasks_routines"
    const val HOME_TASKS_MESSAGE = "home_tasks_message"
    const val HOME_TASKS_ERROR = "home_tasks_error"
    const val HOME_TASKS_RETRY = "home_tasks_retry"

    /** Accueil › Tâches — the box of a task (`home_task_check_<id>`) and the chip of a routine (`home_routine_check_<id>`). */
    const val HOME_TASK_CHECK_PREFIX = "home_task_check_"
    const val HOME_ROUTINE_CHECK_PREFIX = "home_routine_check_"

    fun homeTaskCheck(id: String) = HOME_TASK_CHECK_PREFIX + id

    fun homeRoutineCheck(id: String) = HOME_ROUTINE_CHECK_PREFIX + id

    /**
     * The way into the conversation, on every main screen: the collapsed bar under the
     * content, or — on a window too short for it (MAG-35) — the button at the top of
     * the rail. Same tag either way, so a journey taps it in both layouts.
     */
    const val CHAT_OPEN = "chat_open"

    /** The count of Maggie's messages not yet read, on the way into the conversation (MAG-687). */
    const val CHAT_UNREAD_BADGE = "chat_unread_badge"

    /** Maggie's face inside the bar's field (MAG-624). */
    const val CHAT_AVATAR = "chat_avatar"

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

    /** « + Écran » in the assistant overlay, the attachment it turns into, and its cross (MAG-486). */
    const val ASSIST_SCREEN_ADD = "assist_screen_add"
    const val ASSIST_SCREEN_ATTACHMENT = "assist_screen_attachment"
    const val ASSIST_SCREEN_REMOVE = "assist_screen_remove"

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

    /** The voice bubble of the chat (MAG-686): play/pause, progress, duration, speed, transcript, error. */
    const val VOICE_PLAY = "voice_play"
    const val VOICE_SEEK = "voice_seek"
    const val VOICE_DURATION = "voice_duration"
    const val VOICE_SPEED = "voice_speed"
    const val VOICE_TRANSCRIPT_TOGGLE = "voice_transcript_toggle"
    const val VOICE_TRANSCRIPT = "voice_transcript"
    const val VOICE_ERROR = "voice_error"

    /** The voice bar's state line — « Maggie parle... » and friends. */
    const val VOICE_STATE = "voice_state"

    /** The voice bar's mic — held down to talk. */
    const val VOICE_MIC = "voice_mic"

    /** The voice bar's live text, as the phone's own recognition hears it (MAG-222). */
    const val VOICE_PARTIAL = "voice_partial"

    /** Top bar — the burger that opens the navigation drawer. */
    const val NAV_MENU = "nav_menu"

    /** Header — the loupe that opens the search, and the bell of Maggie's interruptions (MAG-712). */
    const val HEADER_SEARCH = "header_search"
    const val HEADER_BELL = "header_bell"
    const val HEADER_BELL_BADGE = "header_bell_badge"

    /** The bell's panel, the scrim behind it, its sections and its entries (MAG-712). */
    const val INTERRUPTIONS_PANEL = "interruptions_panel"
    const val INTERRUPTIONS_SCRIM = "interruptions_scrim"
    const val INTERRUPTIONS_PENDING = "interruptions_pending"
    const val INTERRUPTIONS_SETTLED = "interruptions_settled"
    const val INTERRUPTIONS_EMPTY = "interruptions_empty"
    const val INTERRUPTIONS_QUESTIONS = "interruptions_questions"
    const val INTERRUPTIONS_CONFIRMATION = "interruptions_confirmation"

    fun interruptionsTrip(id: String) = "interruptions_trip_$id"
    fun interruptionsQuestion(key: String) = "interruptions_question_$key"
    fun interruptionsSettled(id: String) = "interruptions_settled_$id"

    /** Top bar — the title of the generic bar; a module whose screen wears its own breadcrumb has none (MAG-670). */
    const val TOP_BAR_TITLE = "top_bar_title"

    /**
     * Settings > Voix — the row that makes Maggie the system assistant (MAG-30).
     * Addressed by its tag and not by its state, which is the point: the journey
     * reads the state off the row.
     */
    const val SETTINGS_ASSISTANT_ROLE = "settings_assistant_role"

    /** Navigation drawer — the header: Maggie's avatar and the day (MAG-620). */
    const val DRAWER_HEADER = "drawer_header"
    const val DRAWER_SHEET = "drawer_sheet"

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
    /** The « ⋮ » of the Budget breadcrumb (MAG-632): « Ajuster les enveloppes » and « Reconduire le mois précédent ». */
    const val BUDGET_MENU = "budget_menu"
    const val BUDGET_ROLL_OVER = "budget_roll_over"
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

    /** The Finance home and its « À venir » tab drawn as two lists side by side on a wide window: the chat panel is folded there, so it no longer tells (MAG-661). */
    const val FINANCE_TWO_COLUMNS = "finance_two_columns"

    /** The « Découvert prévu » banner, wherever a screen draws it (MAG-280). */
    const val TREASURY_BANNER = "treasury_banner"

    /** Trésorerie — the advice's « Préparer l'avance », and the field of the overdraft limit. */
    const val TREASURY_PREPARE_ADVANCE = "treasury_prepare_advance"
    const val TREASURY_OVERDRAFT_LIMIT = "treasury_overdraft_limit"
    /** Trésorerie — the two curves (balance, cumulated flows) and the line that spells out the touched day. */
    const val TREASURY_CHART = "treasury_chart"
    const val TREASURY_CHART_DETAIL = "treasury_chart_detail"
    const val TREASURY_FLOWS_CHART = "treasury_flows_chart"
    const val TREASURY_FLOWS_DETAIL = "treasury_flows_detail"
    /** Savings advances — the screen root, the total, the retry of a failed load, the salary day and the « Je l'ai fait » of an advance. */
    const val SAVINGS_ADVANCES = "savings_advances"
    const val SAVINGS_ADVANCES_TOTAL = "savings_advances_total"
    const val SAVINGS_ADVANCES_RETRY = "savings_advances_retry"
    const val SAVINGS_ADVANCES_SALARY_DAY = "savings_advances_salary_day"
    const val SAVINGS_ADVANCES_SAVE_DAY = "savings_advances_save_day"
    const val SAVINGS_ADVANCE_DONE_PREFIX = "savings_advance_done_"

    /**
     * The breadcrumb of a module (MAG-537): its name, the section button, the menu of the sections (one
     * item per section, suffixed by its key) and the row of sub-views (one pill per sub-view, suffixed by its key).
     */
    const val BREADCRUMB_MODULE = "breadcrumb_module"
    const val BREADCRUMB_SECTION = "breadcrumb_section"
    const val BREADCRUMB_MENU = "breadcrumb_menu"
    const val BREADCRUMB_SCRIM = "breadcrumb_scrim"
    const val BREADCRUMB_MENU_PREFIX = "breadcrumb_menu_"
    const val SUBVIEW_ROW = "subview_row"
    const val SUBVIEW_ROW_TRAILING = "subview_row_trailing"
    const val MODULE_FAB = "module_fab"
    const val SUBVIEW_PREFIX = "subview_"
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
    const val FINANCE_SCORE_UNCLASSIFIED_PREFIX = "finance_score_unclassified_"
    const val FINANCE_SCORE_CLASSIFY_PREFIX = "finance_score_classify_"
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

    /** The independence counter's date N, coverage curve and dated milestones (MAG-549). */
    const val INDEPENDENCE_DATE_N = "independence_date_n"
    const val INDEPENDENCE_CURVE = "independence_curve"
    const val INDEPENDENCE_MILESTONES = "independence_milestones"

    /** The simulated rents counted by the projection: the section, its two rows and its caption (MAG-558). */
    const val INDEPENDENCE_SOURCES = "independence_sources"
    const val INDEPENDENCE_SOURCES_PLACEMENTS = "independence_sources_placements"
    const val INDEPENDENCE_SOURCES_INVESTMENTS = "independence_sources_investments"
    const val INDEPENDENCE_SOURCES_CAPTION = "independence_sources_caption"

    /** What is to do, as pills of the Comptes and Budget tiles, and the « Revue ce soir » shortcut (MAG-421). */
    const val FINANCE_PILL_TO_SORT = "finance_pill_to_sort"
    const val FINANCE_PILL_REJECTIONS = "finance_pill_rejections"
    const val FINANCE_PILL_OWED = "finance_pill_owed"
    const val FINANCE_PILL_ADVANCE = "finance_pill_advance"
    const val FINANCE_REVIEW_SHORTCUT = "finance_review_shortcut"
    const val FINANCE_REVIEW_CARD = "finance_review_card"
    const val FINANCE_TILE_EXPECTED_SALARY = "finance_tile_expected_salary"
    const val FINANCE_TILE_BALANCE_BEFORE_SALARY = "finance_tile_balance_before_salary"
    const val FINANCE_TILE_ENVELOPE_PREFIX = "finance_tile_envelope_"
    const val FINANCE_NEXT_PROJECT = "finance_next_project"
    const val FINANCE_NEXT_PROJECT_DATE = "finance_next_project_date"
    const val FINANCE_NEXT_PROJECT_FOLLOWING = "finance_next_project_following"
    const val FINANCE_NEXT_PROJECT_NEGATIVE = "finance_next_project_negative"
    const val MONTH_BALANCE_EMPTY = "month_balance_empty"
    const val MONTH_BALANCE_ENVELOPES = "month_balance_envelopes"
    const val MONTH_BALANCE_ENVELOPES_EMPTY = "month_balance_envelopes_empty"
    const val MONTH_BALANCE_SATISFACTION = "month_balance_satisfaction"
    const val MONTH_BALANCE_SATISFACTION_EMPTY = "month_balance_satisfaction_empty"
    const val MONTH_BALANCE_SAVINGS = "month_balance_savings"
    const val MONTH_BALANCE_CUSHION = "month_balance_cushion"
    const val MONTH_BALANCE_ENVELOPE_PREFIX = "month_balance_envelope_"

    /**
     * Finance dashboard — the blocks of « À venir » (its sub-view is `subview_upcoming`): the week and the month after, the envelopes of the month after (one bar per category,
     * suffixed by its id), what is spent outside them, what is left toward the projects, and the
     * error with its retry (MAG-463).
     */
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

    /** Matelas — the row « Avancé à des proches », which only exists while a créance is open. */
    const val CUSHION_PEER_CLAIMS = "cushion_peer_claims"

    /** Matelas — the settings fields a journey fills (MAG-669), and the line that says where the saving goes. */
    const val CUSHION_CRITICAL_MONTHS = "cushion_critical_months"
    const val CUSHION_SHARE_PERCENT = "cushion_share_percent"
    const val CUSHION_SAVE = "cushion_save"
    const val CUSHION_SHARE_RULE = "cushion_share_rule"

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

    /** Day view — the trip line under an event (MAG-658), suffixed by the event's id; the « Y aller » of the next one has its own tag. */
    const val TRIP_LINE_PREFIX = "trip_line_"
    const val TRIP_GO = "trip_go"

    /** The full-screen « Pars maintenant » (MAG-658): the screen, its « Y aller » and its « Déjà en route ». */
    const val DEPARTURE_SCREEN = "departure_screen"
    const val DEPARTURE_GO = "departure_go"
    const val DEPARTURE_DISMISS = "departure_dismiss"

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

    /** Cuisine › Repas — « + Ajouter un repas » of a day: `meal_add_day_<monday … sunday>`. */
    const val MEAL_ADD_DAY_PREFIX = "meal_add_day_"

    /**
     * Tasks screen (MAG-408) — the root, the floating « + » (`tasks_add`), the empty line, and the
     * box that ticks a task, suffixed by the task's id. The header « Tâches › … ▾ » (MAG-626) is the
     * module breadcrumb: `breadcrumb_section` opens the menu, one `breadcrumb_menu_<key>` entry per view
     * (`today`, `upcoming`, `someday`, `done`, `routines`).
     */
    const val TASKS = "tasks"
    const val TASKS_ADD = "tasks_add"
    const val TASKS_EMPTY = "tasks_empty"
    const val TASKS_CHECK_PREFIX = "tasks_check_"

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
    const val FOCUS_TIMER_TOGGLE = "focus_timer_toggle"
    const val FOCUS_ESTIMATE = "focus_estimate"
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

    /**
     * The Bravo (MAG-508): the screen, its green circle, « {titre} · faite », the reward's message,
     * the day's bar, « Retour à ma journée » and the étoile filante; out of the day, the banner.
     */
    const val BRAVO = "bravo"
    const val BRAVO_CIRCLE = "bravo_circle"
    const val BRAVO_TITLE = "bravo_title"
    const val BRAVO_MESSAGE = "bravo_message"
    const val BRAVO_DAY_BAR = "bravo_day_bar"
    const val BRAVO_BACK = "bravo_back"
    const val BRAVO_SHOOTING_STAR = "bravo_shooting_star"
    const val TASKS_REWARD_BANNER = "tasks_reward_banner"

    /**
     * The constellation (MAG-509): the month's stars under the Bravo and in the evening, its title,
     * the latest star, the « +n » past a hundred, the all-time total and the arrows between months;
     * the star of a done task in « Mes 5 »; the palier banner and its dismissal.
     */
    const val CONSTELLATION = "constellation"
    const val CONSTELLATION_TITLE = "constellation_title"
    const val CONSTELLATION_STAR = "constellation_star"
    const val CONSTELLATION_LATEST = "constellation_latest"
    const val CONSTELLATION_MORE = "constellation_more"
    const val CONSTELLATION_TOTAL = "constellation_total"
    const val CONSTELLATION_PREVIOUS = "constellation_previous"
    const val CONSTELLATION_NEXT = "constellation_next"
    const val TASK_STAR = "task_star"
    const val PALIER = "palier"
    const val PALIER_TITLE = "palier_title"
    const val PALIER_MESSAGE = "palier_message"
    const val PALIER_DISMISS = "palier_dismiss"

    /**
     * The « Routines » tab of the tasks (MAG-511, entry `breadcrumb_menu_routines`): its list, « Nouvelle
     * routine », a group (suffixed by its key: morning, afternoon, evening, weekly, monthly), a card,
     * its tick and its total (suffixed by the routine's id); then the sheet: the title, a moment
     * (suffixed by the anchor), the hour, a repeat (suffixed by the kind), a weekday (1-7), a day of
     * the month (1-31), « Me le rappeler », « Active », « Enregistrer », « Supprimer » and its
     * confirmation.
     */
    const val ROUTINES = "routines"
    const val ROUTINES_ADD = "routines_add"
    const val ROUTINES_EMPTY = "routines_empty"
    const val ROUTINE_GROUP_PREFIX = "routine_group_"
    const val ROUTINE_CARD_PREFIX = "routine_card_"
    const val ROUTINE_CHECK_PREFIX = "routine_check_"
    const val ROUTINE_TOTAL_PREFIX = "routine_total_"
    const val ROUTINE_SHEET = "routine_sheet"
    const val ROUTINE_TITLE = "routine_title"
    const val ROUTINE_ANCHOR_PREFIX = "routine_anchor_"
    const val ROUTINE_TIME = "routine_time"
    const val ROUTINE_REPEAT_PREFIX = "routine_repeat_"
    const val ROUTINE_WEEKDAY_PREFIX = "routine_weekday_"
    const val ROUTINE_MONTH_DAY_PREFIX = "routine_month_day_"
    const val ROUTINE_REMIND = "routine_remind"
    const val ROUTINE_ACTIVE = "routine_active"
    const val ROUTINE_SAVE = "routine_save"
    const val ROUTINE_DELETE = "routine_delete"
    const val ROUTINE_DELETE_CONFIRM = "routine_delete_confirm"

    fun routineGroup(key: String) = ROUTINE_GROUP_PREFIX + key

    fun routineCard(id: String) = ROUTINE_CARD_PREFIX + id

    fun routineCheck(id: String) = ROUTINE_CHECK_PREFIX + id

    fun routineTotal(id: String) = ROUTINE_TOTAL_PREFIX + id

    fun routineAnchor(anchor: String) = ROUTINE_ANCHOR_PREFIX + anchor

    fun routineRepeat(kind: String) = ROUTINE_REPEAT_PREFIX + kind

    fun routineWeekday(weekday: Int) = ROUTINE_WEEKDAY_PREFIX + weekday

    fun routineMonthDay(day: Int) = ROUTINE_MONTH_DAY_PREFIX + day

    /** Routines in « Aujourd'hui » (MAG-512): the band above the ring, one pill per routine suffixed by its id. */
    const val ROUTINES_BAND = "routines_band"
    const val ROUTINE_PILL_PREFIX = "routine_pill_"

    fun routinePill(id: String) = ROUTINE_PILL_PREFIX + id

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
    fun drawerLine(route: String) = "drawer_line_$route"
    fun drawerCount(route: String) = "drawer_count_$route"

    fun railItem(route: String) = RAIL_ITEM_PREFIX + route

    fun breadcrumbMenu(key: String) = BREADCRUMB_MENU_PREFIX + key

    fun subview(key: String) = SUBVIEW_PREFIX + key

    fun financeScoreRing(period: String) = FINANCE_SCORE_RING_PREFIX + period
    fun financeScoreUnclassified(period: String) = FINANCE_SCORE_UNCLASSIFIED_PREFIX + period
    fun financeScoreClassify(period: String) = FINANCE_SCORE_CLASSIFY_PREFIX + period

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

    fun tripLine(eventId: String) = TRIP_LINE_PREFIX + eventId

    fun calendarDayView(date: LocalDate) = CALENDAR_DAY_VIEW_PREFIX + date

    fun calendarAllDay(date: LocalDate) = CALENDAR_ALL_DAY_PREFIX + date

    fun calendarMonthSlot(date: LocalDate, slot: Int) = "$CALENDAR_MONTH_SLOT_PREFIX${date}_$slot"

    fun calendarMonthMore(date: LocalDate) = CALENDAR_MONTH_MORE_PREFIX + date

    /** [dayIndex]: 0 is Monday, 6 is Sunday. */
    fun mealAddDay(dayIndex: Int) = MEAL_ADD_DAY_PREFIX + DayOfWeek.of(dayIndex + 1).name.lowercase()

    /**
     * The drawer entry the grocery journeys open. Covered by [DRAWER_ITEM_PREFIX]
     * already; kept as the constant the flows and [UiTagsTest] name.
     */
    const val DRAWER_GROCERY = "drawer_grocery"

    /** The grocery screen — the root, so a journey can wait for the screen itself. */
    const val GROCERY = "grocery"

    /** The search field of the product picker, a window of its own. */
    const val PRODUCT_SEARCH = "product_search"

    /** « + Produit récurrent », the end of the list: opens the recurring items (MAG-369, MAG-627). */
    const val GROCERY_RECURRING_OPEN = "grocery_recurring_open"

    /** The recurring items' « Ajouter un produit », which opens the product search. */
    const val RECURRING_ADD = "recurring_add"

    /** The repeat icon and frequency under a list line whose product comes back by itself. */
    const val RECURRING_INDICATOR = "recurring_indicator"

    /** One choice of the « Récurrent » setting: `recurring_option_none`, `_weekly`, `_biweekly`, `_monthly`. */
    const val RECURRING_OPTION_PREFIX = "recurring_option_"

    fun recurringOption(value: String) = RECURRING_OPTION_PREFIX + value

    /** « Courses passées » in the list's « ⋮ » menu, the « Courses passées » screen and one errand's sheet (MAG-488). */
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

    /** The quantity of a line being added or edited (MAG-573): its modes, stepper, field and conversion text. */
    const val GROCERY_QUANTITY_ENTRY = "grocery_quantity_entry"
    const val GROCERY_QUANTITY_MODE_QUANTITY = "grocery_quantity_mode_quantity"
    const val GROCERY_QUANTITY_MODE_PACKAGINGS = "grocery_quantity_mode_packagings"
    const val GROCERY_QUANTITY_FIELD = "grocery_quantity_field"
    const val GROCERY_PACKAGINGS_FIELD = "grocery_packagings_field"
    const val GROCERY_PACKAGING_CAPTION = "grocery_packaging_caption"
    const val GROCERY_QUANTITY_SUMMARY = "grocery_quantity_summary"
    const val GROCERY_PREFILLED_CHIP = "grocery_prefilled_chip"
    const val GROCERY_QUANTITY_SUGGESTION_PREFIX = "grocery_quantity_suggestion_"

    fun groceryQuantitySuggestion(index: Int) = GROCERY_QUANTITY_SUGGESTION_PREFIX + index

    /** « Pour les recettes de la semaine » box of the add sheet, and the alert when the line does not cover it (MAG-573). */
    const val GROCERY_RECIPE_NEED_BOX = "grocery_recipe_need_box"
    const val GROCERY_RECIPE_NEED_MEAL = "grocery_recipe_need_meal"
    const val GROCERY_COVERAGE_ALERT = "grocery_coverage_alert"
    const val GROCERY_COVERAGE_ADJUST = "grocery_coverage_adjust"
    const val GROCERY_ADD_ERROR = "grocery_add_error"

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
     * La Revue, one list for the lines to qualify and the recognised ones (MAG-539): the three state pills, the three
     * periods and the month arrows, a row and its state pill, the two group headings, the empty list.
     */
    const val REVIEW_LIST = "review_list"
    const val REVIEW_LIST_PERIOD_MONTH = "review_list_period_month"
    const val REVIEW_LIST_PERIOD_SINCE_REVIEW = "review_list_period_since_review"
    const val REVIEW_LIST_PERIOD_ALL = "review_list_period_all"
    const val REVIEW_LIST_MONTH_PREVIOUS = "review_list_month_previous"
    const val REVIEW_LIST_MONTH_NEXT = "review_list_month_next"
    const val REVIEW_LIST_SECTION_UNRECOGNISED = "review_list_section_unrecognised"
    const val REVIEW_LIST_SECTION_RECOGNISED = "review_list_section_recognised"
    const val REVIEW_LIST_ROW = "review_list_row"
    const val REVIEW_LIST_ROW_STATE = "review_list_row_state"
    const val REVIEW_LIST_EMPTY = "review_list_empty"

    /**
     * The fiche of a line of the Revue: the line, its state pill, the gestures of a line to qualify (« Valider »,
     * « Autre catégorie » and its search, « Créer une opération récurrente », the merchant switch, the qualification, the internal
     * transfer and its « sans contrepartie », the contact), those of a recognised one (« Correct », « Corriger » and its
     * category, search and save, « Voir l'opération récurrente »), « Voir la fiche » and the two satisfactions.
     */
    const val REVIEW_FICHE = "review_fiche"
    const val REVIEW_FICHE_STATE = "review_fiche_state"
    const val REVIEW_FICHE_CHECKED = "review_fiche_checked"
    const val REVIEW_FICHE_VALIDATE = "review_fiche_validate"
    const val REVIEW_FICHE_OTHER = "review_fiche_other"
    const val REVIEW_FICHE_SEARCH = "review_fiche_search"
    const val REVIEW_FICHE_CREATE_OPERATION = "review_fiche_create_operation"
    const val REVIEW_FICHE_ALWAYS = "review_fiche_always"
    const val REVIEW_FICHE_QUALIFICATION = "review_fiche_qualification"
    const val REVIEW_FICHE_TRANSFER = "review_fiche_transfer"
    const val REVIEW_FICHE_TRANSFER_NONE = "review_fiche_transfer_none"
    const val REVIEW_FICHE_CONTACT = "review_fiche_contact"
    const val REVIEW_FICHE_CONTACT_NAME = "review_fiche_contact_name"
    const val REVIEW_FICHE_CONTROL = "review_fiche_control"
    const val REVIEW_FICHE_CORRECT = "review_fiche_correct"
    const val REVIEW_FICHE_OPEN_OPERATION = "review_fiche_open_operation"
    const val REVIEW_FICHE_OPEN_SHEET = "review_fiche_open_sheet"
    const val REVIEW_FICHE_KEEP = "review_fiche_keep"
    const val REVIEW_FICHE_AVOIDABLE = "review_fiche_avoidable"
    const val REVIEW_CORRECTION_CATEGORY = "review_correction_category"
    const val REVIEW_CORRECTION_SEARCH = "review_correction_search"
    const val REVIEW_CORRECTION_SAVE = "review_correction_save"

    /**
     * The review pages' fiche (MAG-507): « Précédent » / « Suivant », the « 3 / 12 » position and the way back to the
     * list, shared by the Revue's fiche.
     */
    const val REVIEW_PREVIOUS = "review_previous"
    const val REVIEW_NEXT = "review_next"
    const val REVIEW_POSITION = "review_position"
    const val REVIEW_BACK_TO_LIST = "review_back_to_list"
    const val REVIEW_OPEN_RULES = "review_open_rules"
    const val REVIEW_OPEN_RECURRING = "review_open_recurring"
    const val TREASURY_OPEN_CUSHION = "treasury_open_cushion"
    const val TREASURY_OPEN_SAVINGS_ADVANCES = "treasury_open_savings_advances"

    /** The « À qualifier · n · montant » pill of the finance dashboard and the budget screen. */
    const val TO_QUALIFY_PILL = "to_qualify_pill"

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

    /** A contact's history (MAG-455): the section and one transfer that gave a share back. */
    const val CONTACT_HISTORY = "contact_history"
    const val CONTACT_HISTORY_LINE = "contact_history_line"

    /**
     * « Créances et dettes » (MAG-455): the screen and its retry, the two totals, the two tabs, a person with
     * their lines per object, what compensates, the link to their page, an object, « + Personne », and the empty state.
     */
    const val PEER_DEBTS = "peer_debts"
    const val PEER_DEBTS_RETRY = "peer_debts_retry"
    const val PEER_DEBTS_TILE_OWED_TO_ME = "peer_debts_tile_owed_to_me"
    const val PEER_DEBTS_TILE_I_OWE = "peer_debts_tile_i_owe"
    const val PEER_DEBTS_TAB_PERSON = "peer_debts_tab_person"
    const val PEER_DEBTS_TAB_OBJECT = "peer_debts_tab_object"
    const val PEER_DEBTS_PERSON = "peer_debts_person"
    const val PEER_DEBTS_PERSON_LINE = "peer_debts_person_line"
    const val PEER_DEBTS_COMPENSATION = "peer_debts_compensation"
    const val PEER_DEBTS_CONTACT_LINK = "peer_debts_contact_link"
    const val PEER_DEBTS_OBJECT = "peer_debts_object"
    const val PEER_DEBTS_ADD_PERSON = "peer_debts_add_person"
    const val PEER_DEBTS_EMPTY = "peer_debts_empty"

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

    /** The spinner at the foot of an account's transaction list while the next page is read (MAG-436). */
    const val TRANSACTIONS_LOADING_MORE = "transactions_loading_more"

    /**
     * The tablet's account detail (MAG-422): the page of an account and its balance, the two actions and
     * the balance form, then the timeline's day titles (suffixed by the date), lines, status words,
     * missed occurrences, « +33 % » mark, « Mois précédent » and « Réessayer ».
     */
    const val ACCOUNT_PAGE = "account_page"
    const val ACCOUNT_BALANCE = "account_balance"
    const val ACCOUNT_ADD_TRANSACTION = "account_add_transaction"
    const val ACCOUNT_CORRECT_BALANCE = "account_correct_balance"
    /** The ⋮ of the phone's account bar (MAG-632): « Corriger le solde » of a hand-kept account lives in it. */
    const val ACCOUNT_MENU = "account_menu"
    const val ACCOUNT_BALANCE_INPUT = "account_balance_input"
    const val ACCOUNT_BALANCE_SAVE = "account_balance_save"
    const val ACCOUNT_ALL_TRANSACTIONS = "account_all_transactions"
    const val TIMELINE_DAY_PREFIX = "timeline_day_"
    const val TIMELINE_LINE = "timeline_line"
    const val TIMELINE_STATUS = "timeline_status"
    const val TIMELINE_MISSED = "timeline_missed"
    const val TIMELINE_INCREASE = "timeline_increase"
    const val TIMELINE_PREVIOUS_MONTH = "timeline_previous_month"
    const val TIMELINE_RETRY = "timeline_retry"

    fun timelineDay(date: String) = TIMELINE_DAY_PREFIX + date

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

    /** The notifications list's « Ajouter à la liste » on a shortage alert (MAG-570). */
    const val NOTIFICATION_ADD_TO_LIST = "notification_add_to_list"

    /** Settings > Notifications — the switch of the chime and the vibration that announce her. */
    const val SETTINGS_INTERRUPTION_SOUND = "settings_interruption_sound"

    /** Réglages › « Se déconnecter », the text button under the list (MAG-634). */
    const val SETTINGS_LOGOUT = "settings_logout"

    /** Réglages › « Partir à l'heure » switch (MAG-651). */
    const val SETTINGS_DEPARTURE = "settings_departure"

    /**
     * The row named after the agent (« Maggie »): by id, because on a tablet the launcher's taskbar
     * also carries an app called « Maggie » and a tap by text lands on it.
     */
    const val SETTINGS_AGENT = "settings_agent"

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

    /** « Rappels et récompenses » (MAG-508): the three switches and the tone's choice ([settingsTasksTone]). */
    const val SETTINGS_TASKS_REWARD_ANIMATION = "settings_tasks_reward_animation"
    const val SETTINGS_TASKS_REWARD_VIBRATION = "settings_tasks_reward_vibration"
    const val SETTINGS_TASKS_REWARD_SOUND = "settings_tasks_reward_sound"
    const val SETTINGS_TASKS_TONE_PREFIX = "settings_tasks_tone_"

    /**
     * « Rappels et récompenses » (MAG-510): « Au plus, par jour » (− , the number, +), « Calme » from
     * and to, and one row per moment of « Mes moments » ([settingsTasksMoment]).
     */
    const val SETTINGS_TASKS_REMINDER_CAP = "settings_tasks_reminder_cap"
    const val SETTINGS_TASKS_REMINDER_CAP_MINUS = "settings_tasks_reminder_cap_minus"
    const val SETTINGS_TASKS_REMINDER_CAP_VALUE = "settings_tasks_reminder_cap_value"
    const val SETTINGS_TASKS_REMINDER_CAP_PLUS = "settings_tasks_reminder_cap_plus"
    const val SETTINGS_TASKS_QUIET_START = "settings_tasks_quiet_start"
    const val SETTINGS_TASKS_QUIET_END = "settings_tasks_quiet_end"
    const val SETTINGS_TASKS_MOMENT_PREFIX = "settings_tasks_moment_"

    /** A moment's row: `wake`, `afterBreakfast`, `afterLunch` or `afterDinner`, as the API names it. */
    fun settingsTasksMoment(moment: String): String = SETTINGS_TASKS_MOMENT_PREFIX + moment

    /** The tone's segment: `doux`, `direct` or `drole`. */
    fun settingsTasksTone(tone: String): String = SETTINGS_TASKS_TONE_PREFIX + tone

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

    /** « Quantités pour N convives » on the ingredient choice. */
    const val MEAL_CHOICE_GUESTS = "meal_choice_guests"

    /**
     * Cuisine › Repas (MAG-628): the week's label and its arrows; a meal's card, its convives stepper
     * (`meal_card_guests_minus`, `_value`, `_plus`) and « Ingrédients sur la liste »; each recipe of the card
     * with its portions stepper (`meal_recipe_portions_minus`, `_value`, `_plus` — the simulation, nothing
     * saved), its « Lancer » and what it brings per person.
     */
    const val MEALS_WEEK_LABEL = "meals_week_label"
    const val MEALS_WEEK_PREVIOUS = "meals_week_previous"
    const val MEALS_WEEK_NEXT = "meals_week_next"
    const val MEAL_CARD = "meal_card"
    const val MEAL_CARD_GUESTS = "meal_card_guests"
    const val MEAL_CARD_GUESTS_MINUS = "meal_card_guests_minus"
    const val MEAL_CARD_GUESTS_VALUE = "meal_card_guests_value"
    const val MEAL_CARD_GUESTS_PLUS = "meal_card_guests_plus"
    const val MEAL_CARD_INGREDIENTS = "meal_card_ingredients"
    const val MEAL_RECIPE_PORTIONS = "meal_recipe_portions"
    const val MEAL_RECIPE_PORTIONS_MINUS = "meal_recipe_portions_minus"
    const val MEAL_RECIPE_PORTIONS_VALUE = "meal_recipe_portions_value"
    const val MEAL_RECIPE_PORTIONS_PLUS = "meal_recipe_portions_plus"
    const val MEAL_RECIPE_LAUNCH = "meal_recipe_launch"
    const val MEAL_RECIPE_FACTS = "meal_recipe_facts"

    /** The recipe FAB of « Cuisine › Recettes ▾ » (MAG-628), whose breadcrumb wears the `breadcrumb_*` tags (MAG-670). */
    const val COOKBOOK_RECIPE_CREATE = "cookbook_recipe_create"

    /** The recipe list (MAG-628): the search field, the « Toutes » pill, a tag's pill, a row's avatar and its « 515 kcal · 35 min ». */
    const val RECIPE_SEARCH_FIELD = "recipe_search_field"
    const val RECIPE_FILTER_ALL = "recipe_filter_all"
    const val RECIPE_FILTER_TAG = "recipe_filter_tag"
    const val RECIPE_ROW_AVATAR = "recipe_row_avatar"
    const val RECIPE_ROW_FACTS = "recipe_row_facts"

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
    /** « Cuisiner » on a recipe sheet: opens the Cuisine activity (MAG-580). */
    const val RECIPE_COOK = "recipe_cook"
    const val RECIPE_COOK_RESUME = "recipe_cook_resume"
    const val RECIPE_COOK_RESUME_DETAIL = "recipe_cook_resume_detail"
    const val RECIPE_EDIT = "recipe_edit"
    const val RECIPE_MENU = "recipe_menu"
    const val RECIPE_DELETE = "recipe_delete"
    const val RECIPE_NUTRITION = "recipe_nutrition"
    const val RECIPE_MICRONUTRIENTS_TOGGLE = "recipe_micronutrients_toggle"

    /** The « de saison » / « hors saison » badge of a product, a grocery line or an ingredient (MAG-494), and the product's season chips. */
    const val SEASON_BADGE = "season_badge"
    const val SEASON_MONTHS = "season_months"

    /**
     * The product sheet of Courses › Produits (MAG-605): the sheet itself, its error line, « Enregistrer »,
     * the four fields of the store cascade — « Magasin habituel » then its rayon, « À défaut » then its rayon.
     */
    const val PRODUCT_SHEET = "product_sheet"
    /** The grocery list's « ⋮ » menu (MAG-627: « Courses passées »). */
    const val GROCERY_MENU = "grocery_menu"
    const val PRODUCT_SHEET_ERROR = "product_sheet_error"
    const val PRODUCT_SHEET_SAVE = "product_sheet_save"
    /** « Supprimer le produit » at the foot of the sheet, and the « Supprimer » of its confirmation (MAG-671). */
    const val PRODUCT_SHEET_DELETE = "product_sheet_delete"
    const val PRODUCT_SHEET_DELETE_CONFIRM = "product_sheet_delete_confirm"
    const val PRODUCT_SHEET_STORE = "product_sheet_store"
    const val PRODUCT_SHEET_AISLE = "product_sheet_aisle"
    const val PRODUCT_SHEET_FALLBACK_STORE = "product_sheet_fallback_store"
    const val PRODUCT_SHEET_FALLBACK_AISLE = "product_sheet_fallback_aisle"

    /** The three filter chips of Courses › Produits (MAG-606): Magasin, Rayon, Trier — each opens a bottom sheet. */
    const val PRODUCT_FILTER_STORE = "product_filter_store"
    const val PRODUCT_FILTER_AISLE = "product_filter_aisle"
    const val PRODUCT_FILTER_SORT = "product_filter_sort"

    /** One choice of a filter's bottom sheet — the same tag on each, told apart by its text, which the list behind may repeat. */
    const val PRODUCT_FILTER_OPTION = "product_filter_option"

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

    /** The meal's sheet (MAG-378): its title, « Enregistrer », « Supprimer » and the ingredients. */
    const val MEAL_DETAIL_TITLE = "meal_detail_title"
    const val MEAL_DETAIL_SAVE = "meal_detail_save"
    const val MEAL_DETAIL_DELETE = "meal_detail_delete"
    const val MEAL_DETAIL_INGREDIENTS = "meal_detail_ingredients"
    const val MEAL_DETAIL_LAUNCH = "meal_detail_launch"

    // Activité Comptes · la revue (MAG-456): the full-screen wizard, its header, its footer and the card of a line.
    const val EVENT_LAUNCH_ACTIVITY = "event_launch_activity"
    const val REVIEW_ACTIVITY = "review_activity"
    const val REVIEW_ACTIVITY_SEGMENT_PREFIX = "review_activity_segment_"
    const val REVIEW_ACTIVITY_STEP_TITLE = "review_activity_step_title"
    const val REVIEW_ACTIVITY_NEXT_STEP = "review_activity_next_step"
    const val REVIEW_ACTIVITY_QUIT = "review_activity_quit"
    const val REVIEW_ACTIVITY_MAGGIE = "review_activity_maggie"
    const val REVIEW_ACTIVITY_PREVIOUS = "review_activity_previous"
    const val REVIEW_ACTIVITY_NEXT = "review_activity_next"
    const val REVIEW_ACTIVITY_CARD = "review_activity_card"
    const val REVIEW_ACTIVITY_LABEL = "review_activity_label"
    const val REVIEW_ACTIVITY_COUNTER = "review_activity_counter"
    const val REVIEW_ACTIVITY_ACCEPT = "review_activity_accept"
    const val REVIEW_ACTIVITY_CHANGE = "review_activity_change"
    const val REVIEW_ACTIVITY_CREATE_RULE = "review_activity_create_rule"
    const val REVIEW_ACTIVITY_CORRECT = "review_activity_correct"
    const val REVIEW_ACTIVITY_SEARCH = "review_activity_search"
    const val REVIEW_ACTIVITY_KEEP = "review_activity_keep"
    const val REVIEW_ACTIVITY_AVOIDABLE = "review_activity_avoidable"
    const val REVIEW_ACTIVITY_DONE = "review_activity_done"
    const val REVIEW_ACTIVITY_RESTART = "review_activity_restart"
    const val REVIEW_ACTIVITY_REMAINING = "review_activity_remaining"

    // The frame every activity shares (MAG-580): the activity itself, Maggie and ✕.
    const val ACTIVITY_SCAFFOLD = "activity_scaffold"
    const val ACTIVITY_MAGGIE = "activity_maggie"
    const val ACTIVITY_QUIT = "activity_quit"

    // Activité Cuisine (MAG-580): « Pour combien ? », « On sort tout », a step, « Bon appétit » and the states between.
    const val COOKING_LOADING = "cooking_loading"
    const val COOKING_ERROR = "cooking_error"
    const val COOKING_RETRY = "cooking_retry"
    // A Cuisine already in progress on another origin, and « Recommencer » (MAG-615).
    const val COOKING_CONFLICT = "cooking_conflict"
    const val COOKING_CONFLICT_DETAIL = "cooking_conflict_detail"
    const val COOKING_RESUME = "cooking_resume"
    const val COOKING_REPLACE = "cooking_replace"
    const val COOKING_RESTART = "cooking_restart"
    const val COOKING_EMPTY = "cooking_empty"
    const val COOKING_EMPTY_BACK = "cooking_empty_back"
    const val COOKING_DONE = "cooking_done"
    const val COOKING_FINISH = "cooking_finish"
    const val COOKING_PROGRESS = "cooking_progress"
    const val COOKING_SERVINGS = "cooking_servings"
    const val COOKING_SERVINGS_MINUS = "cooking_servings_minus"
    const val COOKING_SERVINGS_PLUS = "cooking_servings_plus"
    const val COOKING_SERVINGS_VALUE = "cooking_servings_value"
    const val COOKING_CONTINUE = "cooking_continue"
    const val COOKING_PREPARATION = "cooking_preparation"
    const val COOKING_GUESTS_MINUS = "cooking_guests_minus"
    const val COOKING_GUESTS_PLUS = "cooking_guests_plus"
    const val COOKING_GUESTS_VALUE = "cooking_guests_value"
    const val COOKING_INGREDIENT = "cooking_ingredient"
    const val COOKING_UTENSIL = "cooking_utensil"
    const val COOKING_QUEUE_ITEM = "cooking_queue_item"
    const val COOKING_QUEUE_CURRENT = "cooking_queue_current"
    const val COOKING_START = "cooking_start"
    const val COOKING_NO_STEPS = "cooking_no_steps"
    const val COOKING_SKIP = "cooking_skip"
    const val COOKING_STEP = "cooking_step"
    const val COOKING_STEP_TEXT = "cooking_step_text"
    const val COOKING_PREVIOUS = "cooking_previous"
    const val COOKING_NEXT = "cooking_next"

    // The step by step (MAG-581): segments, number, what a step takes, the next step.
    const val COOKING_SEGMENTS = "cooking_segments"
    const val COOKING_SEGMENT = "cooking_segment"
    const val COOKING_STEP_NUMBER = "cooking_step_number"
    const val COOKING_STEP_INGREDIENT = "cooking_step_ingredient"
    const val COOKING_STEP_UTENSIL = "cooking_step_utensil"
    const val COOKING_UPNEXT = "cooking_upnext"
    const val COOKING_DONE_SUMMARY = "cooking_done_summary"
    // The timers (MAG-582)
    const val COOKING_TIMER_ROW_CLOCK = "cooking_timer_row_clock"
    // The steps of the meal (MAG-614): the column or the banner and its sheet, one row per step with its mark.
    const val COOKING_STEPS_COLUMN = "cooking_steps_column"
    const val COOKING_STEPS_BANNER = "cooking_steps_banner"
    const val COOKING_STEPS_BANNER_PROGRESS = "cooking_steps_banner_progress"
    const val COOKING_STEPS_BANNER_RINGING = "cooking_steps_banner_ringing"
    const val COOKING_STEPS_SHEET = "cooking_steps_sheet"
    const val COOKING_STEPS_PROGRESS = "cooking_steps_progress"
    const val COOKING_STEPS_RECIPE = "cooking_steps_recipe"
    const val COOKING_STEP_ROW = "cooking_step_row"
    const val COOKING_STEP_CHECK = "cooking_step_check"
    const val COOKING_STEP_CURRENT = "cooking_step_current"
    const val COOKING_STEP_RINGING = "cooking_step_ringing"
    const val COOKING_STEP_TIMER = "cooking_step_timer"
    const val COOKING_STEP_TIMER_CLOCK = "cooking_step_timer_clock"
    const val COOKING_STEP_TIMER_SUB = "cooking_step_timer_sub"
    const val COOKING_TIMER_TOGGLE = "cooking_timer_toggle"
    const val COOKING_TIMER_MINUS = "cooking_timer_minus"
    const val COOKING_TIMER_PLUS = "cooking_timer_plus"
    const val COOKING_TIMER_ERROR = "cooking_timer_error"

    // La liste de courses dans l'activité Cuisine (MAG-583).
    const val COOKING_GROCERY_BUTTON = "cooking_grocery_button"
    const val COOKING_GROCERY_COUNT = "cooking_grocery_count"
    const val COOKING_GROCERY_SHEET = "cooking_grocery_sheet"
    const val COOKING_GROCERY_CLOSE = "cooking_grocery_close"
    const val COOKING_GROCERY_LOADING = "cooking_grocery_loading"
    const val COOKING_GROCERY_ERROR = "cooking_grocery_error"
    const val COOKING_GROCERY_RETRY = "cooking_grocery_retry"
    const val COOKING_GROCERY_EMPTY = "cooking_grocery_empty"
    const val COOKING_GROCERY_STORE = "cooking_grocery_store"
    const val COOKING_GROCERY_ITEM = "cooking_grocery_item"
    const val COOKING_GROCERY_CHECK = "cooking_grocery_check"
    const val COOKING_GROCERY_MEAL = "cooking_grocery_meal"
    const val COOKING_GROCERY_SHORT = "cooking_grocery_short"
    const val COOKING_GROCERY_MESSAGE = "cooking_grocery_message"

    // The two steps that close a quarter, a semester or a year (MAG-462).
    const val PERIOD_BALANCE = "review_period_balance"
    const val PERIOD_BALANCE_MONTHS = "review_period_balance_months"
    const val PERIOD_BALANCE_ENVELOPES = "review_period_balance_envelopes"
    const val PERIOD_BALANCE_PROJECTS = "review_period_balance_projects"
    const val PERIOD_BALANCE_ERROR = "review_period_balance_error"
    const val PERIOD_PLAN = "review_period_plan"
    const val PERIOD_PLAN_ROW_PREFIX = "review_period_plan_row_"
    const val PERIOD_PLAN_AMOUNT_PREFIX = "review_period_plan_amount_"
    const val PERIOD_PLAN_ONE_OFF_NAME = "review_period_plan_one_off_name"
    const val PERIOD_PLAN_ONE_OFF_AMOUNT = "review_period_plan_one_off_amount"
    const val PERIOD_PLAN_ONE_OFF_ADD = "review_period_plan_one_off_add"
    const val PERIOD_PLAN_ONE_OFF_ROW = "review_period_plan_one_off_row"
    const val PERIOD_PLAN_APPLY = "review_period_plan_apply"
    const val PERIOD_PLAN_APPLIED = "review_period_plan_applied"
    const val PERIOD_PLAN_REFUSED = "review_period_plan_refused"
    const val PERIOD_PLAN_ERROR = "review_period_plan_error"

    /**
     * « Projets » of Planification (MAG-519): the screen, a row, its drag handle, the warning of a
     * dependency placed after it, the empty state, « Nouveau projet »; the full-screen form, its fields,
     * a wellbeing level, a date mode, a dependency, what the server refused, « Enregistrer » and « Supprimer ».
     */
    const val PROJECTS = "projects"
    const val PROJECT_ROW = "project_row"
    const val PROJECT_DRAG_HANDLE = "project_drag_handle"
    const val PROJECT_UNMET = "project_unmet"
    const val PROJECT_EMPTY = "project_empty"
    const val PROJECT_ADD = "project_add"
    const val PROJECT_FORM = "project_form"
    const val PROJECT_NAME = "project_name"
    const val PROJECT_AMOUNT = "project_amount"
    const val PROJECT_WELLBEING_PREFIX = "project_wellbeing_"
    const val PROJECT_DATE_MODE_PREFIX = "project_date_mode_"
    const val PROJECT_TARGET_ON = "project_target_on"
    const val PROJECT_DEPENDENCY_PREFIX = "project_dependency_"
    const val PROJECT_NOTE = "project_note"
    const val PROJECT_FORM_ERROR = "project_form_error"
    const val PROJECT_SAVE = "project_save"
    const val PROJECT_PREVIEW = "project_preview"
    const val PROJECT_PREVIEW_DATE = "project_preview_date"
    const val PROJECT_PREVIEW_N = "project_preview_n"
    const val PROJECT_PREVIEW_CONFIRM = "project_preview_confirm"
    const val PROJECT_PREVIEW_CANCEL = "project_preview_cancel"
    const val PROJECT_DELETE = "project_delete"
    const val PROJECT_DELETE_CONFIRM = "project_delete_confirm"
    const val PROJECT_GANTT = "project_gantt"
    const val GANTT_ROW = "gantt_row"
    const val GANTT_CUSHION = "gantt_cushion"
    const val GANTT_SAVING = "gantt_saving"
    const val GANTT_PURCHASE = "gantt_purchase"
    const val GANTT_LOAN = "gantt_loan"
    const val GANTT_RENT = "gantt_rent"
    const val GANTT_RESALE = "gantt_resale"
    const val GANTT_DEADLINE = "gantt_deadline"
    const val GANTT_FIXED = "gantt_fixed"
    const val GANTT_UNMET = "gantt_unmet"
    const val GANTT_BEYOND = "gantt_beyond"
    const val GANTT_TODAY = "gantt_today"
    const val GANTT_DETAIL = "gantt_detail"
    const val GANTT_ERROR = "gantt_error"

    /**
     * The 20-year frise of the planning card (MAG-554): the « 36 mois · 20 ans » switch, the frise, the
     * assumptions it rests on, the sentence on the gap of N, the scenario to compare with, one row per
     * event (`timeline_event_<type>` for the followed scenario, `timeline_compared_event_<type>` for the
     * other one), the marker of the date N on each track, what could not be read and its retry.
     */
    const val TIMELINE_HORIZON_PREFIX = "timeline_horizon_"
    const val TIMELINE_FRISE = "timeline_frise"
    const val TIMELINE_ASSUMPTIONS = "timeline_assumptions"
    const val TIMELINE_DIFFERENCE = "timeline_difference"
    const val TIMELINE_NOTICE = "timeline_notice"
    const val TIMELINE_COMPARE_PREFIX = "timeline_compare_"
    const val TIMELINE_EVENT_PREFIX = "timeline_event_"
    const val TIMELINE_COMPARED_EVENT_PREFIX = "timeline_compared_event_"
    const val TIMELINE_TRACK = "timeline_track"
    const val TIMELINE_COMPARED_TRACK = "timeline_compared_track"
    const val TIMELINE_N_MARKER = "timeline_n_marker"
    const val TIMELINE_LOADING = "timeline_loading"
    const val TIMELINE_ERROR = "timeline_error"
    const val FUNDING_CHART = "funding_chart"
    const val FUNDING_LEGEND = "funding_legend"
    const val FUNDING_MONTH = "funding_month"
    const val FUNDING_SALARY = "funding_salary"
    const val FUNDING_SAVINGS = "funding_savings"
    const val FUNDING_CAPITAL = "funding_capital"
    const val FUNDING_OVER = "funding_over"
    const val FUNDING_CAPACITY = "funding_capacity"
    const val FUNDING_TODAY = "funding_today"
    const val FUNDING_DETAIL = "funding_detail"

    /**
     * The sheet of a project (MAG-525): the sources in words on its row, the sheet, « Modifier », the stacked
     * bar of the sources, a source, what is left on the salary, the three priced options and their figures;
     * on the tablet the sources editor (add, remove, − / +, save, what the server refused) and the options' parameters.
     */
    const val PROJECT_FUNDING_SUMMARY = "project_funding_summary"
    const val PROJECT_SHEET = "project_sheet"
    const val PROJECT_SHEET_EDIT = "project_sheet_edit"
    const val PROJECT_FUNDING_BAR = "project_funding_bar"
    const val PROJECT_FUNDING_SOURCE = "project_funding_source"
    const val PROJECT_FUNDING_SALARY = "project_funding_salary"
    const val PROJECT_OPTIONS = "project_options"
    const val PROJECT_OPTIONS_ERROR = "project_options_error"
    const val PROJECT_OPTION_PREFIX = "project_option_"
    const val PROJECT_OPTION_TOTAL_PREFIX = "project_option_total_"
    const val PROJECT_OPTION_NET_PREFIX = "project_option_net_"
    const val PROJECT_OPTION_MONTHLY_PREFIX = "project_option_monthly_"
    const val PROJECT_OPTION_DELAY_PREFIX = "project_option_delay_"
    const val PROJECT_FUNDING_ADD_PREFIX = "project_funding_add_"
    const val PROJECT_FUNDING_AMOUNT = "project_funding_amount"
    const val PROJECT_FUNDING_MINUS = "project_funding_minus"
    const val PROJECT_FUNDING_PLUS = "project_funding_plus"
    const val PROJECT_FUNDING_REMOVE = "project_funding_remove"
    const val PROJECT_FUNDING_ACCOUNT_PREFIX = "project_funding_account_"
    const val PROJECT_FUNDING_PLACEMENT_PREFIX = "project_funding_placement_"
    const val PROJECT_FUNDING_PLACEMENT_NONE = "project_funding_placement_none"
    const val PROJECT_FUNDING_SAVE = "project_funding_save"
    const val PROJECT_FUNDING_ERROR = "project_funding_error"
    const val PROJECT_OPTIONS_RATE = "project_options_rate"
    const val PROJECT_OPTIONS_MONTHS = "project_options_months"
    const val PROJECT_OPTIONS_DOWN_PAYMENT = "project_options_down_payment"
    const val PROJECT_OPTIONS_YIELD = "project_options_yield"
    const val PROJECT_OPTIONS_APPLY = "project_options_apply"
    const val PROJECT_INVESTMENT = "project_investment"
    const val PROJECT_INVESTMENT_PRICE = "project_investment_price"
    const val PROJECT_INVESTMENT_DOWN_PAYMENT = "project_investment_down_payment"
    const val PROJECT_INVESTMENT_RENT = "project_investment_rent"
    const val PROJECT_INVESTMENT_LOAN = "project_investment_loan"
    const val PROJECT_INVESTMENT_BALANCE = "project_investment_balance"
    const val PROJECT_INVESTMENT_RESALE = "project_investment_resale"
    const val PROJECT_ROW_INVESTMENT = "project_row_investment"

    // Scenarios of the projects (MAG-551). The `_PREFIX` ones are followed by the scenario's id (`auto-rapid`, a ULID…).
    const val SCENARIO_CURRENT_CARD = "scenario_current_card"
    const val SCENARIO_CURRENT_TITLE = "scenario_current_title"
    const val SCENARIO_CURRENT_N = "scenario_current_n"
    const val SCENARIO_SHEET = "scenario_sheet"
    const val SCENARIO_PANEL = "scenario_panel"
    const val SCENARIO_NONE = "scenario_none"
    const val SCENARIO_ADD = "scenario_add"
    const val SCENARIO_CLEAR_CURRENT = "scenario_clear_current"
    const val SCENARIO_CURVES = "scenario_curves"
    const val SCENARIO_TABLE = "scenario_table"
    const val SCENARIO_COMPARE_CLEAR = "scenario_compare_clear"
    const val SCENARIO_COMPARE_ERROR = "scenario_compare_error"
    const val SCENARIO_ERROR = "scenario_error"
    const val SCENARIO_DELETE_CONFIRM = "scenario_delete_confirm"
    const val SCENARIO_ROW_PREFIX = "scenario_row_"
    const val SCENARIO_BADGE_PREFIX = "scenario_badge_"
    const val SCENARIO_N_PREFIX = "scenario_n_"
    const val SCENARIO_GAP_PREFIX = "scenario_gap_"
    const val SCENARIO_MAKE_CURRENT_PREFIX = "scenario_make_current_"
    const val SCENARIO_DUPLICATE_PREFIX = "scenario_duplicate_"
    const val SCENARIO_COMPARE_PREFIX = "scenario_compare_"
    const val SCENARIO_EDIT_PREFIX = "scenario_edit_"
    const val SCENARIO_DELETE_PREFIX = "scenario_delete_"
    const val SCENARIO_DISMISS_PREFIX = "scenario_dismiss_"
    const val SCENARIO_EDITOR = "scenario_editor"
    const val SCENARIO_NAME = "scenario_name"
    const val SCENARIO_SAVING = "scenario_saving"
    const val SCENARIO_SAVE = "scenario_save"
    const val SCENARIO_EDITOR_ERROR = "scenario_editor_error"
    const val SCENARIO_PROJECT_PREFIX = "scenario_project_"
    const val SCENARIO_PROJECT_UP_PREFIX = "scenario_project_up_"
    const val SCENARIO_PROJECT_DOWN_PREFIX = "scenario_project_down_"
    const val SCENARIO_PLACEMENT_PREFIX = "scenario_placement_"
    const val SCENARIO_REPAYMENT_ADD = "scenario_repayment_add"
    const val SCENARIO_REPAYMENT_PREFIX = "scenario_repayment_"
    const val SCENARIO_REPAYMENT_ROW_PREFIX = "scenario_repayment_row_"
    const val SCENARIO_REPAYMENT_FIGURES_PREFIX = "scenario_repayment_figures_"

    /**
     * The loans of Planification and their sheet (MAG-523): a row, the sheet, its IRA block and its curve;
     * on the tablet the conditions form (a type, a mode, the payment, « Enregistrer », what the server
     * refused) and the simulation of an amount.
     */
    const val LOAN_ROW = "loan_row"
    const val LOAN_PROJECTED = "loan_projected"
    const val LOAN_SHEET = "loan_sheet"
    const val LOAN_IRA = "loan_ira"
    const val LOAN_CURVE = "loan_curve"
    const val LOAN_END = "loan_end"
    const val LOAN_TYPE_PREFIX = "loan_type_"
    const val LOAN_PENALTY_MODE_PREFIX = "loan_penalty_mode_"
    const val LOAN_PAYMENT_FIELD = "loan_payment"
    const val LOAN_EARLY_ALLOWED = "loan_early_allowed"
    const val LOAN_STEP_ADD = "loan_step_add"
    const val LOAN_SAVE = "loan_save"
    const val LOAN_SAVE_ERROR = "loan_save_error"
    const val LOAN_SIMULATION_AMOUNT = "loan_simulation_amount"
    const val LOAN_SIMULATE = "loan_simulate"
    const val LOAN_SIMULATION = "loan_simulation"
    const val LOAN_SIMULATION_ERROR = "loan_simulation_error"

    fun loanType(key: String) = LOAN_TYPE_PREFIX + key
    fun loanPenaltyMode(key: String) = LOAN_PENALTY_MODE_PREFIX + key

    /**
     * « Placements » of Planification (MAG-555): the screen, a row, the empty state, « + »; the
     * projection card of the selected placement, its chart, its « Modifier »; the tablet's settings
     * panel, a frequency, the steppers; the full-screen form, its fields, what the server refused,
     * « Enregistrer » and « Supprimer ».
     */
    const val PLACEMENTS = "placements"
    const val PLACEMENT_ROW = "placement_row"
    const val PLACEMENT_EMPTY = "placement_empty"
    const val PLACEMENT_ADD = "placement_add"
    const val PLACEMENT_CARD = "placement_card"
    const val PLACEMENT_CHART = "placement_chart"
    const val PLACEMENT_EDIT = "placement_edit"
    const val PLACEMENT_CHART_TITLE = "placement_chart_title"
    const val PLACEMENT_GAP = "placement_gap"
    const val PLACEMENT_AFTER_WITHDRAWAL = "placement_after_withdrawal"
    const val PLACEMENT_WITHDRAWALS = "placement_withdrawals"
    const val PLACEMENT_SETTINGS = "placement_settings"
    const val PLACEMENT_FREQUENCY_PREFIX = "placement_frequency_"
    const val PLACEMENT_STEP_PREFIX = "placement_step_"
    const val PLACEMENT_FORM = "placement_form"
    const val PLACEMENT_NAME = "placement_name"
    const val PLACEMENT_INITIAL = "placement_initial"
    const val PLACEMENT_DEPOSIT = "placement_deposit"
    const val PLACEMENT_RATE = "placement_rate"
    const val PLACEMENT_YEARS = "placement_years"
    const val PLACEMENT_FORM_ERROR = "placement_form_error"
    const val PLACEMENT_SAVE = "placement_save"
    const val PLACEMENT_DELETE = "placement_delete"
    const val PLACEMENT_DELETE_CONFIRM = "placement_delete_confirm"

    fun placementFrequency(key: String) = PLACEMENT_FREQUENCY_PREFIX + key

    /** A stepper button of the tablet: `deposit_plus`, `rate_minus`, `years_plus`… */
    fun placementStep(field: String, plus: Boolean) = PLACEMENT_STEP_PREFIX + field + if (plus) "_plus" else "_minus"

    fun projectWellbeing(key: String) = PROJECT_WELLBEING_PREFIX + key
    fun projectDateMode(key: String) = PROJECT_DATE_MODE_PREFIX + key
    fun projectDependency(id: String) = PROJECT_DEPENDENCY_PREFIX + id
    fun projectOption(key: String) = PROJECT_OPTION_PREFIX + key
    fun projectOptionTotal(key: String) = PROJECT_OPTION_TOTAL_PREFIX + key
    fun projectOptionNet(key: String) = PROJECT_OPTION_NET_PREFIX + key
    fun projectOptionMonthly(key: String) = PROJECT_OPTION_MONTHLY_PREFIX + key
    fun projectOptionDelay(key: String) = PROJECT_OPTION_DELAY_PREFIX + key
    fun projectFundingAdd(kind: String) = PROJECT_FUNDING_ADD_PREFIX + kind
    fun projectFundingAccount(id: String) = PROJECT_FUNDING_ACCOUNT_PREFIX + id
    fun projectFundingPlacement(id: String) = PROJECT_FUNDING_PLACEMENT_PREFIX + id

    fun scenarioRow(id: String) = SCENARIO_ROW_PREFIX + id
    fun scenarioBadge(id: String) = SCENARIO_BADGE_PREFIX + id
    fun scenarioN(id: String) = SCENARIO_N_PREFIX + id
    fun scenarioGap(id: String) = SCENARIO_GAP_PREFIX + id
    fun scenarioMakeCurrent(id: String) = SCENARIO_MAKE_CURRENT_PREFIX + id
    fun scenarioDuplicate(id: String) = SCENARIO_DUPLICATE_PREFIX + id
    fun scenarioCompare(id: String) = SCENARIO_COMPARE_PREFIX + id
    fun scenarioEdit(id: String) = SCENARIO_EDIT_PREFIX + id
    fun scenarioDelete(id: String) = SCENARIO_DELETE_PREFIX + id
    fun scenarioDismiss(id: String) = SCENARIO_DISMISS_PREFIX + id
    fun scenarioProject(id: String) = SCENARIO_PROJECT_PREFIX + id
    fun scenarioProjectUp(id: String) = SCENARIO_PROJECT_UP_PREFIX + id
    fun scenarioProjectDown(id: String) = SCENARIO_PROJECT_DOWN_PREFIX + id
    fun scenarioPlacement(id: String) = SCENARIO_PLACEMENT_PREFIX + id
    fun scenarioRepaymentRow(id: String) = SCENARIO_REPAYMENT_ROW_PREFIX + id
    fun scenarioRepaymentFigures(id: String) = SCENARIO_REPAYMENT_FIGURES_PREFIX + id
    fun scenarioRepayment(key: Int, field: String) = "${SCENARIO_REPAYMENT_PREFIX}${key}_$field"
    /** `36` or `240`: the horizon in months. */
    fun timelineHorizon(months: Int) = TIMELINE_HORIZON_PREFIX + months

    /** A scenario id (`auto-rapid`…) or `none`. */
    fun timelineCompare(id: String?) = TIMELINE_COMPARE_PREFIX + (id ?: "none")

    fun timelineEvent(type: String, compared: Boolean = false) =
        (if (compared) TIMELINE_COMPARED_EVENT_PREFIX else TIMELINE_EVENT_PREFIX) + type

    fun reviewSegment(step: String) = REVIEW_ACTIVITY_SEGMENT_PREFIX + step
    fun periodPlanRow(categoryId: String) = PERIOD_PLAN_ROW_PREFIX + categoryId
    fun periodPlanAmount(categoryId: String) = PERIOD_PLAN_AMOUNT_PREFIX + categoryId

    const val TASK_NOTES = "task_notes"
    const val TASK_FORM_TITLE = "task_form_title"
    const val TASK_FORM_SUBMIT = "task_form_submit"
    const val TASK_FORM_DELETE = "task_form_delete"
    const val TASK_DETAIL_EDIT = "task_detail_edit"
    const val TASK_DETAIL_DONE = "task_detail_done"
    const val EVENT_DESCRIPTION = "event_description"

    /** Markdown fields (MAG-713): the bar above the keyboard, its buttons, the reading view's check boxes and « n/m » counter. */
    const val MARKDOWN_TOOLBAR = "markdown_toolbar"
    const val MARKDOWN_CHECK = "markdown_tool_check"
    const val MARKDOWN_BULLET = "markdown_tool_bullet"
    const val MARKDOWN_BOLD = "markdown_tool_bold"
    const val MARKDOWN_HEADING = "markdown_tool_heading"
    const val MARKDOWN_LINK = "markdown_tool_link"
    const val MARKDOWN_CLEAR = "markdown_tool_clear"
    const val MARKDOWN_COUNTER = "markdown_counter"
    const val MARKDOWN_CHECKBOX_PREFIX = "markdown_checkbox_"

    /** The reading view's check boxes, in order from 0. */
    fun markdownCheckbox(index: Int) = MARKDOWN_CHECKBOX_PREFIX + index

    /**
     * « Santé › Sport » (MAG-705): the screen, « + », the week card and its seven days, the list and a row, the empty
     * state; the detail (sheet on a phone, pane on a tablet) with its « Modifier » and « Supprimer »; the form's fields
     * and « Enregistrer ».
     */
    const val SPORT = "sport"
    const val SPORT_ADD = "sport_add"
    const val SPORT_WEEK = "sport_week"
    const val SPORT_WEEK_DAY_PREFIX = "sport_week_day_"
    const val SPORT_LIST = "sport_list"
    const val SPORT_EMPTY = "sport_empty"
    const val WORKOUT_ROW_PREFIX = "workout_row_"
    const val WORKOUT_DETAIL = "workout_detail"
    const val WORKOUT_EDIT = "workout_edit"
    const val WORKOUT_DELETE = "workout_delete"
    const val WORKOUT_DELETE_CONFIRM = "workout_delete_confirm"
    const val WORKOUT_FORM = "workout_form"
    const val WORKOUT_FORM_ERROR = "workout_form_error"
    const val WORKOUT_TYPE_PREFIX = "workout_type_"
    const val WORKOUT_FEELING_PREFIX = "workout_feeling_"
    const val WORKOUT_TITLE = "workout_title"
    const val WORKOUT_DATE = "workout_date"
    const val WORKOUT_TIME = "workout_time"
    const val WORKOUT_DURATION = "workout_duration"
    const val WORKOUT_DISTANCE = "workout_distance"
    const val WORKOUT_KCAL = "workout_kcal"
    const val WORKOUT_HEART_RATE = "workout_heart_rate"
    const val WORKOUT_NOTES = "workout_notes"
    const val WORKOUT_SAVE = "workout_save"

    fun sportWeekDay(dayOfMonth: Int) = SPORT_WEEK_DAY_PREFIX + dayOfMonth
    fun workoutRow(id: String) = WORKOUT_ROW_PREFIX + id
    fun workoutType(key: String) = WORKOUT_TYPE_PREFIX + key
    fun workoutFeeling(key: String) = WORKOUT_FEELING_PREFIX + key
}
