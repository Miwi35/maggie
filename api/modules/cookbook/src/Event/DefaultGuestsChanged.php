<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Event;

/**
 * The owner's default number of guests changed (MAG-482). The meals that follow
 * the default are now cooked for another number of people; `previousDefault` is
 * what they were cooked for until now.
 */
final readonly class DefaultGuestsChanged
{
    public function __construct(
        public string $userId,
        public int $previousDefault,
    ) {
    }
}
