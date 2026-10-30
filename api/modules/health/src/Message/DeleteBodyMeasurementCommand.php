<?php

declare(strict_types=1);

namespace Maggie\Health\Message;

final readonly class DeleteBodyMeasurementCommand
{
    public function __construct(
        public string $userId,
        public string $measurementId,
    ) {
    }
}
