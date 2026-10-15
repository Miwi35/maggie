<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Enum\BudgetMode;

/**
 * How the envelopes of a period nest, following their categories: the mother
 * of an envelope is the one of the closest ancestor category that has one.
 */
final class EnvelopeNesting
{
    /**
     * @param iterable<Envelope> $envelopes
     *
     * @return array<string, list<Envelope>>
     */
    public function byCategory(iterable $envelopes): array
    {
        $byCategory = [];
        foreach ($envelopes as $envelope) {
            $byCategory[(string) $envelope->requireCategory()->getId()][] = $envelope;
        }

        return $byCategory;
    }

    /**
     * The envelope of the closest ancestor category that has one, the one of
     * the same mode when the ancestor has several.
     *
     * @param array<string, list<Envelope>> $envelopesByCategory
     */
    public function motherOf(Envelope $envelope, array $envelopesByCategory): ?Envelope
    {
        for ($ancestor = $envelope->requireCategory()->getParent(); null !== $ancestor; $ancestor = $ancestor->getParent()) {
            $candidates = $envelopesByCategory[(string) $ancestor->getId()] ?? [];
            if ([] === $candidates) {
                continue;
            }

            foreach ($candidates as $candidate) {
                if ($candidate->getMode() === $envelope->getMode()) {
                    return $candidate;
                }
            }

            return $candidates[0];
        }

        return null;
    }

    /**
     * The envelopes whose mother is this one and that budget the same period:
     * the ones a split shares its amount between.
     *
     * @param list<Envelope> $envelopes the envelopes covering the period
     *
     * @return list<Envelope>
     */
    public function daughtersOf(Envelope $mother, array $envelopes): array
    {
        $envelopes = array_values(array_filter(
            $envelopes,
            fn (Envelope $envelope) => BudgetMode::OneOff !== $envelope->getMode(),
        ));
        $byCategory = $this->byCategory($envelopes);

        return array_values(array_filter(
            $envelopes,
            fn (Envelope $candidate) => $candidate->getMode() === $mother->getMode()
                && $candidate->getMonth() === $mother->getMonth()
                && $this->motherOf($candidate, $byCategory)?->getId()->equals($mother->getId()),
        ));
    }
}
