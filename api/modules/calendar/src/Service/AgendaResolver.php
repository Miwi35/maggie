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
 *
 * Two outcomes are deliberately told apart (MAG-150): a reference matching
 * *nothing* leaves room for the deduction to read it as a hint, while a
 * reference matching *several* agendas is a real ambiguity only the user can
 * settle — no amount of deduction may pick one of two agendas that carry the
 * same name.
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
        return $this->findExact($user, $reference)
            ?? throw $this->unknownReference($user, $reference);
    }

    /**
     * The one agenda this reference names exactly, or null when it names none.
     *
     * @throws \DomainException when several of the user's agendas carry that name
     */
    public function findExact(User $user, string $reference): ?Agenda
    {
        $agendas = $this->agendaRepository->findForEventsByUser($user);
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

        if ([] !== $matches) {
            throw new \DomainException(sprintf('Several agendas are named "%s": pass the id of the one meant. %s', $reference, self::describe($agendas)));
        }

        return null;
    }

    /**
     * The canonical answer to a reference that names none of the user's agendas: say so,
     * list them, and ask. Returned rather than thrown so a caller that tried the deduction
     * first can decide when it is the right answer (MAG-150).
     */
    public function unknownReference(User $user, string $reference): \DomainException
    {
        return new \DomainException(sprintf(
            'No agenda has the id or name "%s". %s',
            trim($reference),
            $this->describeFor($user),
        ));
    }

    /**
     * The caller's agendas, named with their ids — the sentence every "which agenda?"
     * answer ends on, wherever the question comes from.
     */
    public function describeFor(User $user): string
    {
        return self::describe($this->agendaRepository->findForEventsByUser($user));
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
