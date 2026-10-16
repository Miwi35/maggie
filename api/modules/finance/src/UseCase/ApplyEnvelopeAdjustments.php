<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Enum\BudgetMode;
use Maggie\Finance\Message\CreateEnvelopeCommand;
use Maggie\Finance\Message\UpdateEnvelopeCommand;
use Maggie\Finance\Repository\EnvelopeRepository;
use Maggie\Finance\Service\OwnedReferenceResolver;
use Maggie\Finance\Service\SeasonalAmounts;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * Sets what the month after a reviewed month is budgeted at, envelope by
 * envelope. The envelope of that month is created, from the reviewed month's
 * (seasons and alert threshold carried over, as a rollover does), when it does
 * not exist yet.
 *
 * An adjustment is for the month alone or, when it names a season, for that
 * month of every year: the season goes onto the envelope, so the months after
 * it and the years to come are rolled over with it. Everything is checked
 * before the first write.
 */
class ApplyEnvelopeAdjustments
{
    public function __construct(
        private readonly EnvelopeRepository $envelopeRepository,
        private readonly OwnedReferenceResolver $references,
        private readonly GetEnvelopeAdjustments $getEnvelopeAdjustments,
        private readonly MessageBusInterface $bus,
    ) {
    }

    /**
     * @param list<array{categoryId: string, amountCents: int, seasonal?: bool}> $adjustments
     *
     * @return list<array{categoryId: string, envelopeId: string, amountCents: int, created: bool}>
     *
     * @throws \DomainException       when an adjustment is malformed or has no envelope in the reviewed month
     * @throws HandlerFailedException when a write is refused by its handler
     */
    public function execute(User $user, \DateTimeImmutable $month, array $adjustments): array
    {
        $month = $month->modify('first day of this month')->setTime(0, 0);
        $target = $month->modify('+1 month');
        $targetMonth = (int) $target->format('n');

        $plans = [];
        $seen = [];
        foreach ($adjustments as $adjustment) {
            $categoryId = $adjustment['categoryId'];
            $amountCents = $adjustment['amountCents'];
            if (isset($seen[$categoryId])) {
                throw new \DomainException("Category adjusted twice: {$categoryId}");
            }
            $seen[$categoryId] = true;
            if ($amountCents < 0) {
                throw new \DomainException('An envelope amount is a positive amount in cents.');
            }

            $category = $this->references->category($categoryId, $user);
            $source = $this->envelopeRepository->findOneForPeriod($category, BudgetMode::Monthly, (int) $month->format('Y'), (int) $month->format('n'))
                ?? throw new \DomainException("No envelope budgets {$category->getName()} in {$month->format('Y-m')}.");
            $existing = $this->envelopeRepository->findOneForPeriod($category, BudgetMode::Monthly, (int) $target->format('Y'), $targetMonth);

            $base = $existing ?? $source;
            $amount = $base->getAmountCents();
            $seasons = $base->getSeasonalAmounts();
            if ($adjustment['seasonal'] ?? false) {
                $seasons = self::withSeason($seasons, $targetMonth, $amountCents);
            } elseif (null !== ($covering = SeasonalAmounts::covering($seasons, $targetMonth))) {
                $seasons = array_map(
                    static fn (array $season): array => $season === $covering ? array_replace($season, ['amountCents' => $amountCents]) : $season,
                    $seasons,
                );
            } else {
                $amount = $amountCents;
            }

            $plans[] = [$source, $existing, $amount, $seasons];
        }

        $applied = [];
        foreach ($plans as [$source, $existing, $amount, $seasons]) {
            /* @var Envelope $source */
            /** @var Envelope|null $existing */
            if (null === $existing) {
                $stamped = $this->bus->dispatch(new CreateEnvelopeCommand(
                    userId: (string) $user->getId(),
                    categoryId: (string) $source->requireCategory()->getId(),
                    amountCents: $amount,
                    year: (int) $target->format('Y'),
                    mode: BudgetMode::Monthly->value,
                    month: $targetMonth,
                    currency: $source->getCurrency(),
                    alertThresholdPercent: $source->getAlertThresholdPercent(),
                    seasonalAmounts: $seasons,
                ));
            } else {
                $stamped = $this->bus->dispatch(new UpdateEnvelopeCommand(
                    userId: (string) $user->getId(),
                    envelopeId: (string) $existing->getId(),
                    amountCents: $amount,
                    seasonalAmounts: $seasons,
                ));
            }

            /** @var Envelope $envelope */
            $envelope = $stamped->last(HandledStamp::class)->getResult();
            $applied[] = [
                'categoryId' => (string) $envelope->requireCategory()->getId(),
                'envelopeId' => (string) $envelope->getId(),
                'amountCents' => $envelope->getMonthAmountCents(),
                'created' => null === $existing,
            ];
        }

        return $applied;
    }

    /**
     * Applies the suggestions the review offers, the given categories' or all of them.
     *
     * @param list<string>|null $categoryIds
     *
     * @return list<array{categoryId: string, envelopeId: string, amountCents: int, created: bool}>
     */
    public function applySuggestions(User $user, \DateTimeImmutable $month, ?array $categoryIds = null): array
    {
        $adjustments = [];
        foreach ($this->getEnvelopeAdjustments->execute($user, $month)['envelopes'] as $line) {
            $suggestion = $line['suggestion'];
            if (null === $suggestion || (null !== $categoryIds && !\in_array($line['categoryId'], $categoryIds, true))) {
                continue;
            }

            $adjustments[] = [
                'categoryId' => $line['categoryId'],
                'amountCents' => $suggestion['proposedCents'],
                'seasonal' => null !== $suggestion['seasonal'],
            ];
        }

        return $this->execute($user, $month, $adjustments);
    }

    /**
     * The seasons with `month` set to `amountCents`, alone: the season that
     * covered it is cut around it so its other months keep their amount.
     *
     * @param list<array{fromMonth: int, toMonth: int, amountCents: int}> $seasons
     *
     * @return list<array{fromMonth: int, toMonth: int, amountCents: int}>
     */
    private static function withSeason(array $seasons, int $month, int $amountCents): array
    {
        $result = [];
        foreach ($seasons as $season) {
            if (!\in_array($month, SeasonalAmounts::months($season), true)) {
                $result[] = $season;
                continue;
            }

            $months = SeasonalAmounts::months($season);
            $at = array_search($month, $months, true);
            foreach ([\array_slice($months, 0, $at), \array_slice($months, $at + 1)] as $rest) {
                if ([] !== $rest) {
                    $result[] = ['fromMonth' => $rest[0], 'toMonth' => $rest[\count($rest) - 1], 'amountCents' => $season['amountCents']];
                }
            }
        }
        $result[] = ['fromMonth' => $month, 'toMonth' => $month, 'amountCents' => $amountCents];

        return $result;
    }
}
