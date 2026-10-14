<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Service;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\MealGroceryContribution;
use Maggie\Cookbook\Repository\MealGroceryContributionRepository;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Enum\GroceryItemSource;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Service\GroceryLineNeeds;
use Maggie\Grocery\Service\Needs;
use Maggie\Grocery\Service\Packaging;

/**
 * Catches the lists written before a product had one line (MAG-380): the open
 * lines of one product become one, and its totals are worked out again from
 * what the meals hold on it.
 *
 * A group of lines is touched when it has more than one open line, or when its
 * line never learned its needs. Its needs are the meals' contributions added
 * up, plus what the lines no meal holds (written by hand) say; a packaged
 * product then counts its packagings once on that total, plus the packagings
 * the hand-written lines held. A line ticked is in the basket and stays as it
 * is. Running it again finds nothing left to do.
 */
class GroceryLineConsolidator
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly MealGroceryContributionRepository $contributionRepository,
        private readonly Packaging $packaging,
        private readonly GroceryLineNeeds $lineNeeds,
    ) {
    }

    /**
     * What consolidating the list would do, without doing it.
     *
     * @return list<array{product: string, lines: int, before: string, after: string, group: list<GroceryItem>}>
     */
    public function plan(GroceryList $list): array
    {
        $groups = [];
        foreach ($this->em->getRepository(GroceryItem::class)->findBy(['groceryList' => $list], ['position' => 'ASC']) as $item) {
            if ($item->isChecked() || null === $item->getProduct()) {
                continue;
            }

            $groups[(string) $item->getProduct()->getId()][] = $item;
        }

        $plan = [];
        foreach ($groups as $group) {
            if (1 === count($group) && null !== $group[0]->getNeeded()) {
                continue;
            }

            $result = $this->resolve($group);

            // Nothing a recipe or a hand wrote down: nothing to learn, nothing to merge.
            if (1 === count($group) && [] === $result['needed'] && !$result['held']) {
                continue;
            }

            $plan[] = [
                'product' => $group[0]->getProduct()->getName(),
                'lines' => count($group),
                'before' => implode(' + ', array_map($this->describeLine(...), $group)),
                'after' => $this->describeResult($result),
                'group' => $group,
            ];
        }

        return $plan;
    }

    /**
     * Merges the lines and works the totals out again; returns what changed, as `plan()` described it.
     *
     * @return list<array{product: string, lines: int, before: string, after: string, group: list<GroceryItem>}>
     */
    public function apply(GroceryList $list): array
    {
        $plan = $this->plan($list);

        foreach ($plan as $entry) {
            $this->merge($entry['group']);
        }

        if ([] !== $plan) {
            $list->setUpdatedAt(new \DateTimeImmutable());
            $this->em->flush();
        }

        return $plan;
    }

    /** @param list<GroceryItem> $group */
    private function merge(array $group): void
    {
        $result = $this->resolve($group);
        $target = $group[0];

        $buyAfter = $target->getBuyAfter();
        $source = $target->getSource();
        $targetContributions = [];
        foreach ($this->contributionRepository->findByGroceryItem($target) as $contribution) {
            $targetContributions[(string) $contribution->getMeal()->getId().':'.($contribution->getUnit()->value ?? '')] = $contribution;
        }

        foreach (array_slice($group, 1) as $duplicate) {
            foreach ($this->contributionRepository->findByGroceryItem($duplicate) as $contribution) {
                $key = (string) $contribution->getMeal()->getId().':'.($contribution->getUnit()->value ?? '');

                if (isset($targetContributions[$key])) {
                    $targetContributions[$key]->addQuantity($contribution->getQuantity());
                    $this->em->remove($contribution);

                    continue;
                }

                $contribution->setGroceryItem($target);
                $targetContributions[$key] = $contribution;
            }

            // The earliest date wins, and none if any line is due now.
            $buyAfter = null === $buyAfter || null === $duplicate->getBuyAfter() ? null : min($buyAfter, $duplicate->getBuyAfter());
            if (GroceryItemSource::Recipe === $source) {
                $source = $duplicate->getSource();
            }

            $target->getGroceryList()->removeItem($duplicate);
            $this->em->remove($duplicate);
        }

        $target->setBuyAfter($buyAfter);
        $target->setSource($source);

        // The needs are the sum worked out above; the packagings follow from them.
        $target->setNeeded($result['needed']);
        $product = $target->getProduct();
        $packagingUnit = $product?->getPackagingUnit();
        if (null !== $product && null !== $packagingUnit) {
            $packs = ($this->packaging->packsFor($product, $result['needed']) ?? 0) + $result['manualPacks'];
            $target->setQuantity($packs > 0 ? (float) $packs : null);
            $target->setUnit($packagingUnit);
        } else {
            $this->lineNeeds->replace($target, [], $result['needed']);
        }
    }

    /**
     * @param list<GroceryItem> $group the open lines of one product
     *
     * @return array{needed: list<array{quantity: float, unit: ?string}>, manualPacks: int, held: bool}
     */
    private function resolve(array $group): array
    {
        $needed = [];
        $manualPacks = 0;
        $held = false;

        foreach ($group as $item) {
            $contributions = $this->contributionRepository->findByGroceryItem($item);

            if ([] !== $contributions) {
                $held = true;
                foreach ($contributions as $contribution) {
                    $needed = $this->add($needed, $contribution);
                }

                continue;
            }

            // Written by hand: a packaged line stands for its packagings, the others for their quantity.
            $product = $item->getProduct();
            if (null !== $product && null !== $product->getPackagingUnit()) {
                $manualPacks += (int) $this->packaging->inPackagings($product, $item->getQuantity(), $item->getUnit());

                continue;
            }

            foreach (Needs::of($item->getQuantity(), $item->getUnit()) as $need) {
                $needed = Needs::add($needed, $need['quantity'], null === $need['unit'] ? null : Unit::from($need['unit']));
            }
        }

        return ['needed' => $needed, 'manualPacks' => $manualPacks, 'held' => $held];
    }

    /**
     * @param list<array{quantity: float, unit: ?string}> $needed
     *
     * @return list<array{quantity: float, unit: ?string}>
     */
    private function add(array $needed, MealGroceryContribution $contribution): array
    {
        return Needs::add($needed, $contribution->getQuantity(), $contribution->getUnit());
    }

    private function describeLine(GroceryItem $item): string
    {
        return null === $item->getQuantity() ? 'no quantity' : sprintf('%s %s', $this->number($item->getQuantity()), $item->getUnit()->value ?? '');
    }

    /** @param array{needed: list<array{quantity: float, unit: ?string}>, manualPacks: int, held: bool} $result */
    private function describeResult(array $result): string
    {
        $needs = implode(' + ', array_map(
            fn (array $need) => trim(sprintf('%s %s', $this->number($need['quantity']), $need['unit'] ?? '')),
            $result['needed'],
        ));

        return '' === $needs ? 'no need' : $needs.(0 < $result['manualPacks'] ? sprintf(' (+%d added by hand)', $result['manualPacks']) : '');
    }

    private function number(float $value): string
    {
        return rtrim(rtrim(number_format($value, 2, '.', ''), '0'), '.');
    }
}
