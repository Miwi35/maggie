<?php

declare(strict_types=1);

namespace Maggie\Health\Specification;

use Maggie\Calendar\Entity\Event;

/** A planned session's event moved or resized in the agenda (MAG-777): its session follows. */
final readonly class IsSportEventMoved
{
    /** @param array<string, array{0: mixed, 1: mixed}> $changeSet what Doctrine is about to write: field => [before, after] */
    public function __construct(
        private array $changeSet,
    ) {
    }

    public function isSatisfiedBy(Event $event): bool
    {
        return Event::ACTIVITY_SPORT === $event->getActivity()
            && (isset($this->changeSet['startAt']) || isset($this->changeSet['endAt']));
    }
}
