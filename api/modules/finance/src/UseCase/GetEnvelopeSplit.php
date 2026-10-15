<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Finance\Entity\Envelope;

/** How a mother envelope is shared between its daughters right now, what is left free in it. */
class GetEnvelopeSplit
{
    public function __construct(
        private readonly SplitEnvelope $splitEnvelope,
    ) {
    }

    /** @return array<string, mixed> */
    public function execute(Envelope $mother): array
    {
        $children = [];
        $allocated = 0;

        foreach ($this->splitEnvelope->daughtersOf($mother) as $daughter) {
            $allocated += $daughter->getAmountCents();
            $children[] = [
                'envelopeId' => (string) $daughter->getId(),
                'categoryId' => (string) $daughter->requireCategory()->getId(),
                'categoryName' => $daughter->requireCategory()->getName(),
                'categoryPath' => $daughter->requireCategory()->getPath(),
                'amountCents' => $daughter->getAmountCents(),
            ];
        }

        return [
            'envelopeId' => (string) $mother->getId(),
            'categoryId' => null === $mother->getCategory() ? null : (string) $mother->getCategory()->getId(),
            'categoryName' => $mother->getCategory()?->getName(),
            'currency' => $mother->getCurrency(),
            'amountCents' => $mother->getAmountCents(),
            'allocatedToChildrenCents' => $allocated,
            'freeCents' => $mother->getAmountCents() - $allocated,
            'children' => $children,
        ];
    }
}
