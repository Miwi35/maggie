<?php

declare(strict_types=1);

namespace Maggie\Notification\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Message\DeleteNotificationCommand;
use Maggie\Notification\Message\MarkNotificationReadCommand;
use Maggie\Notification\Repository\NotificationRepository;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

#[McpTool(name: 'manage_notifications', description: 'List, mark as read, or delete notifications. Notifications are raised by Maggie itself (event reminders for now); use unreadOnly to see what still needs attention.')]
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
    ): string {
        try {
            return match ($action) {
                'list' => $this->list($unreadOnly, $limit),
                'mark_read' => $this->markRead($notificationId),
                'delete' => $this->delete($notificationId),
                default => json_encode(['error' => "Unknown action: {$action}. Use list, mark_read, or delete."], JSON_THROW_ON_ERROR),
            };
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
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
