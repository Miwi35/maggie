<?php

declare(strict_types=1);

namespace Maggie\Notification\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Notification\Entity\Notification;

class DeleteNotification
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Notification $notification): void
    {
        $this->em->remove($notification);
        $this->em->flush();
    }
}
