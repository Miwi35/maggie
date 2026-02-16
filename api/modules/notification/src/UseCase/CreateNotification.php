<?php

declare(strict_types=1);

namespace Maggie\Notification\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Notification\Entity\Notification;

class CreateNotification
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Notification $notification): Notification
    {
        $this->em->persist($notification);
        $this->em->flush();

        return $notification;
    }
}
