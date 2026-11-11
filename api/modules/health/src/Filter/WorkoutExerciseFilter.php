<?php

declare(strict_types=1);

namespace Maggie\Health\Filter;

use ApiPlatform\Doctrine\Orm\Filter\AbstractFilter;
use ApiPlatform\Doctrine\Orm\Util\QueryNameGeneratorInterface;
use ApiPlatform\Metadata\Operation;
use Doctrine\ORM\QueryBuilder;
use Maggie\Health\Entity\WorkoutExercise;

/**
 * `exercise=<slug>`: the sessions that hold that exercise (MAG-775), with
 * `order[startedAt]=desc` « the last time » it was done or planned.
 */
final class WorkoutExerciseFilter extends AbstractFilter
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
        if (!$this->isPropertyEnabled($property, $resourceClass) || !\is_string($value) || '' === $value) {
            return;
        }

        $alias = $queryBuilder->getRootAliases()[0];
        $exerciseAlias = $queryNameGenerator->generateJoinAlias('workoutExercise');
        $parameter = $queryNameGenerator->generateParameterName('exercise');

        $queryBuilder
            ->andWhere(sprintf('EXISTS (SELECT 1 FROM %1$s %2$s WHERE %2$s.workout = %3$s AND %2$s.exercise = :%4$s)', WorkoutExercise::class, $exerciseAlias, $alias, $parameter))
            ->setParameter($parameter, $value);
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
                'description' => 'The sessions holding this exercise (catalogue slug, e.g. goblet-squat).',
            ];
        }

        return $description;
    }
}
