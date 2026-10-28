<?php

declare(strict_types=1);

namespace Maggie\Core\MessageHandler;

use Maggie\Core\Entity\UserLocation;
use Maggie\Core\Message\ReportUserLocationCommand;
use Maggie\Core\Service\UserLocations;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
final class ReportUserLocationHandler
{
    public function __construct(
        private readonly UserLocations $locations,
    ) {
    }

    public function __invoke(ReportUserLocationCommand $command): void
    {
        $this->locations->save($command->userId, static fn (UserLocation $location) => $location->moveTo(
            $command->source,
            $command->latitude,
            $command->longitude,
            $command->accuracy,
            $command->capturedAt,
        ));
    }
}
