<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Core\Entity\User;
use Symfony\Component\String\UnicodeString;

/**
 * Works out which agenda an event belongs to, and says when it cannot (MAG-150).
 *
 * Before this, an event Maggie created went to the default agenda whatever it was about.
 * The signals here are the ones the owner listed: what he said about the agenda, the
 * agendas' own names and descriptions, and the events he already filed — « un concert »
 * belongs in « Concerts », and an appointment with Paul belongs wherever the last ones
 * went. When two agendas fit as well as each other, nothing is chosen: the caller asks.
 *
 * Only the caller's own agendas and events are ever read, so neither a choice nor the
 * question it raises can name another user's agenda.
 *
 * Three properties are worth keeping in mind when touching the weights below:
 *
 * - **What the user said is not one signal among others.** When they named an agenda, only
 *   that is read: it decides, or it raises the question. Letting the event's own words
 *   outvote them would be contradicting them, not helping.
 * - **Confidence is relative.** There is no "good enough" score, because any absolute
 *   threshold would have to be re-tuned every time a weight moves and it reads the wrong
 *   quantity: a doubt is two agendas competing, not one weak signal. A sole candidate
 *   wins; a candidate twice its runner-up wins; anything else is a question.
 * - **A word that leans on no agenda decides nothing, and is dropped.** Each history
 *   signal is weighted by this agenda's *share* of the events that match it, so five
 *   lunches with Paul in « Boulot » against one in « Perso » clears the dominance test
 *   while a word spread evenly points nowhere in particular. The share alone is not
 *   enough, though — split three ways it still clears the floor, and the answer would be a
 *   three-way question where the ticket asks for the default agenda — so a signal that
 *   reaches *every* one of the user's agendas and leans on none of them is dropped
 *   outright. Both are also why the history is counted per word rather than per event.
 */
class AgendaSuggester
{
    /** Below this, a signal is noise rather than a candidate. */
    private const FLOOR = 10.0;

    /** How far ahead of its runner-up a candidate has to be to be acted on alone. */
    private const DOMINANCE = 2.0;

    /** Asking about more than three agendas is not a question, it is a list. */
    private const MAX_CANDIDATES = 3;

    /** A one-off booked years ahead is not a habit — see EventRepository::findForAgendaDeduction(). */
    private const HISTORY_HORIZON = '+30 days';
    private const HISTORY_LIMIT = 500;

    private const SPOKEN_NAME = 200.0;
    private const SPOKEN_DESCRIPTION = 120.0;
    private const NAME_IN_REQUEST = 100.0;
    private const WHOLE_NAME_IN_REQUEST = 40.0;
    private const DESCRIPTION_IN_REQUEST = 30.0;
    private const HISTORY_SAME_SUMMARY = 60.0;
    private const HISTORY_WORD = 40.0;
    private const HISTORY_SAME_LOCATION = 25.0;

    /** Shortest word worth comparing: anything shorter carries no subject. */
    private const MIN_WORD = 3;

    /** Shortest spoken reference worth matching as a fragment of an agenda's name. */
    private const MIN_FRAGMENT = 4;

    /** From this length on, one trailing "s" is dropped so « Concerts » matches « concert ». */
    private const PLURAL_FROM = 6;

    /**
     * French function words. They are in every sentence and name no agenda.
     *
     * @var list<string>
     */
    private const STOPWORDS = [
        'avec', 'pour', 'chez', 'dans', 'sous', 'sans', 'vers', 'sur', 'par', 'entre',
        'les', 'des', 'une', 'aux', 'ces', 'cet', 'cette', 'pas',
        'mon', 'mes', 'ton', 'tes', 'son', 'ses', 'nos', 'vos', 'leur', 'leurs', 'notre', 'votre',
        'est', 'sont', 'etre', 'ete', 'avoir',
        'que', 'qui', 'quoi', 'dont', 'mais', 'donc', 'car', 'non', 'oui',
        'nous', 'vous', 'toi', 'moi', 'lui', 'elle', 'elles', 'eux', 'ils',
        'tout', 'tous', 'toute', 'plus', 'moins', 'tres', 'bien', 'puis', 'apres', 'avant',
        'encore', 'aussi',
    ];

    /**
     * Words naming the *kind* of entry rather than its subject.
     *
     * « Rendez-vous avec Paul » shares « rendez » with every appointment the user ever
     * had, so counting it would drown the one word that does point somewhere. A short
     * explicit list, not a frequency measure: on a few dozen events « rendez » in one of
     * them and « Paul » in two score alike, and the outcome would depend on how full the
     * agenda happens to be.
     *
     * @var list<string>
     */
    private const GENERIC_WORDS = [
        'agenda', 'calendrier', 'evenement',
        'rendez', 'rdv', 'reunion', 'point', 'appel', 'visio', 'truc', 'chose',
    ];

