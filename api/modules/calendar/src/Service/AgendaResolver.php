<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Core\Entity\User;
use Symfony\Component\String\UnicodeString;

/**
 * Turns what a model passes as "the agenda" — an id, or the name the user
 * spoke — into one of the user's own agendas (MAG-230).
 *
 * Only the user's agendas are ever considered, so neither the match nor the
 * list in the error can reveal another user's.
 */
class AgendaResolver
{
    public function __construct(
        private readonly AgendaRepository $agendaRepository,
    ) {
    }

    /**
     * @throws \DomainException when the reference matches no agenda, or several
     */
    public function resolve(User $user, string $reference): Agenda
    {
        $agendas = $this->agendaRepository->findByUser($user);
        $reference = trim($reference);

        foreach ($agendas as $agenda) {
            if (0 === strcasecmp((string) $agenda->getId(), $reference)) {
                return $agenda;
            }
        }

        $wanted = self::fold($reference);
        $matches = '' === $wanted
            ? []
            : array_values(array_filter($agendas, static fn (Agenda $a) => self::fold($a->getName()) === $wanted));

        if (1 === count($matches)) {
            return $matches[0];
        }

        $problem = [] === $matches
            ? sprintf('No agenda has the id or name "%s".', $reference)
            : sprintf('Several agendas are named "%s": pass the id of the one meant.', $reference);

        throw new \DomainException(sprintf('%s %s', $problem, self::describe($agendas)));
    }

    /**
     * @param Agenda[] $agendas
     */
    private static function describe(array $agendas): string
    {
        if ([] === $agendas) {
            return 'The user has no agenda yet.';
        }

        return 'Available agendas: '.implode(', ', array_map(
            static fn (Agenda $a) => sprintf('%s (id %s)', $a->getName(), $a->getId()),
            $agendas,
        )).'. Retry with one of them, or ask the user which one they mean.';
    }

    private static function fold(string $value): string
    {
        return (new UnicodeString(trim($value)))->ascii()->lower()->collapseWhitespace()->toString();
    }
}
