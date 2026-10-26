<?php

declare(strict_types=1);

namespace Maggie\Core\MessageHandler;

use Maggie\Core\Entity\UserLocation;
use Maggie\Core\Message\ParkCarCommand;
use Maggie\Core\Service\UserLocations;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
final class ParkCarHandler
{
    public function __construct(
        private readonly UserLocations $locations,
    ) {
    }

    public function __invoke(ParkCarCommand $command): void
    {
        $this->locations->save($command->userId, static fn (UserLocation $location) => $location->parkCar(
            $command->latitude,
            $command->longitude,
            $command->parkedAt,
        ));
    }
}