    public function __construct(
        private readonly AgendaRepository $agendaRepository,
        private readonly EventRepository $eventRepository,
    ) {
    }

    /**
     * @param string|null $spoken what the user said the agenda was, when it resolved to
     *                            none of theirs exactly. It does not join the other
     *                            signals, it replaces them: someone who says « dans mon
     *                            agenda Théâtre » has told us where the event goes, and
     *                            deducing « Concerts » from its title instead would be
     *                            contradicting them rather than helping. Reaching no
     *                            agenda at all is then a question, never the default one.
     */
    public function suggest(
        User $user,
        string $summary,
        ?string $description = null,
        ?string $location = null,
        ?string $spoken = null,
    ): AgendaChoice {
        // A module's own agenda is never a candidate for an ordinary event (MAG-324).
        $agendas = $this->agendaRepository->findForEventsByUser($user);
        if ([] === $agendas) {
            return AgendaChoice::ask();
        }

        /** @var array<string, float> $scores */
        $scores = [];
        /** @var array<string, list<array{float, string}>> $reasons score, then what to say about it */
        $reasons = [];
        foreach ($agendas as $agenda) {
            $scores[(string) $agenda->getId()] = 0.0;
            $reasons[(string) $agenda->getId()] = [];
        }

        if (self::said($spoken)) {
            $this->scoreWhatTheUserSaid($agendas, (string) $spoken, $scores, $reasons);

            return $this->decide($user, $agendas, $scores, $reasons, userSaidSomething: true);
        }

        $requestText = self::fold(implode(' ', array_filter([$summary, $description, $location], self::said(...))));
        $requestWords = self::words($requestText);

        foreach ($agendas as $agenda) {
            $id = (string) $agenda->getId();
            $name = self::fold($agenda->getName());

            $shared = array_intersect(self::words($name), $requestWords);
            if ([] !== $shared) {
                $points = self::NAME_IN_REQUEST;
                if ('' !== $name && str_contains($requestText, $name)) {
                    $points += self::WHOLE_NAME_IN_REQUEST;
                }
                $scores[$id] += $points;
                $reasons[$id][] = [$points, sprintf('the event is about "%s"', implode(' ', $shared))];
            }

            $described = array_intersect(self::words(self::fold($agenda->getDescription() ?? '')), $requestWords);
            if ([] !== $described) {
                $scores[$id] += self::DESCRIPTION_IN_REQUEST;
                $reasons[$id][] = [self::DESCRIPTION_IN_REQUEST, sprintf('its description mentions "%s"', implode(' ', $described))];
            }
        }

        $this->scoreHistory($user, $summary, $location, $requestWords, $scores, $reasons);

        return $this->decide($user, $agendas, $scores, $reasons, userSaidSomething: false);
    }

    /**
     * What the user said about the agenda, matched against the agendas' own names first and
     * their descriptions only if no name answered.
     *
     * Not both at once: an agenda merely *mentioning* the word would then compete with the
     * one actually called it, and 200 against 120 sits inside the dominance band — so
     * « dans mon agenda boulot », with a « Perso » described as « tout ce qui n'est pas le
     * boulot », would come back as a question instead of as « Boulot ».
     *
     * @param Agenda[]                                  $agendas
     * @param array<string, float>                      $scores
     * @param array<string, list<array{float, string}>> $reasons
     */
    private function scoreWhatTheUserSaid(array $agendas, string $spoken, array &$scores, array &$reasons): void
    {
        $spokenText = self::fold($spoken);
        $spokenWords = self::words($spokenText);

        $named = array_filter(
            $agendas,
            static fn (Agenda $a) => self::nameAnswersTo($spokenText, $spokenWords, self::fold($a->getName())),
        );

        if ([] !== $named) {
            foreach ($named as $agenda) {
                $scores[(string) $agenda->getId()] += self::SPOKEN_NAME;
                $reasons[(string) $agenda->getId()][] = [self::SPOKEN_NAME, sprintf('its name matches "%s"', trim($spoken))];
            }

            return;
        }

        foreach ($agendas as $agenda) {
            if ([] !== array_intersect($spokenWords, self::words(self::fold($agenda->getDescription() ?? '')))) {
                $scores[(string) $agenda->getId()] += self::SPOKEN_DESCRIPTION;
                $reasons[(string) $agenda->getId()][] = [self::SPOKEN_DESCRIPTION, sprintf('its description mentions "%s"', trim($spoken))];
            }
        }
    }

