<?php

declare(strict_types=1);

namespace Maggie\Notification\MessageHandler;

use Maggie\Core\Repository\UserPreferenceRepository;
use Maggie\Core\Repository\UserRepository;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Enum\NotificationType;
use Maggie\Notification\Message\CreateNotificationCommand;
use Maggie\Notification\UseCase\CreateNotification;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateNotificationHandler
{
    public function __construct(
        private readonly CreateNotification $createNotification,
        private readonly UserRepository $userRepository,
        private readonly UserPreferenceRepository $preferenceRepository,
    ) {
    }

    public function __invoke(CreateNotificationCommand $command): ?Notification
    {
        if (null !== $command->userId) {
            $user = $this->userRepository->find($command->userId);
        } else {
            $users = $this->userRepository->findAll();
            $user = $users[0] ?? null;
        }

        if (null === $user) {
            throw new \DomainException('No user found.');
        }

        // On by default: a user who never saved preferences has no row.
        if (false === $this->preferenceRepository->findOneByUser($user)?->isNotificationsEnabled()) {
            return null;
        }

        $notification = new Notification();
        $notification->setUser($user);
        $notification->setType(NotificationType::from($command->type));
        $notification->setTitle($command->title);
        $notification->setBody($command->body);
        $notification->setRelatedEntityIri($command->relatedEntityIri);
        $notification->setOccurrenceStartAt($command->occurrenceStartAt);

        return $this->createNotification->execute($notification);
    }
}
