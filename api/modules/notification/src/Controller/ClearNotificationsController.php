<?php

declare(strict_types=1);

namespace Maggie\Notification\Controller;

use Maggie\Core\Entity\User;
use Maggie\Notification\Message\DeleteNotificationCommand;
use Maggie\Notification\Repository\NotificationRepository;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Routing\Attribute\Route;

/**
 * Empties the bell: deletes every notification of the signed-in user (MAG-362).
 *
 * Hand-written rather than an API Platform operation, which cannot be declared
 * on a collection URI without an identifier. Each notification goes through the
 * same command as `DELETE /api/notifications/{id}`, so Mercure publishes the
 * usual `deleted: true` and the index drops the document, with no new format.
 */
final class ClearNotificationsController
{
    public function __construct(
        private readonly Security $security,
        private readonly NotificationRepository $notifications,
        private readonly MessageBusInterface $bus,
    ) {
    }

    #[Route('/api/notifications', name: 'api_notifications_clear', methods: ['DELETE'])]
    public function __invoke(): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $deleted = 0;
        foreach ($this->notifications->findIdsByUser($user) as $id) {
            try {
                $this->bus->dispatch(new DeleteNotificationCommand(notificationId: $id));
                ++$deleted;
            } catch (HandlerFailedException $e) {
                // Deleted by another client since we listed it: it is gone, which is what was asked.
                if (!$e->getPrevious() instanceof \DomainException) {
                    throw $e;
                }
            }
        }

        return new JsonResponse(['deleted' => $deleted]);
    }
}
