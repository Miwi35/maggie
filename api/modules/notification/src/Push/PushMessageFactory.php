<?php

declare(strict_types=1);

namespace Maggie\Notification\Push;

use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Enum\NotificationType;
use Symfony\Component\DependencyInjection\Attribute\AutowireIterator;

/**
 * Turns a stored notification into the FCM message one device receives.
 *
 * Data alone, at high priority, for every notification (MAG-681). A `notification`
 * block is shown by Android itself while the app is closed, and Android Auto only
 * reads notifications the app builds. So the `data` block carries everything the
 * app needs to build it (`MaggieFcmService`): the notification id to dedupe against
 * Mercure, the type, title and text, the channel, the `maggie://` link with
 * its label, and the buttons' keys. In the foreground the app raises its
 * interruption instead (MAG-314).
 */
class PushMessageFactory
{
    /**
     * Android channels, one per kind of message (the app creates them on the device, with
     * Maggie's chime). The `_v2` ids are the second generation: a channel's sound is frozen
     * at creation, so the chime needed new ids (MAG-485).
     */
    public const CHANNEL_CHAT = 'chat_v2';
    public const CHANNEL_REMINDERS = 'reminders_v2';
    public const CHANNEL_APPROVALS = 'approvals_v2';
    public const CHANNEL_FINANCE = 'finance_v2';
    /** Time to leave (MAG-655): strong importance, for the app's hard interruption. */
    public const CHANNEL_DEPARTURE = 'departure';

    /** The Android tag of a cooking timer's end, followed by the timer's id (MAG-582). */
    public const COOKING_TIMER_TAG = 'cooking-timer';

    /** The `type` of a data-only message that takes a notification down (MAG-582). */
    public const WITHDRAW_TYPE = 'withdraw';

    /** The `type` of a data-only message that asks the phone for its position (MAG-650). */
    public const LOCATION_REQUEST_TYPE = 'location_request';

    /** A position asked for and not given by then is too old to plan a departure with. */
    private const LOCATION_REQUEST_TTL = '600s';

    /** FCM refuses a message over 4096 bytes: 400 + 1200 leaves room for the rest of the data. */
    private const MAX_TITLE_BYTES = 400;
    private const MAX_TEXT_BYTES = 1200;

    /** The finance screens the app opens from a link (mobile `DeepLinks`). */
    private const FINANCE_PATHS = ['', 'accounts', 'budgets', 'categories', 'rules', 'rule-suggestions', 'banks', 'cushion', 'loans', 'review'];

    /** The finance screens whose action says more than « Ouvrir les finances » (admin `notificationLink`, mobile `Interruptions`). */
    private const FINANCE_LABELS = [
        'banks' => 'Reconnecter la banque',
        'budgets' => 'Voir les enveloppes',
    ];

    /** API collection → app link host and the label of the action that opens it. */
    private const ENTITY_LINKS = [
        'events' => ['event', 'Voir l\'événement'],
        'tasks' => ['task', 'Voir la tâche'],
        'grocery_items' => ['grocery', 'Voir la liste de courses'],
        'recipes' => ['recipe', 'Voir la recette'],
    ];

    /** @param iterable<NavigationLinkProvider> $navigationLinks the modules whose entities carry their own link */
    public function __construct(
        // `maggie` in prod; the dev and e2e builds answer to their own scheme.
        private readonly string $deepLinkScheme = 'maggie',
        #[AutowireIterator(NavigationLinkProvider::TAG)]
        private readonly iterable $navigationLinks = [],
    ) {
    }

