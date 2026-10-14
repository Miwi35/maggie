<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Enum\BudgetMode;
use Maggie\Finance\Exception\EnvelopeSplitException;
use Maggie\Finance\Repository\EnvelopeRepository;
use Maggie\Finance\Service\EnvelopeNesting;

/**
 * Shares the amount of a mother envelope between its daughters — the envelopes
 * of the sub-categories directly below it, for the same period. What the
 * daughters do not hold stays free in the mother.
 *
 * The daughters not named keep their amount and still count: the whole is
 * checked against the mother, and refused as a whole.
 */
class SplitEnvelope
{
    public function __construct(
        private readonly EnvelopeRepository $envelopeRepository,
        private readonly EnvelopeNesting $nesting,
        private readonly EntityManagerInterface $em,
    ) {
    }

    /** @param array<string, int> $amountsByEnvelopeId */
    public function execute(Envelope $mother, array $amountsByEnvelopeId): void
    {
        $this->assertSplittable($mother);
        $daughters = $this->daughtersOf($mother);

        $byId = [];
        foreach ($daughters as $daughter) {
            $byId[(string) $daughter->getId()] = $daughter;
        }

        foreach ($amountsByEnvelopeId as $envelopeId => $amountCents) {
            if (!isset($byId[$envelopeId])) {
                throw new EnvelopeSplitException("Envelope {$envelopeId} is not a daughter of this envelope for the same period.");
            }
            if ($amountCents < 0) {
                throw new EnvelopeSplitException('An amount cannot be negative.');
            }
        }

        $allocated = 0;
        foreach ($byId as $envelopeId => $daughter) {
            $allocated += $amountsByEnvelopeId[$envelopeId] ?? $daughter->getAmountCents();
        }

        if ($allocated > $mother->getAmountCents()) {
            throw new EnvelopeSplitException(sprintf('The daughters would hold %d cents, more than the %d cents of the envelope.', $allocated, $mother->getAmountCents()));
        }

        foreach ($amountsByEnvelopeId as $envelopeId => $amountCents) {
            $byId[$envelopeId]->setAmountCents($amountCents);
        }

        $this->em->flush();
    }

    /** A one-off envelope has neither category nor period: nothing sits below it. */
    public function assertSplittable(Envelope $mother): void
    {
        if (BudgetMode::OneOff === $mother->getMode()) {
            throw new EnvelopeSplitException('A one-off envelope has no daughters to share its amount with.');
        }
    }

    /** @return list<Envelope> */
    public function daughtersOf(Envelope $mother): array
    {
        if (BudgetMode::OneOff === $mother->getMode() || null === $mother->getYear()) {
            return [];
        }

        return $this->nesting->daughtersOf($mother, $this->envelopeRepository->findCoveringPeriod(
            $mother->getUser(),
            $mother->getYear(),
            $mother->getMonth(),
        ));
    }
}
