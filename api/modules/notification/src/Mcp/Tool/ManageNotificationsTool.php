<?php

declare(strict_types=1);

namespace Maggie\Notification\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Enum\NotificationType;
use Maggie\Notification\Message\CreateNotificationCommand;
use Maggie\Notification\Message\DeleteNotificationCommand;
use Maggie\Notification\Message\MarkNotificationReadCommand;
use Maggie\Notification\Repository\NotificationRepository;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'manage_notifications', description: 'Create, list, mark as read, or delete notifications. Use create to reach the user on their own devices (push notification): a proaction you took or an approval you are waiting for — type is one of proaction, approval, reminder, task_due, grocery, finance; give a short title, an optional body, and relatedEntityIri (e.g. /api/events/{id}, /api/tasks/{id}, /finance/banks) so tapping it opens the right screen. Use unreadOnly to see what still needs attention.')]
class ManageNotificationsTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly NotificationRepository $notificationRepository,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(
        string $action,
        ?string $notificationId = null,
        bool $unreadOnly = false,
        int $limit = 50,
        ?string $type = null,
        ?string $title = null,
        ?string $body = null,
        ?string $relatedEntityIri = null,
    ): string {
        try {
            return match ($action) {
                'create' => $this->create($type, $title, $body, $relatedEntityIri),
                'list' => $this->list($unreadOnly, $limit),
                'mark_read' => $this->markRead($notificationId),
                'delete' => $this->delete($notificationId),
                default => json_encode(['error' => "Unknown action: {$action}. Use create, list, mark_read, or delete."], JSON_THROW_ON_ERROR),
            };
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }

    private function create(?string $type, ?string $title, ?string $body, ?string $relatedEntityIri): string
    {
        $user = $this->userContext->requireUser();

        // consent_expiring is raised by the bank cron, which knows when a consent ends.
        $allowed = array_values(array_filter(
            array_map(static fn (NotificationType $t) => $t->value, NotificationType::cases()),
            static fn (string $value) => NotificationType::ConsentExpiring->value !== $value,
        ));
        if (null === $type || !\in_array($type, $allowed, true)) {
            return json_encode(['error' => 'type is required for create, one of: '.implode(', ', $allowed).'.'], JSON_THROW_ON_ERROR);
        }

        $title = null === $title ? '' : trim($title);
        if ('' === $title || mb_strlen($title) > 255) {
            return json_encode(['error' => 'title is required for create (255 characters at most).'], JSON_THROW_ON_ERROR);
        }

        if (null !== $relatedEntityIri && mb_strlen($relatedEntityIri) > 500) {
            return json_encode(['error' => 'relatedEntityIri is 500 characters at most.'], JSON_THROW_ON_ERROR);
        }

        $envelope = $this->bus->dispatch(new CreateNotificationCommand(
            type: $type,
            title: $title,
            body: null === $body || '' === trim($body) ? null : trim($body),
            relatedEntityIri: $relatedEntityIri,
            userId: (string) $user->getId(),
        ));

        $notification = $envelope->last(HandledStamp::class)?->getResult();

        // The user turned notifications off: nothing is stored, nothing is pushed.
        if (!$notification instanceof Notification) {
            return json_encode(['success' => true, 'created' => false, 'reason' => 'The user turned notifications off.'], JSON_THROW_ON_ERROR);
        }

        return json_encode(['success' => true, 'created' => true, 'notification' => $this->serialize($notification)], JSON_THROW_ON_ERROR);
    }

    private function list(bool $unreadOnly, int $limit): string
    {
        $user = $this->userContext->requireUser();

        $notifications = $unreadOnly
            ? $this->notificationRepository->findUnreadByUser($user)
            : $this->notificationRepository->findByUser($user, $limit);

        return json_encode([
            'notifications' => array_map(fn (Notification $n) => $this->serialize($n), $notifications),
            'count' => count($notifications),
        ], JSON_THROW_ON_ERROR);
    }

    private function markRead(?string $notificationId): string
    {
        if (null === $notificationId) {
            return json_encode(['error' => 'notificationId is required for mark_read.'], JSON_THROW_ON_ERROR);
        }

        $this->bus->dispatch(new MarkNotificationReadCommand(notificationId: $notificationId));

        return json_encode(['success' => true], JSON_THROW_ON_ERROR);
    }

    private function delete(?string $notificationId): string
    {
        if (null === $notificationId) {
            return json_encode(['error' => 'notificationId is required for delete.'], JSON_THROW_ON_ERROR);
        }

        $this->bus->dispatch(new DeleteNotificationCommand(notificationId: $notificationId));

        return json_encode(['success' => true], JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function serialize(Notification $notification): array
    {
        return [
            'id' => (string) $notification->getId(),
            'type' => $notification->getType()->value,
            'title' => $notification->getTitle(),
            'body' => $notification->getBody(),
            'relatedEntityIri' => $notification->getRelatedEntityIri(),
            'readAt' => $notification->getReadAt()?->format('c'),
            'createdAt' => $notification->getCreatedAt()->format('c'),
        ];
    }
}