    /** @return array<string, mixed> the FCM v1 `message` object */
    public function build(Notification $notification, string $deviceToken): array
    {
        $id = (string) $notification->getId();
        $title = self::cut($notification->getTitle(), self::MAX_TITLE_BYTES);
        $text = $this->text($notification);
        $action = $this->action($notification->getRelatedEntityIri());

        // FCM wants every data value as a string: absent rather than null.
        $data = array_filter([
            'notificationId' => $id,
            'type' => $notification->getType()->value,
            'title' => $title,
            'body' => $text,
            'channel' => $this->channel($notification->getType()),
            'link' => $action['link'] ?? null,
            'actionLabel' => $action['label'] ?? null,
            // A task reminder's buttons (MAG-510), as ordered keys the phone labels; they answer the reminder.
            'reminderId' => $notification->getReminderId(),
            'taskId' => null === $notification->getReminderId() ? null : $this->taskId($notification->getRelatedEntityIri()),
            // A missing product's button (MAG-570): « Ajouter à la liste » answers this alert.
            'shortageId' => null === $notification->getReminderId() ? $this->shortageId($notification->getRelatedEntityIri()) : null,
            'actions' => [] === $notification->getActions() ? null : implode(',', $notification->getActions()),
            // A cooking timer's end (MAG-582): the app clears it by this id once the timer is seen elsewhere.
            'cookingTimerId' => $this->cookingTimerId($notification->getRelatedEntityIri()),
            // Past it the app says nothing (MAG-679): a phone that was offline gets the alert late.
            'expiresAt' => $notification->getExpiresAt()?->format('c'),
        ], static fn (?string $value) => null !== $value && '' !== $value);

        return [
            'token' => $deviceToken,
            'data' => $data,
            // Delivered at once, even to a phone in Doze.
            'android' => ['priority' => 'high'] + $this->ttl($notification),
        ];
    }

    /**
     * FCM keeps an undelivered message for four weeks by default: one that expires is dropped by FCM itself
     * once it is no longer worth saying (MAG-679).
     *
     * @return array{ttl?: string}
     */
    private function ttl(Notification $notification): array
    {
        $expiresAt = $notification->getExpiresAt();

        return null === $expiresAt ? [] : ['ttl' => max(0, $expiresAt->getTimestamp() - time()).'s'];
    }

    /**
     * The data-only message that takes a notification down from a device's tray
     * once it is seen elsewhere (MAG-582): Android hands it to the app even in
     * the background, which cancels the notification of that tag.
     *
     * @return array<string, mixed> the FCM v1 `message` object
     */
    public function buildWithdrawal(Notification $notification, string $deviceToken): array
    {
        return [
            'token' => $deviceToken,
            'data' => [
                'type' => self::WITHDRAW_TYPE,
                'notificationId' => (string) $notification->getId(),
                'tag' => $this->tag($notification),
            ],
            'android' => ['priority' => 'high'],
        ];
    }

    /**
     * The data-only message that asks a phone where it is (MAG-650): Android
     * hands it to the app even in the background, which answers with
     * `PUT /api/me/location`. Nothing is shown; a newer request replaces one
     * still waiting on the device.
     *
     * @return array<string, mixed> the FCM v1 `message` object
     */
    public function buildLocationRequest(string $deviceToken): array
    {
        return [
            'token' => $deviceToken,
            'data' => ['type' => self::LOCATION_REQUEST_TYPE],
            'android' => [
                'priority' => 'high',
                'ttl' => self::LOCATION_REQUEST_TTL,
                'collapse_key' => self::LOCATION_REQUEST_TYPE,
            ],
        ];
    }

    private function text(Notification $notification): ?string
    {
        $body = $notification->getBody();
        if (null !== $body) {
            $body = self::cut($body, self::MAX_TEXT_BYTES);
        }

        // A reminder stores how many minutes ahead it fires, not a sentence.
        if (NotificationType::Reminder === $notification->getType() && null !== $body && ctype_digit($body)) {
            return sprintf('Dans %d min', (int) $body);
        }

        return $body;
    }

    /** At most $maxBytes bytes of UTF-8, never splitting a character. */
    private static function cut(string $text, int $maxBytes): string
    {
        if (\strlen($text) <= $maxBytes) {
            return $text;
        }

        // `…` takes three bytes.
        return mb_strcut($text, 0, $maxBytes - 3, 'UTF-8').'…';
    }

