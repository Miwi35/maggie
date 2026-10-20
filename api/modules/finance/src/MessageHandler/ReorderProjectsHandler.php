<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\Project;
use Maggie\Finance\Message\ReorderProjectsCommand;
use Maggie\Finance\Repository\ProjectRepository;
use Maggie\Finance\UseCase\ReorderProjects;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/**
 * The order is the whole list, never a part of it: a project left out would
 * need a place nobody chose.
 */
#[AsMessageHandler]
class ReorderProjectsHandler
{
    public function __construct(
        private readonly ReorderProjects $reorderProjects,
        private readonly ProjectRepository $projectRepository,
    ) {
    }

    /** @return list<Project> in the new order */
    public function __invoke(ReorderProjectsCommand $command): array
    {
        $duplicates = array_keys(array_filter(array_count_values($command->ids), static fn (int $count): bool => $count > 1));
        if ([] !== $duplicates) {
            throw new \DomainException('The list names a project more than once: '.implode(', ', $duplicates).'.');
        }

        $byId = [];
        foreach ($this->projectRepository->findByUser($command->userId) as $project) {
            $byId[(string) $project->getId()] = $project;
        }

        $ordered = [];
        foreach ($command->ids as $id) {
            $ordered[] = $byId[$id] ?? throw new \DomainException("Project not found: {$id}");
        }

        if (\count($ordered) !== \count($byId)) {
            $missing = array_diff(array_keys($byId), $command->ids);

            throw new \DomainException('The list must name every project, in the new order. Missing: '.implode(', ', $missing).'.');
        }

        return $this->reorderProjects->execute($ordered);
    }
}
