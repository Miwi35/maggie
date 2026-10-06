<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Agenda;

/**
 * One agenda the deduction considered, with what it scored and why (MAG-150).
 *
 * The reasons are not decoration: they are what the question Maggie asks is built from
 * when two agendas tie, and what she can say when one wins.
 */
final class AgendaCandidate
{
    /**
     * @param list<string> $reasons strongest signal first, each said once
     */
    public function __construct(
        public readonly Agenda $agenda,
        public readonly float $score,
        public readonly array $reasons,
    ) {
    }

    /** The agenda's name followed by the one or two reasons worth saying. */
    public function describe(): string
    {
        $reasons = array_slice($this->reasons, 0, 2);

        return sprintf(
            '%s (id %s)%s',
            $this->agenda->getName(),
            $this->agenda->getId(),
            [] === $reasons ? '' : ' — '.implode(', ', $reasons),
        );
    }
}
