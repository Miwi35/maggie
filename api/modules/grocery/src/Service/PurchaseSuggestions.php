<?php

declare(strict_types=1);

namespace Maggie\Grocery\Service;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Entity\PurchasedItem;
use Maggie\Grocery\Entity\RecurringGroceryItem;
use Maggie\Grocery\Enum\Unit;
use Symfony\Component\Clock\ClockInterface;
use Symfony\Component\Uid\Ulid;

/**
 * « Tu en prends souvent » (MAG-495): the products the past errands say are
 * due again, computed on read — there is no table of suggestions.
 *
 * Left out: a product already on the list (ticked or not), one a recurring
 * errand already brings back, and one dismissed with « Pas besoin » until the
 * day its snooze runs out.
 *
 * @phpstan-type Suggestion array{product: array{id: string, name: string}, everyDays: int, lastBoughtOn: string, dueOn: string, usualQuantity: ?float, usualUnit: ?string}
 */
final readonly class PurchaseSuggestions
{
    /** The shopper's days, as the recurring errands count them. */
    private const ZONE = 'Europe/Paris';

    public function __construct(
        private EntityManagerInterface $em,
        private ClockInterface $clock,
    ) {
    }

    /** Today in Paris, as a calendar day at midnight UTC. */
    public function today(): \DateTimeImmutable
    {
        return self::day($this->clock->now());
    }

    /**
     * The products due today or earlier, the longest overdue first.
     *
     * @return list<Suggestion>
     */
    public function suggest(User $user): array
    {
        $today = $this->today();
        $excluded = array_flip($this->productIdsOnTheListOrRecurring($user));

        $rhythms = [];
        foreach ($this->purchasesByProduct($user) as $productId => $purchases) {
            if (isset($excluded[$productId])) {
                continue;
            }
            $rhythm = PurchaseRhythm::of($purchases);
            if (null !== $rhythm && $rhythm->isDueOn($today)) {
                $rhythms[$productId] = $rhythm;
            }
        }

        if ([] === $rhythms) {
            return [];
        }

        /** @var list<Product> $products */
        $products = $this->em->getRepository(Product::class)->findBy(['id' => array_keys($rhythms), 'user' => $user]);

        $suggestions = [];
        foreach ($products as $product) {
            $snoozedUntil = $product->getSuggestionSnoozedUntil();
            if (null !== $snoozedUntil && $snoozedUntil->format('Y-m-d') > $today->format('Y-m-d')) {
                continue;
            }
            $suggestions[] = self::toArray($product, $rhythms[(string) $product->getId()]);
        }

        usort($suggestions, static fn (array $a, array $b) => [$a['dueOn'], $a['product']['name']] <=> [$b['dueOn'], $b['product']['name']]);

        return $suggestions;
    }

    /** The product's rhythm, or null when it was bought fewer than three times. */
    public function rhythmOf(User $user, Product $product): ?PurchaseRhythm
    {
        $purchases = $this->purchasesByProduct($user, $product)[(string) $product->getId()] ?? [];

        return PurchaseRhythm::of($purchases);
    }

    /**
     * Every bought line tied to a product, by product id.
     *
     * @return array<string, list<array{day: \DateTimeImmutable, quantity: ?float, unit: ?Unit}>>
     */
    private function purchasesByProduct(User $user, ?Product $product = null): array
    {
        $qb = $this->em->createQueryBuilder()
            ->select('IDENTITY(pi.product) AS productId', 'e.endedAt', 'pi.quantity', 'pi.unit')
            ->from(PurchasedItem::class, 'pi')
            ->join('pi.errand', 'e')
            ->where('e.user = :user')
            ->andWhere('pi.product IS NOT NULL')
            ->setParameter('user', $user->getId(), 'ulid');

        if (null !== $product) {
            $qb->andWhere('pi.product = :product')->setParameter('product', $product->getId(), 'ulid');
        }

        /** @var list<array{productId: string, endedAt: \DateTimeImmutable, quantity: ?float, unit: Unit|string|null}> $rows */
        $rows = $qb->getQuery()->getResult();

        $byProduct = [];
        foreach ($rows as $row) {
            // The id comes back as the database spells it: read it as a ULID, as the entities write it.
            $id = (string) Ulid::fromString((string) $row['productId']);
            $byProduct[$id][] = [
                'day' => self::day($row['endedAt']),
                'quantity' => null !== $row['quantity'] ? (float) $row['quantity'] : null,
                'unit' => \is_string($row['unit']) ? Unit::tryFrom($row['unit']) : $row['unit'],
            ];
        }

        return $byProduct;
    }

    /** @return list<string> */
    private function productIdsOnTheListOrRecurring(User $user): array
    {
        $onTheList = $this->em->createQueryBuilder()
            ->select('IDENTITY(gi.product) AS productId')
            ->from(GroceryItem::class, 'gi')
            ->join('gi.groceryList', 'gl')
            ->where('gl.user = :user')
            ->andWhere('gi.product IS NOT NULL')
            ->setParameter('user', $user->getId(), 'ulid')
            ->getQuery()
            ->getSingleColumnResult();

        $recurring = $this->em->createQueryBuilder()
            ->select('IDENTITY(r.product) AS productId')
            ->from(RecurringGroceryItem::class, 'r')
            ->where('r.user = :user')
            ->andWhere('r.product IS NOT NULL')
            ->setParameter('user', $user->getId(), 'ulid')
            ->getQuery()
            ->getSingleColumnResult();

        return array_values(array_map(
            static fn (mixed $id): string => (string) Ulid::fromString((string) $id),
            [...$onTheList, ...$recurring],
        ));
    }

    /**
     * The calendar day an instant falls on in Paris, at midnight UTC: two such
     * days are a whole number of days apart, whatever the clocks did in between.
     */
    private static function day(\DateTimeInterface $instant): \DateTimeImmutable
    {
        $local = \DateTimeImmutable::createFromInterface($instant)->setTimezone(new \DateTimeZone(self::ZONE));

        return new \DateTimeImmutable($local->format('Y-m-d'), new \DateTimeZone('UTC'));
    }

    /** @return Suggestion */
    private static function toArray(Product $product, PurchaseRhythm $rhythm): array
    {
        return [
            'product' => ['id' => (string) $product->getId(), 'name' => $product->getName()],
            'everyDays' => $rhythm->everyDays,
            'lastBoughtOn' => $rhythm->lastBoughtOn->format('Y-m-d'),
            'dueOn' => $rhythm->dueOn()->format('Y-m-d'),
            'usualQuantity' => $rhythm->usualQuantity,
            'usualUnit' => $rhythm->usualUnit?->value,
        ];
    }
}
