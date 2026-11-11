<?php

declare(strict_types=1);

namespace Maggie\Health\Message;

final readonly class ChooseWorkoutMakeupCommand
{
    public function __construct(
        public string $userId,
        /** Its id or its IRI. */
        public string $makeupId,
        /** softer, shorter or tomorrow. */
        public string $option,
    ) {
    }
}
