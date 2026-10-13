<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * A line of a packaged product is a number of packagings (MAG-299, the owner's
 * refused recette of MAG-291): « 2 paquets », never « 300 g » of a 500 g pack.
 *
 * The code now writes only counts in the packaging; this converts what the
 * list and the meals already held, for every product that has a packaging:
 *
 * - a line (and a meal's share of it) in another unit, or with no unit, becomes
 *   whole packagings, rounded up — 300 g of a 500 g pack is 1, 1 kg is 2; a
 *   quantity that cannot be turned into packagings (a jar of unknown content in
 *   grams) is 1, and a line with no quantity is 1;
 * - a line never ends below what the meals hold on it;
 * - the open lines of one product become one: the quantities add up, the meals'
 *   shares move onto it, the earliest `buy_after` wins (none if any is due now).
 *   Ticked lines are in the basket and stay where they are.
 *
 * Data only — the schema is untouched. The statements are computed from what
 * the database holds and queued, so a dry run (`doctrine:migrations:migrate
 * --dry-run`) prints the report and writes nothing, and running it again finds
 * nothing left to convert. The Elasticsearch documents are rebuilt by the
 * reindex the deploy runs after the migrations.
 */
final class Version20261013150000 extends AbstractMigration
{
    /** Each convertible unit, as its family and its factor to the family's base unit. */
    private const FAMILIES = [
        'g' => ['mass', 1.0],
        'kg' => ['mass', 1000.0],
        'ml' => ['volume', 1.0],
        'cl' => ['volume', 10.0],
        'l' => ['volume', 1000.0],
    ];

    private const EPSILON = 1e-9;

    public function getDescription(): string
    {
        return 'Count the grocery lines of packaged products in packagings: convert their quantities and units, merge their open lines';
    }

    public function up(Schema $schema): void
    {
        $products = $this->connection->fetchAllAssociative(
            'SELECT id, name, packaging_unit, packaging_size, packaging_size_unit FROM product WHERE packaging_unit IS NOT NULL ORDER BY name',
        );

        $converted = 0;
        $merged = 0;
        $contributions = 0;
        $examples = [];

        foreach ($products as $product) {
            $report = $this->convert($product);
            $converted += $report['converted'];
            $merged += $report['merged'];
            $contributions += $report['contributions'];
            array_push($examples, ...$report['examples']);
        }

        $this->write(sprintf(
            'MAG-299: %d line(s) of packaged products converted to packagings, %d merged into another, %d meal share(s) converted.',
            $converted,
            $merged,
            $contributions,
        ));
        foreach (\array_slice($examples, 0, 10) as $example) {
            $this->write('  '.$example);
        }
    }

    /** Data only, and the grams that were rounded up are not recoverable: there is nothing to go back to. */
    public function down(Schema $schema): void
    {
    }

    /**
     * Queues the statements for one product.
     *
     * @param array<string, mixed> $product
     *
     * @return array{converted: int, merged: int, contributions: int, examples: list<string>}
     */
    private function convert(array $product): array
    {
        $packagingUnit = (string) $product['packaging_unit'];
        $size = null !== $product['packaging_size'] ? (float) $product['packaging_size'] : null;
        $sizeUnit = $product['packaging_size_unit'];

        $lines = [];
        foreach ($this->connection->fetchAllAssociative(
            'SELECT id, quantity, unit, checked, source, buy_after FROM grocery_item WHERE product_id = :product ORDER BY position, id',
            ['product' => $product['id']],
        ) as $row) {
            $lines[$row['id']] = [
                'before' => $row,
                'quantity' => $this->count($row['quantity'], $row['unit'], $packagingUnit, $size, $sizeUnit),
                'unit' => $packagingUnit,
                'checked' => (bool) $row['checked'],
                'source' => $row['source'],
                'buyAfter' => $row['buy_after'],
                'meals' => [],
                'alive' => true,
            ];
        }

        if ([] === $lines) {
            return ['converted' => 0, 'merged' => 0, 'contributions' => 0, 'examples' => []];
        }

        $shares = [];
        foreach ($this->connection->fetchAllAssociative(
            'SELECT c.id, c.meal_id, c.grocery_item_id, c.quantity, c.unit FROM meal_grocery_contribution c JOIN grocery_item i ON i.id = c.grocery_item_id WHERE i.product_id = :product ORDER BY c.id',
            ['product' => $product['id']],
        ) as $row) {
            $shares[$row['id']] = [
                'before' => $row,
                'quantity' => $this->count($row['quantity'], $row['unit'], $packagingUnit, $size, $sizeUnit, nullIsOne: false),
                'item' => $row['grocery_item_id'],
                'meal' => $row['meal_id'],
                'alive' => true,
            ];
            $lines[$row['grocery_item_id']]['meals'][$row['meal_id']] = $row['id'];
        }

        // A line is never worth less than what the meals hold on it.
        foreach ($lines as $id => $line) {
            if ($line['checked']) {
                continue;
            }
            $held = array_sum(array_map(static fn (string $share) => $shares[$share]['quantity'], $line['meals']));
            $lines[$id]['quantity'] = max($line['quantity'], $held);
        }

        // One open line per product: the first (by position) takes the others.
        $keeperId = null;
        $mergedCount = 0;
        foreach ($lines as $id => $line) {
            if ($line['checked']) {
                continue;
            }

            if (null === $keeperId) {
                $keeperId = $id;

                continue;
            }

            $keeper = &$lines[$keeperId];
            $keeper['quantity'] += $line['quantity'];
            $keeper['buyAfter'] = null === $keeper['buyAfter'] || null === $line['buyAfter'] ? null : min($keeper['buyAfter'], $line['buyAfter']);
            $keeper['source'] = 'manual' === $keeper['source'] || 'manual' === $line['source'] ? 'manual' : $keeper['source'];

            foreach ($line['meals'] as $meal => $shareId) {
                if (isset($keeper['meals'][$meal])) {
                    $shares[$keeper['meals'][$meal]]['quantity'] += $shares[$shareId]['quantity'];
                    $shares[$shareId]['alive'] = false;
                } else {
                    $shares[$shareId]['item'] = $keeperId;
                    $keeper['meals'][$meal] = $shareId;
                }
            }
            unset($keeper);

            $lines[$id]['alive'] = false;
            ++$mergedCount;
        }

        $convertedCount = 0;
        $contributionCount = 0;
        $examples = [];

        foreach ($shares as $id => $share) {
            if (!$share['alive']) {
                $this->addSql('DELETE FROM meal_grocery_contribution WHERE id = :id', ['id' => $id]);

                continue;
            }

            $before = $share['before'];
            if ($share['item'] === $before['grocery_item_id'] && $this->same($share['quantity'], $before['quantity']) && $before['unit'] === $packagingUnit) {
                continue;
            }

            $this->addSql(
                'UPDATE meal_grocery_contribution SET quantity = :quantity, unit = :unit, grocery_item_id = :item WHERE id = :id',
                ['quantity' => $share['quantity'], 'unit' => $packagingUnit, 'item' => $share['item'], 'id' => $id],
            );
            ++$contributionCount;
        }

        foreach ($lines as $id => $line) {
            if (!$line['alive']) {
                $this->addSql('DELETE FROM grocery_item WHERE id = :id', ['id' => $id]);

                continue;
            }

            $before = $line['before'];
            if ($this->same($line['quantity'], $before['quantity']) && $before['unit'] === $packagingUnit
                && $line['buyAfter'] === $before['buy_after'] && $line['source'] === $before['source']) {
                continue;
            }

            $this->addSql(
                'UPDATE grocery_item SET quantity = :quantity, unit = :unit, buy_after = :buyAfter, source = :source WHERE id = :id',
                ['quantity' => $line['quantity'], 'unit' => $packagingUnit, 'buyAfter' => $line['buyAfter'], 'source' => $line['source'], 'id' => $id],
            );
            ++$convertedCount;

            if (\count($examples) < 3) {
                $examples[] = sprintf(
                    '%s: %s %s → %s %s',
                    $product['name'],
                    $before['quantity'] ?? '—',
                    $before['unit'] ?? '—',
                    $line['quantity'],
                    $packagingUnit,
                );
            }
        }

        return ['converted' => $convertedCount, 'merged' => $mergedCount, 'contributions' => $contributionCount, 'examples' => $examples];
    }

    /**
     * The packagings a quantity comes to: rounded up, one when it cannot be
     * computed or is missing, nothing when a share was emptied to zero.
     */
    private function count(mixed $quantity, ?string $unit, string $packagingUnit, ?float $size, ?string $sizeUnit, bool $nullIsOne = true): float
    {
        if (null === $quantity) {
            return $nullIsOne ? 1.0 : 0.0;
        }

        $quantity = (float) $quantity;
        if ($quantity <= self::EPSILON) {
            return 0.0;
        }

        $unit ??= $packagingUnit;
        if ($unit === $packagingUnit) {
            return (float) max(1, (int) ceil($quantity - self::EPSILON));
        }

        $ratio = $this->ratio($quantity, $unit, $size, $sizeUnit);

        return null === $ratio ? 1.0 : (float) max(1, (int) ceil($ratio - self::EPSILON));
    }

    private function ratio(float $quantity, string $unit, ?float $size, ?string $sizeUnit): ?float
    {
        if (null === $size || null === $sizeUnit || $size <= 0) {
            return null;
        }

        if ($unit === $sizeUnit) {
            return $quantity / $size;
        }

        $from = self::FAMILIES[$unit] ?? null;
        $to = self::FAMILIES[$sizeUnit] ?? null;
        if (null === $from || null === $to || $from[0] !== $to[0]) {
            return null;
        }

        return ($quantity * $from[1]) / ($size * $to[1]);
    }

    private function same(float $a, mixed $b): bool
    {
        return null !== $b && abs($a - (float) $b) < self::EPSILON;
    }
}
