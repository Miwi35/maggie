<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Event;
use Maggie\Core\Entity\User;
use Symfony\Component\String\UnicodeString;

/**
 * What the two event reads (`get_upcoming_events`, `get_events_by_date`) narrow their result
 * with: some of the user's agendas, and the title or place of the event looked for (MAG-325).
 */
class EventReadFilter
{
    private const MIN_WORD = 3;

    /** Words that say nothing about which event is meant. */
    private const FILLER = ['des', 'les', 'une', 'mon', 'mes', 'aux', 'pour', 'tous', 'tout', 'chez', 'avec', 'dans', 'sur', 'the'];

    public function __construct(
        private readonly AgendaResolver $agendaResolver,
    ) {
    }

    /**
     * The ids of the agendas to keep, or null for all of them.
     *
     * @param list<string>|null $references names or ids, as the user said them
     *
     * @return list<string>|null
     *
     * @throws \DomainException when a reference is none of the user's agendas
     */
    public function agendaIds(User $user, ?array $references): ?array
    {
        $references = array_values(array_filter(
            array_map(static fn ($reference) => trim((string) $reference), $references ?? []),
            static fn (string $reference) => '' !== $reference,
        ));
        if ([] === $references) {
            return null;
        }

        return array_map(
            static fn ($agenda) => (string) $agenda->getId(),
            $this->agendaResolver->resolveAll($user, $references),
        );
    }

    /**
     * @param Event[]           $events
     * @param list<string>|null $agendaIds
     *
     * @return Event[]
     */
    public function keep(array $events, ?array $agendaIds, ?string $query): array
    {
        $words = null === $query ? [] : self::words($query);

        return array_values(array_filter($events, static function (Event $event) use ($agendaIds, $words) {
            if (null !== $agendaIds && !\in_array((string) $event->getAgenda()->getId(), $agendaIds, true)) {
                return false;
            }

            return [] === $words || self::matches(trim($event->getSummary().' '.$event->getLocation()), $words);
        }));
    }

    /**
     * @throws \DomainException when a query was given but holds no word to look for: ignoring it
     *                          would hand back every event as if all of them matched
     */
    public function assertSearchable(?string $query): void
    {
        if (null !== $query && '' !== trim($query) && [] === self::words($query)) {
            throw new \DomainException(sprintf('The query "%s" holds no word to look for: give the distinctive word(s) of the title, at least %d letters.', trim($query), self::MIN_WORD));
        }
    }

    /**
     * Planned meals are not events here (MAG-323): a title search that found nothing says where else to look.
     *
     * @param list<array<string, mixed>> $found
     *
     * @return array{hint?: string}
     */
    public function mealsHint(bool $searching, array $found): array
    {
        if (!$searching || [] !== $found) {
            return [];
        }

        return ['hint' => 'No event has that title. Planned meals are not listed here: if it may be a meal (a dish, « le repas … »), search it with manage_meals, action list and query, before saying it does not exist.'];
    }

    public function hasQuery(?string $query): bool
    {
        return null !== $query && [] !== self::words($query);
    }

    /**
     * The title or the place holds a word of the query, give or take case, accents and a typo:
     * « Lubus », as a dictation heard it, finds « Black Wizards » at « L'UBU, Rennes ».
     *
     * @param list<string> $queryWords
     */
    private static function matches(string $text, array $queryWords): bool
    {
        $eventWords = self::words($text, keepFiller: true);

        foreach ($queryWords as $wanted) {
            foreach ($eventWords as $word) {
                if (self::sameWord($wanted, $word)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static function sameWord(string $wanted, string $word): bool
    {
        if (str_contains($word, $wanted) || str_contains($wanted, $word)) {
            return true;
        }

        $shortest = min(\strlen($wanted), \strlen($word));
        $tolerated = match (true) {
            $shortest >= 9 => 2,
            $shortest >= 5 => 1,
            default => 0,
        };

        return 0 !== $tolerated && levenshtein($wanted, $word) <= $tolerated;
    }

    /** @return list<string> */
    private static function words(string $text, bool $keepFiller = false): array
    {
        $folded = (new UnicodeString($text))->ascii()->lower()->toString();
        $words = preg_split('/[^a-z0-9]+/', $folded, -1, PREG_SPLIT_NO_EMPTY) ?: [];

        return array_values(array_filter(
            $words,
            static fn (string $word) => \strlen($word) >= self::MIN_WORD && ($keepFiller || !\in_array($word, self::FILLER, true)),
        ));
    }
}
