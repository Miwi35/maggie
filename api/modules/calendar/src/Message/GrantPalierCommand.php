<?php

declare(strict_types=1);

namespace Maggie\Calendar\Message;

/** Lays down the palier of a chapter finished (MAG-509); a subject already rewarded keeps its own. */
final readonly class GrantPalierCommand
{
    public function __construct(
        public string $userId,
        public string $subjectType,
        public string $subjectId,
        public string $title,
    ) {
    }
}
