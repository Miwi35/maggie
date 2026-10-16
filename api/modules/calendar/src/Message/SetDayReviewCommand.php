<?php

declare(strict_types=1);

namespace Maggie\Calendar\Message;

/** « Bonne nuit » (MAG-500): records what helped, the whole list, and closes the review. */
final readonly class SetDayReviewCommand
{
    /** @param list<mixed> $helped among DayReview::HELPED */
    public function __construct(
        public string $userId,
        public string $dayReviewId,
        public array $helped,
    ) {
    }
}
