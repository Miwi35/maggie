<?php

declare(strict_types=1);

namespace Maggie\Health\Message;

use Maggie\Health\Enum\HealthDayAuthor;

/**
 * Write a health journal day's mood, fatigue or note (MAG-750). A null value
 * leaves the field as it is; a field named in `$cleared` is emptied.
 */
final readonly class UpdateHealthDayCommand
{
    /**
     * @param string       $date    the local day, YYYY-MM-DD
     * @param list<string> $cleared among mood, fatigue, note
     */
    public function __construct(
        public string $userId,
        public string $date,
        public HealthDayAuthor $author,
        public mixed $mood = null,
        public mixed $fatigue = null,
        public mixed $note = null,
        public array $cleared = [],
    ) {
    }
}
