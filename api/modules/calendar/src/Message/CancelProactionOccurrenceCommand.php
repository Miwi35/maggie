<?php

declare(strict_types=1);

namespace Maggie\Calendar\Message;

final readonly class CancelProactionOccurrenceCommand
{
    public function __construct(
        public string $userId,
        public string $seriesId,
        public \DateTimeImmutable $occurrence,
    ) {
    }
}
