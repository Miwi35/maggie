<?php

declare(strict_types=1);

namespace Maggie\Calendar\Filter;

use ApiPlatform\Doctrine\Orm\Filter\AbstractFilter;
use ApiPlatform\Doctrine\Orm\Util\QueryNameGeneratorInterface;
use ApiPlatform\Metadata\Operation;
use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\QueryBuilder;
use Maggie\Core\Time\DayBound;

/**
 * `startAt[before]`, `endAt[after]` and the rest, for both kinds of event (MAG-382).
 *
 * API Platform's DateFilter compares the instant, and an all-day event has
 * none: it would drop out of every range the clients ask for. Here a timed
 * event is compared by its instant, as before, and an all-day one by its days
 * — `startAt` stands for `startDate`, `endAt` for `endDate` — with the day of
 * the bound {@see DayBound} gives. Elasticsearch, which serves the collection
 * in production, does the same through `IndexedField::$dayField`.
 */
final class EventPeriodFilter extends AbstractFilter
{
    private const array DAY_FIELDS = ['startAt' => 'startDate', 'endAt' => 'endDate'];

    private const array OPERATORS = ['before' => '<=', 'strictly_before' => '<', 'after' => '>=', 'strictly_after' => '>'];

    protected function filterProperty(
        string $property,
        mixed $value,
        QueryBuilder $queryBuilder,
        QueryNameGeneratorInterface $queryNameGenerator,
        string $resourceClass,
        ?Operation $operation = null,
        array $context = [],
    ): void {
        if (!\is_array($value) || !isset(self::DAY_FIELDS[$property]) || !$this->isPropertyEnabled($property, $resourceClass)) {
            return;
        }

        $alias = $queryBuilder->getRootAliases()[0];
        $dayField = self::DAY_FIELDS[$property];

        foreach ($value as $operator => $operand) {
            if (!isset(self::OPERATORS[$operator]) || !\is_string($operand) || '' === trim($operand)) {
                continue;
            }

            try {
                $instant = new \DateTimeImmutable($operand);
                $bound = DayBound::forOperator($operator, $operand, 'endDate' === $dayField);
            } catch (\Exception) {
                // DateFilter's own answer to a value that is not a date: no clause.
                continue;
            }
            if (null === $bound) {
                continue;
            }

            $instantParameter = $queryNameGenerator->generateParameterName($property);
            $dayParameter = $queryNameGenerator->generateParameterName($dayField);

            $queryBuilder
                ->andWhere(sprintf(
                    '((%1$s.%2$s IS NOT NULL AND %1$s.%2$s %3$s :%4$s) OR (%1$s.%2$s IS NULL AND %1$s.%5$s %6$s :%7$s))',
                    $alias,
                    $property,
                    self::OPERATORS[$operator],
                    $instantParameter,
                    $dayField,
                    ['gte' => '>=', 'gt' => '>', 'lte' => '<='][$bound[0]],
                    $dayParameter,
                ))
                ->setParameter($instantParameter, $instant, Types::DATETIMETZ_IMMUTABLE)
                ->setParameter($dayParameter, $bound[1], Types::DATE_IMMUTABLE);
        }
    }

    /**
     * The parameters DateFilter described, so the contract the clients read does not move.
     *
     * @return array<string, array<string, mixed>>
     */
    public function getDescription(string $resourceClass): array
    {
        $description = [];

        foreach (array_keys($this->properties ?? []) as $property) {
            foreach (array_keys(self::OPERATORS) as $operator) {
                $description[sprintf('%s[%s]', $property, $operator)] = [
                    'property' => $property,
                    'type' => \DateTimeInterface::class,
                    'required' => false,
                    'description' => sprintf('An all-day event, which has no %s, is compared by its %s.', $property, self::DAY_FIELDS[$property] ?? $property),
                ];
            }
        }

        return $description;
    }
}
