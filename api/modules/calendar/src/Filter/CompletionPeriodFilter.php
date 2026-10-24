<?php

declare(strict_types=1);

namespace Maggie\Calendar\Filter;

use ApiPlatform\Doctrine\Orm\Filter\AbstractFilter;
use ApiPlatform\Doctrine\Orm\Util\QueryNameGeneratorInterface;
use ApiPlatform\Metadata\Operation;
use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\QueryBuilder;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;

/**
 * `completedAt[after]`, `completedAt[before]` (and their strict forms): the tasks
 * completed within a period, the domain filter behind « faites aujourd'hui ».
 *
 * Unlike API Platform's DateFilter, which drops a value that is not a date and
 * answers the whole collection, a bound that cannot be read is a 400: a card
 * counting « faites du jour » would otherwise count every task ever done.
 * Elasticsearch serves the collection in production through the translator's
 * generic range clause on the same field; a bad bound fails there too and the
 * Doctrine fallback answers the 400.
 */
final class CompletionPeriodFilter extends AbstractFilter
{
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
        if (!$this->isPropertyEnabled($property, $resourceClass)) {
            return;
        }

        if (!\is_array($value)) {
            throw new BadRequestHttpException(sprintf('"%s" takes a bound: %1$s[after] or %1$s[before].', $property));
        }

        $alias = $queryBuilder->getRootAliases()[0];

        foreach ($value as $operator => $operand) {
            if (!isset(self::OPERATORS[$operator])) {
                throw new BadRequestHttpException(sprintf('"%s[%s]" is not a known bound.', $property, $operator));
            }

            $instant = $this->instant($property, $operator, $operand);
            $parameter = $queryNameGenerator->generateParameterName($property);

            $queryBuilder
                ->andWhere(sprintf('%s.%s %s :%s', $alias, $property, self::OPERATORS[$operator], $parameter))
                ->setParameter($parameter, $instant, Types::DATETIMETZ_IMMUTABLE);
        }
    }

    private function instant(string $property, string $operator, mixed $operand): \DateTimeImmutable
    {
        if (!\is_string($operand) || '' === trim($operand)) {
            throw new BadRequestHttpException(sprintf('"%s[%s]" is not a date.', $property, $operator));
        }

        try {
            return new \DateTimeImmutable($operand);
        } catch (\Exception) {
            throw new BadRequestHttpException(sprintf('"%s[%s]" is not a date.', $property, $operator));
        }
    }

    /**
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
                    'description' => 'A bound that is not a date is refused with a 400.',
                ];
            }
        }

        return $description;
    }
}
