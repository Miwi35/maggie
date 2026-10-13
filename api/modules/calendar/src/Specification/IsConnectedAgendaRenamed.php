<?php

declare(strict_types=1);

namespace Maggie\Calendar\Specification;

use Maggie\Calendar\Entity\Agenda;

/** A user renamed an agenda connected to Google: a name only Maggie knows, which Google must be told. */
final readonly class IsConnectedAgendaRenamed
{
    /** @param array<string, array{0: mixed, 1: mixed}> $changeSet what Doctrine is about to write: field => [before, after] */
    public function __construct(
        private array $changeSet,
    ) {
    }

    public function isSatisfiedBy(Agenda $agenda): bool
    {
        if (!isset($this->changeSet['name'])) {
            return false;
        }

        // A creation has no previous name: an agenda created from a Google calendar already carries Google's.
        [$before, $after] = $this->changeSet['name'];
        if (null === $before || $before === $after) {
            return false;
        }

        return $agenda->isGoogleSynced() && !$agenda->isNameFromGoogle();
    }
}
