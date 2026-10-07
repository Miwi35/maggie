<?php

declare(strict_types=1);

namespace Maggie\Notification\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Notification\Entity\DeviceToken;

class UnregisterDeviceToken
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(DeviceToken $deviceToken): void
    {
        $this->em->remove($deviceToken);
        $this->em->flush();
    }
}
