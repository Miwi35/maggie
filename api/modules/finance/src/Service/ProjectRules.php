<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Enum\ProjectDateMode;
use Maggie\Finance\Enum\ProjectWellbeing;
use Maggie\Finance\Repository\ProjectRepository;

/**
 * What a project command is held to whichever door it comes through (API,
 * MCP): its enums, its date and its dependencies. Every refusal is a
 * DomainException, a 400 for the API and an error for the tool.
 */
class ProjectRules
{
    public function __construct(
        private readonly ProjectRepository $projectRepository,
    ) {
    }

    public function wellbeing(string $value): ProjectWellbeing
    {
        return ProjectWellbeing::tryFrom($value)
            ?? throw new \DomainException("Unknown wellbeing: {$value}. Use essential, high, medium or comfort.");
    }

    public function dateMode(string $value): ProjectDateMode
    {
        return ProjectDateMode::tryFrom($value)
            ?? throw new \DomainException("Unknown dateMode: {$value}. Use none, fixed or deadline.");
    }

    public function assertDate(ProjectDateMode $mode, ?\DateTimeImmutable $targetOn): void
    {
        if (null !== $message = Project::dateProblem($mode, $targetOn)) {
            throw new \DomainException($message);
        }
    }

    public function assertAmount(int $amountCents): void
    {
        if ($amountCents <= 0) {
            throw new \DomainException('amountCents must be a positive number of cents.');
        }
    }

    public function assertName(string $name): void
    {
        if ('' === trim($name)) {
            throw new \DomainException('name must not be blank.');
        }
    }

    public function assertCurrency(string $currency): void
    {
        if (1 !== preg_match('/^[A-Z]{3}$/', $currency)) {
            throw new \DomainException('The currency must be a 3-letter ISO 4217 code.');
        }
    }

    /**
     * The user's projects these ids name, refusing another user's (reported
     * as not found), the project itself and a cycle.
     *
     * @param list<string> $ids
     *
     * @return list<Project>
     */
    public function dependencies(User|string $user, ?Project $project, array $ids): array
    {
        $dependencies = [];
        foreach (array_unique($ids) as $id) {
            if (null !== $project && $id === (string) $project->getId()) {
                throw new \DomainException(Project::SELF_DEPENDENCY);
            }

            $dependencies[] = $this->projectRepository->findOneOfUser($user, $id)
                ?? throw new \DomainException("Project not found: {$id}");
        }

        if (null !== $project) {
            $this->assertNoCycle($project, $dependencies);
        }

        return $dependencies;
    }

    /**
     * Walks what the new dependencies wait for, transitively: reaching the
     * project again closes a loop (A → B → A) the planning could never order.
     *
     * @param list<Project> $dependencies
     */
    private function assertNoCycle(Project $project, array $dependencies): void
    {
        $target = (string) $project->getId();
        $seen = [];
        $stack = $dependencies;

        while ([] !== $stack) {
            $current = array_pop($stack);
            $id = (string) $current->getId();
            if ($id === $target) {
                throw new \DomainException('These dependencies would make a cycle: a project would end up waiting for itself.');
            }
            if (isset($seen[$id])) {
                continue;
            }
            $seen[$id] = true;

            foreach ($current->getDependsOn() as $next) {
                $stack[] = $next;
            }
        }
    }
}