    /**
     * The habits the user's own events show, each weighted by this agenda's share of them.
     *
     * @param list<string>                              $requestWords
     * @param array<string, float>                      $scores
     * @param array<string, list<array{float, string}>> $reasons
     */
    private function scoreHistory(
        User $user,
        string $summary,
        ?string $location,
        array $requestWords,
        array &$scores,
        array &$reasons,
    ): void {
        $history = $this->eventRepository->findForAgendaDeduction(
            $user,
            (new \DateTimeImmutable('now'))->modify(self::HISTORY_HORIZON),
            self::HISTORY_LIMIT,
        );
        if ([] === $history) {
            return;
        }

        $wantedSummary = self::fold($summary);
        $wantedLocation = self::said($location) ? self::fold((string) $location) : '';

        /** @var array<string, int> $sameSummary agenda id => how many of its events carry the same title */
        $sameSummary = [];
        /** @var array<string, int> $sameLocation agenda id => how many of its events are at the same place */
        $sameLocation = [];
        /** @var array<array-key, array<string, int>> $byWord word => agenda id => how many of its events use it */
        $byWord = [];

        foreach ($history as $event) {
            // Plain calendar entries only. A planned meal is an Event too — `Meal extends
            // Event` — and the meal planner files it in a « Repas » agenda it creates for
            // itself, so a user with twenty planned lunches there would be told their
            // appointment might belong in the meal planner. Those dedicated agendas are
            // out of this ticket's scope by its own wording, and nobody chooses them per
            // event. These rows come from a root query, so none of them is a proxy.
            if (Event::class !== $event::class) {
                continue;
            }

            $id = (string) $event->getAgenda()->getId();
            // An agenda deleted between the two queries, or one the caller does not own:
            // scoring it would be scoring something the decision cannot return.
            if (!array_key_exists($id, $scores)) {
                continue;
            }

            if (self::fold($event->getSummary()) === $wantedSummary) {
                $sameSummary[$id] = ($sameSummary[$id] ?? 0) + 1;
            }
            if ('' !== $wantedLocation && self::fold($event->getLocation() ?? '') === $wantedLocation) {
                $sameLocation[$id] = ($sameLocation[$id] ?? 0) + 1;
            }
            foreach (array_intersect(self::words(self::fold($event->getSummary())), $requestWords) as $word) {
                $byWord[$word][$id] = ($byWord[$word][$id] ?? 0) + 1;
            }
        }

        $agendaCount = count($scores);
        self::share($sameSummary, $agendaCount, self::HISTORY_SAME_SUMMARY, 'a past event here has the same title', $scores, $reasons);
        self::share($sameLocation, $agendaCount, self::HISTORY_SAME_LOCATION, sprintf('past events here are at "%s"', $wantedLocation), $scores, $reasons);
        foreach ($byWord as $word => $counts) {
            self::share($counts, $agendaCount, self::HISTORY_WORD, sprintf('past events here mention "%s"', (string) $word), $scores, $reasons);
        }
    }

    /**
     * Spread one signal's weight over the agendas that match it, in proportion to how many
     * of the matching events each holds — unless it singles out none of them.
     *
     * @param array<string, int>                        $counts
     * @param array<string, float>                      $scores
     * @param array<string, list<array{float, string}>> $reasons
     */
    private static function share(array $counts, int $agendaCount, float $weight, string $reason, array &$scores, array &$reasons): void
    {
        $total = array_sum($counts);
        if (0 === $total || self::decidesNothing($counts, $agendaCount)) {
            return;
        }

        foreach ($counts as $id => $count) {
            $points = $weight * ($count / $total);
            $scores[$id] += $points;
            $reasons[$id][] = [$points, $reason];
        }
    }

    /**
     * A signal that reaches every one of the user's agendas and leans on none of them.
     *
     * Reaching them all is not enough on its own, and getting that wrong cost the second
     * acceptance criterion for anyone with exactly two agendas: there, « three past
     * appointments with Paul at work against one at home » also reaches both, and dropping
     * it put the event back in the default agenda — the behaviour this ticket removes. So
     * what is dropped is a signal spread with no clear lean, by the same factor
     * confidence is read with everywhere else.
     *
     * @param array<string, int> $counts
     */
    private static function decidesNothing(array $counts, int $agendaCount): bool
    {
        if (count($counts) < $agendaCount) {
            return false;
        }

        $spread = array_values($counts);
        rsort($spread);

        return $spread[0] < self::DOMINANCE * ($spread[1] ?? 0);
    }

