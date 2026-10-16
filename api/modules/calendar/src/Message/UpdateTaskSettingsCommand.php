<?php

declare(strict_types=1);

namespace Maggie\Calendar\Message;

/** Changes the task settings sent; a field left null keeps its value. */
final readonly class UpdateTaskSettingsCommand
{
    /**
     * @param array<string, mixed>|null $orderWeights  the weights to change, merged over the current ones
     * @param mixed                     $alertSchedule the rules to change, merged over the current ones; null keeps them
     */
    public function __construct(
        public string $userId,
        public string $taskSettingsId,
        public ?array $orderWeights = null,
        public mixed $alertSchedule = null,
        public mixed $morningTime = null,
        public mixed $eveningTime = null,
    ) {
    }
}
