<?php

declare(strict_types=1);

namespace Maggie\Notification\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Entity\NotificationType;
use Maggie\Notification\Message\CreateNotificationCommand;
use Maggie\Notification\UseCase\CreateNotification;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateNotificationHandler
{
    public function __construct(
        private readonly CreateNotification $createNotification,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(CreateNotificationCommand $command): Notification
    {
        if ($command->userId !== null) {
            $user = $this->userRepository->find($command->userId);
        } else {
            $users = $this->userRepository->findAll();
            $user = $users[0] ?? null;
        }

        if ($user === null) {
            throw new \DomainException('No user found.');
        }

        $notification = new Notification();
        $notification->setUser($user);
        $notification->setType(NotificationType::from($command->type));
        $notification->setTitle($command->title);
        $notification->setBody($command->body);
        $notification->setRelatedEntityIri($command->relatedEntityIri);

        return $this->createNotification->execute($notification);
    }
}
