<?php

declare(strict_types=1);

namespace Maggie\Proaction\Message;

final readonly class CreateProactionCommand
{
    public function __construct(
        public \DateTimeImmutable $scheduledAt,
        public string $prompt,
        public ?string $userId = null,
    ) {
    }
}