    private function channel(NotificationType $type): string
    {
        return match ($type) {
            NotificationType::Reminder, NotificationType::TaskDue, NotificationType::TaskNudge, NotificationType::Routine, NotificationType::CookingTimer => self::CHANNEL_REMINDERS,
            NotificationType::Approval => self::CHANNEL_APPROVALS,
            NotificationType::ConsentExpiring, NotificationType::Finance => self::CHANNEL_FINANCE,
            NotificationType::Departure => self::CHANNEL_DEPARTURE,
            NotificationType::Proaction, NotificationType::Grocery => self::CHANNEL_CHAT,
        };
    }

    /**
     * The Android tag the app files the notification under: its id, and for a cooking
     * timer's end `cooking-timer-{timerId}` — one per timer (MAG-582), which the app
     * clears when the timer is seen on another device. Sent twice (a worker retry),
     * a notification replaces itself rather than stacking. Only the withdrawal names it.
     */
    private function tag(Notification $notification): string
    {
        $timerId = $this->cookingTimerId($notification->getRelatedEntityIri());

        return null === $timerId ? (string) $notification->getId() : self::COOKING_TIMER_TAG.'-'.$timerId;
    }

    private function cookingTimerId(?string $relatedEntityIri): ?string
    {
        $path = (string) parse_url((string) $relatedEntityIri, PHP_URL_PATH);

        return 1 === preg_match('#^/api/cooking/timers/([0-9A-Za-z-]{1,64})$#', $path, $m) ? $m[1] : null;
    }

    private function taskId(?string $relatedEntityIri): ?string
    {
        $path = (string) parse_url((string) $relatedEntityIri, PHP_URL_PATH);

        return 1 === preg_match('#^/api/tasks/([0-9A-Za-z-]{1,64})$#', $path, $m) ? $m[1] : null;
    }

    private function shortageId(?string $relatedEntityIri): ?string
    {
        $path = (string) parse_url((string) $relatedEntityIri, PHP_URL_PATH);

        return 1 === preg_match('#^/api/shortage-alerts/([0-9A-Za-z-]{1,64})$#', $path, $m) ? $m[1] : null;
    }

    /** @return array{link: string, label: string}|null */
    private function action(?string $relatedEntityIri): ?array
    {
        if (null === $relatedEntityIri) {
            return null;
        }

        $path = (string) parse_url($relatedEntityIri, PHP_URL_PATH);

        if (1 === preg_match('#^/api/([a-z_]+)/([0-9A-Za-z-]{1,64})$#', $path, $m) && isset(self::ENTITY_LINKS[$m[1]])) {
            [$host, $label] = self::ENTITY_LINKS[$m[1]];

            return ['link' => sprintf('%s://%s/%s', $this->deepLinkScheme, $host, $m[2]), 'label' => $label];
        }

        // Time to leave (MAG-655): « Y aller » opens the navigation URL kept on the trip.
        if (1 === preg_match('#^/api/trips/[0-9A-Za-z-]{1,64}$#', $path)) {
            foreach ($this->navigationLinks as $provider) {
                $url = $provider->navigationUrl($path);
                if (null !== $url) {
                    return ['link' => $url, 'label' => 'Y aller'];
                }
            }

            return null;
        }

        // The routines of the day (MAG-512): « Aujourd'hui » of the tasks.
        if (1 === preg_match('#^/tasks/?$#', $path)) {
            return ['link' => sprintf('%s://tasks', $this->deepLinkScheme), 'label' => 'Voir les routines'];
        }

        if (1 === preg_match('#^/finance(?:/([a-z-]+))?/?$#', $path, $m) && \in_array($m[1] ?? '', self::FINANCE_PATHS, true)) {
            $screen = $m[1] ?? '';

            return [
                'link' => rtrim(sprintf('%s://finance/%s', $this->deepLinkScheme, $screen), '/'),
                'label' => self::FINANCE_LABELS[$screen] ?? 'Ouvrir les finances',
            ];
        }

        return null;
    }
}
