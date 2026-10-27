<?php

declare(strict_types=1);

namespace Maggie\Calendar\Filter;

use ApiPlatform\Doctrine\Orm\Filter\AbstractFilter;
use ApiPlatform\Doctrine\Orm\Util\QueryNameGeneratorInterface;
use ApiPlatform\Metadata\Operation;
use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\QueryBuilder;
use Maggie\Calendar\Service\TripDay;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;

/**
 * `date=YYYY-MM-DD`: the trips leaving that Paris day ({@see TripDay}). A date
 * that cannot be read is a 400, never the whole list.
 */
final class TripDayFilter extends AbstractFilter
{
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

        try {
            [$from, $to] = TripDay::bounds(\is_string($value) ? $value : '');
        } catch (\InvalidArgumentException $e) {
            throw new BadRequestHttpException(sprintf('"%s": %s', $property, $e->getMessage()), $e);
        }

        $alias = $queryBuilder->getRootAliases()[0];
        $fromParameter = $queryNameGenerator->generateParameterName('departFrom');
        $toParameter = $queryNameGenerator->generateParameterName('departTo');

        $queryBuilder
            ->andWhere(sprintf('%1$s.departAt >= :%2$s AND %1$s.departAt < :%3$s', $alias, $fromParameter, $toParameter))
            ->setParameter($fromParameter, $from, Types::DATETIMETZ_IMMUTABLE)
            ->setParameter($toParameter, $to, Types::DATETIMETZ_IMMUTABLE);
    }

    /**
     * @return array<string, array<string, mixed>>
     */
    public function getDescription(string $resourceClass): array
    {
        $description = [];
        foreach (array_keys($this->properties ?? []) as $property) {
            $description[$property] = [
                'property' => $property,
                'type' => 'string',
                'required' => false,
                'description' => 'The trips leaving that day, YYYY-MM-DD read in Europe/Paris; any other value is refused with a 400.',
            ];
        }

        return $description;
    }
}
