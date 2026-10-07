<?php

declare(strict_types=1);

namespace Maggie\Notification\Push;

use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Enum\NotificationType;

/**
 * Turns a stored notification into the FCM message one device receives.
 *
 * Both blocks, never data alone (owner's rule, 7 Oct.): with a `notification`
 * block Android shows the push itself while the app is closed, killed or in
 * the background; in the foreground it shows nothing and hands the message to
 * `onMessageReceived`, where the app raises its interruption (MAG-314). The
 * `data` block carries what the app needs either way — the notification id to
 * dedupe against Mercure, the type, and the `maggie://` link with its label.
 */
class PushMessageFactory
{
    /** Android channels, one per kind of message (MAG-29 creates them on the device). */
    public const CHANNEL_CHAT = 'chat';
    public const CHANNEL_REMINDERS = 'reminders';
    public const CHANNEL_APPROVALS = 'approvals';
    public const CHANNEL_FINANCE = 'finance';

    /**
     * FCM refuses a message over 4096 bytes, and title and text travel twice
     * (both blocks): 2 × (400 + 1200) leaves room for the rest of the data.
     */
    private const MAX_TITLE_BYTES = 400;
    private const MAX_TEXT_BYTES = 1200;

    /** The finance screens the app opens from a link (mobile `DeepLinks`). */
    private const FINANCE_PATHS = ['', 'accounts', 'budgets', 'categories', 'rules', 'rule-suggestions', 'banks', 'cushion', 'loans', 'review'];

    /** API collection → app link host and the label of the action that opens it. */
    private const ENTITY_LINKS = [
        'events' => ['event', 'Voir l\'événement'],
        'tasks' => ['task', 'Voir la tâche'],
        'grocery_items' => ['grocery', 'Voir la liste de courses'],
        'recipes' => ['recipe', 'Voir la recette'],
    ];

    public function __construct(
        // `maggie` in prod; the dev and e2e builds answer to their own scheme.
        private readonly string $deepLinkScheme = 'maggie',
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
            'link' => $action['link'] ?? null,
            'actionLabel' => $action['label'] ?? null,
        ], static fn (?string $value) => null !== $value && '' !== $value);

        return [
            'token' => $deviceToken,
            'notification' => array_filter([
                'title' => $title,
                'body' => $text,
            ], static fn (?string $value) => null !== $value && '' !== $value),
            'data' => $data,
            'android' => [
                // Delivered at once, even to a phone in Doze.
                'priority' => 'high',
                'notification' => [
                    'channel_id' => $this->channel($notification->getType()),
                    // Sent twice (a worker retry), it replaces itself rather than stacking.
                    'tag' => $id,
                ],
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
            NotificationType::Reminder, NotificationType::TaskDue => self::CHANNEL_REMINDERS,
            NotificationType::Approval => self::CHANNEL_APPROVALS,
            NotificationType::ConsentExpiring, NotificationType::Finance => self::CHANNEL_FINANCE,
            NotificationType::Proaction, NotificationType::Grocery => self::CHANNEL_CHAT,
        };
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

        if (1 === preg_match('#^/finance(?:/([a-z-]+))?/?$#', $path, $m) && \in_array($m[1] ?? '', self::FINANCE_PATHS, true)) {
            $screen = $m[1] ?? '';

            return [
                'link' => rtrim(sprintf('%s://finance/%s', $this->deepLinkScheme, $screen), '/'),
                'label' => 'banks' === $screen ? 'Reconnecter la banque' : 'Ouvrir les finances',
            ];
        }

        return null;
    }
}