    /**
     * @param Agenda[]                                  $agendas
     * @param array<string, float>                      $scores
     * @param array<string, list<array{float, string}>> $reasons
     * @param bool                                      $userSaidSomething whether the user named an agenda at all
     */
    private function decide(User $user, array $agendas, array $scores, array $reasons, bool $userSaidSomething): AgendaChoice
    {
        $candidates = [];
        foreach ($agendas as $agenda) {
            $id = (string) $agenda->getId();
            if ($scores[$id] >= self::FLOOR) {
                $candidates[] = new AgendaCandidate($agenda, $scores[$id], self::strongestFirst($reasons[$id]));
            }
        }
        usort($candidates, static fn (AgendaCandidate $a, AgendaCandidate $b) => $b->score <=> $a->score);

        if ([] === $candidates) {
            // The user naming an agenda that reaches none of theirs is not the same as the
            // user saying nothing: filing the event in the default agenda instead of asking
            // is exactly the behaviour this ticket exists to remove.
            $default = $userSaidSomething ? null : $this->agendaRepository->findDefault($user);

            return null !== $default ? AgendaChoice::fallback($default) : AgendaChoice::ask();
        }

        if (1 === count($candidates) || $candidates[0]->score >= self::DOMINANCE * $candidates[1]->score) {
            return AgendaChoice::deduced($candidates[0]);
        }

        // The same factor, read the other way round: the agendas worth asking about are the
        // ones the winner failed to dominate.
        $plausible = array_slice(
            array_values(array_filter($candidates, static fn (AgendaCandidate $c) => $c->score >= $candidates[0]->score / self::DOMINANCE)),
            0,
            self::MAX_CANDIDATES,
        );

        return AgendaChoice::ambiguous($plausible);
    }

    /**
     * The reasons an agenda was chosen, the strongest first and each said once.
     *
     * Ordered by what each signal scored rather than by the order they were read in: only
     * the first two are said, and they are what the user is asked to choose between.
     *
     * @param list<array{float, string}> $reasons
     *
     * @return list<string>
     */
    private static function strongestFirst(array $reasons): array
    {
        usort($reasons, static fn (array $a, array $b) => $b[0] <=> $a[0]);

        return array_values(array_unique(array_map(static fn (array $r) => $r[1], $reasons)));
    }

    /**
     * Does what the user said name this agenda? Either they share a word, or one is written
     * inside the other — which is how a shortened or mistyped « boulo » still reaches
     * « Boulot ».
     *
     * Containment is only ever tried against a *name*. Against a free-text description it
     * reaches inside unrelated words — « sport » is written in « Déplacements et
     * transports » — and the event would be filed somewhere the user never mentioned,
     * where MAG-230 used to answer that no agenda carries that name.
     *
     * @param list<string> $spokenWords
     */
    private static function nameAnswersTo(string $spoken, array $spokenWords, string $name): bool
    {
        if ('' === $spoken || '' === $name) {
            return false;
        }

        if ([] !== array_intersect($spokenWords, self::words($name))) {
            return true;
        }

        return mb_strlen($spoken) >= self::MIN_FRAGMENT
            && (str_contains($name, $spoken) || str_contains($spoken, $name));
    }

    /**
     * The words of a folded text worth comparing: long enough, carrying a subject, and
     * singular.
     *
     * @return list<string>
     */
    private static function words(string $folded): array
    {
        $words = [];
        foreach (preg_split('/[^a-z0-9]+/', $folded) ?: [] as $word) {
            if (mb_strlen($word) < self::MIN_WORD || self::isNoise($word)) {
                continue;
            }
            $words[] = self::singular($word);
        }

        return array_values(array_unique($words));
    }

    /**
     * A function word, or a word naming the kind of entry rather than its subject.
     *
     * Tried on the word and on it without a trailing "s", because the lists are written in
     * the singular and `singular()` leaves short words alone: « rdvs » and « trucs » would
     * otherwise slip through while « appels » and « réunions » are caught.
     */
    private static function isNoise(string $word): bool
    {
        $singular = str_ends_with($word, 's') ? mb_substr($word, 0, -1) : $word;

        foreach ([$word, $singular] as $form) {
            if (in_array($form, self::STOPWORDS, true) || in_array($form, self::GENERIC_WORDS, true)) {
                return true;
            }
        }

        return false;
    }

    /** One trailing "s" dropped from a long enough word, so « Concerts » and « concert » are one. */
    private static function singular(string $word): string
    {
        return mb_strlen($word) >= self::PLURAL_FROM && str_ends_with($word, 's')
            ? mb_substr($word, 0, -1)
            : $word;
    }

    private static function fold(string $value): string
    {
        return (new UnicodeString(trim($value)))->ascii()->lower()->collapseWhitespace()->toString();
    }

    private static function said(?string $value): bool
    {
        return null !== $value && '' !== trim($value);
    }
}
