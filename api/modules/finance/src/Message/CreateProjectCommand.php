<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

final readonly class CreateProjectCommand
{
    /** @param list<string> $dependsOn ids of the user's projects this one waits for */
    public function __construct(
        public string $userId,
        public string $name,
        public int $amountCents,
        public string $currency = 'EUR',
        public string $wellbeing = 'medium',
        public string $dateMode = 'none',
        public ?string $targetOn = null,
        public array $dependsOn = [],
        public ?string $note = null,
    ) {
    }
}
