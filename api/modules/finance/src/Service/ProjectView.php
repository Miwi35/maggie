<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Finance\Entity\Project;

/**
 * A project as the MCP tool and the reorder endpoint answer it: dependencies
 * and unmet ones as bare ids.
 */
final class ProjectView
{
    /** @return array<string, mixed> */
    public static function of(Project $project): array
    {
        return [
            'id' => (string) $project->getId(),
            'name' => $project->getName(),
            'amountCents' => $project->getAmountCents(),
            'currency' => $project->getCurrency(),
            'wellbeing' => $project->getWellbeing()->value,
            'wellbeingScore' => $project->getWellbeingScore(),
            'position' => $project->getPosition(),
            'dateMode' => $project->getDateMode()->value,
            'targetOn' => $project->getTargetOn()?->format('Y-m-d'),
            'dependsOn' => $project->dependencyIds(),
            'note' => $project->getNote(),
            'unmetDependencies' => $project->getUnmetDependencies(),
        ];
    }

    /**
     * @param list<Project> $projects
     *
     * @return list<array<string, mixed>>
     */
    public static function list(array $projects): array
    {
        return array_map(self::of(...), $projects);
    }
}
