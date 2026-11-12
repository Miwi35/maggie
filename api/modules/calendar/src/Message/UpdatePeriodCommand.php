<?php

declare(strict_types=1);

namespace Maggie\Calendar\Message;

use Maggie\Core\Message\ClearsFieldsTrait;

final readonly class UpdatePeriodCommand
{
    use ClearsFieldsTrait;

    /**
     * Null leaves a field as it is. The schedule travels whole: `allDay` set means
     * the period becomes timed (`startAt`, `endAt`) or all-day (`startDate`,
     * `endDate`, excluded), never half of each.
     *
     * @param list<'rrule'|'holiday'> $clearFields
     */
    public function __construct(
        public string $userId,
        public string $periodId,
        public ?string $name = null,
        public ?string $agendaId = null,
        public ?bool $allDay = null,
        public ?\DateTimeImmutable $startAt = null,
        public ?\DateTimeImmutable $endAt = null,
        public ?\DateTimeImmutable $startDate = null,
        public ?\DateTimeImmutable $endDate = null,
        public ?string $timeZone = null,
        public ?string $rrule = null,
        public ?string $holiday = null,
        public array $clearFields = [],
    ) {
    }
}
