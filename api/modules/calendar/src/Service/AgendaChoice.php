<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Agenda;

/**
 * What the deduction concluded about an event's agenda (MAG-150): one agenda to file it
 * in, or a question to ask.
 */
final class AgendaChoice
{
    /**
     * @param list<AgendaCandidate> $candidates the plausible agendas, best first — only
     *                                          filled in for an ambiguous choice
     */
    private function __construct(
        public readonly AgendaChoiceKind $kind,
        public readonly ?Agenda $agenda,
        public readonly array $candidates = [],
        public readonly ?string $reason = null,
    ) {
    }

    public static function named(Agenda $agenda): self
    {
        return new self(AgendaChoiceKind::Named, $agenda, [], 'the user named it');
    }

    public static function deduced(AgendaCandidate $candidate): self
    {
        return new self(
            AgendaChoiceKind::Deduced,
            $candidate->agenda,
            [$candidate],
            implode(', ', array_slice($candidate->reasons, 0, 2)),
        );
    }

    public static function fallback(Agenda $agenda): self
    {
        return new self(AgendaChoiceKind::Fallback, $agenda, [], 'nothing pointed at an agenda, so the default one took it');
    }

    /**
     * @param list<AgendaCandidate> $candidates
     */
    public static function ambiguous(array $candidates): self
    {
        return new self(AgendaChoiceKind::Ambiguous, null, $candidates);
    }

    public static function ask(): self
    {
        return new self(AgendaChoiceKind::Ask, null);
    }
}
