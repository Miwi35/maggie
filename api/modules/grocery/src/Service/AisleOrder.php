<?php

declare(strict_types=1);

namespace Maggie\Grocery\Service;

use Maggie\Grocery\Entity\Aisle;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Exception\InvalidAisleException;
use Maggie\Grocery\Repository\AisleRepository;

/**
 * The rules every write of an aisle shares, for the API and the MCP tools
 * alike: a name that is not blank and that its store does not already have
 * (whatever the case), and positions that stay 1, 2, 3… along the way
 * through the store.
 */
class AisleOrder
{
    public function __construct(
        private readonly AisleRepository $aisleRepository,
    ) {
    }

    /** @throws InvalidAisleException */
    public function checkedName(Store $store, string $name, ?Aisle $renamed = null): string
    {
        $name = trim($name);
        if ('' === $name) {
            throw new InvalidAisleException('An aisle needs a name.');
        }

        $same = $this->aisleRepository->findOneByStoreAndName($store, $name);
        if (null !== $same && $same !== $renamed) {
            throw new InvalidAisleException(sprintf('The store "%s" already has an aisle named "%s".', $store->getName(), $same->getName()));
        }

        return $name;
    }

    /**
     * Puts the aisle at this place (1 = first), the others keeping their order
     * around it; null or out of range puts it last. Numbers them all 1…n.
     */
    public function place(Aisle $aisle, ?int $position): void
    {
        $others = array_values(array_filter(
            $this->aisleRepository->findByStore($aisle->getStore()),
            fn (Aisle $other) => $other !== $aisle,
        ));

        $index = null === $position || $position < 1 || $position > \count($others) ? \count($others) : $position - 1;
        array_splice($others, $index, 0, [$aisle]);

        $this->number($others);
    }

    /**
     * The store's aisles in exactly this order: every one of them, once.
     *
     * @param list<string> $aisleIds
     *
     * @return Aisle[]
     *
     * @throws InvalidAisleException
     */
    public function reorder(Store $store, array $aisleIds): array
    {
        $byId = [];
        foreach ($this->aisleRepository->findByStore($store) as $aisle) {
            $byId[(string) $aisle->getId()] = $aisle;
        }

        $ordered = [];
        foreach ($aisleIds as $aisleId) {
            $aisle = $byId[$aisleId] ?? throw new InvalidAisleException(sprintf('Aisle %s is not an aisle of the store "%s".', $aisleId, $store->getName()));
            $ordered[$aisleId] = $aisle;
        }
        if (\count($ordered) !== \count($byId)) {
            throw new InvalidAisleException(sprintf('Give every aisle of the store "%s" once: %d expected, %d given.', $store->getName(), \count($byId), \count($ordered)));
        }

        $ordered = array_values($ordered);
        $this->number($ordered);

        return $ordered;
    }

    /** Closes the gap an aisle leaves. */
    public function closeGaps(Store $store, ?Aisle $leaving = null): void
    {
        $this->number(array_values(array_filter(
            $this->aisleRepository->findByStore($store),
            fn (Aisle $other) => $other !== $leaving,
        )));
    }

    /** @param list<Aisle> $aisles */
    private function number(array $aisles): void
    {
        foreach ($aisles as $index => $aisle) {
            if ($aisle->getPosition() !== $index + 1) {
                $aisle->setPosition($index + 1);
            }
        }
    }
}
