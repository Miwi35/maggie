<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Repository\ProjectRepository;
use Maggie\Finance\Service\ProjectPositions;

/**
 * A project leaves the lists of those that waited for it, and the projects
 * after it move up one place: the positions stay 0..n-1.
 */
class DeleteProject
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly ProjectRepository $projectRepository,
        private readonly ProjectPositions $positions,
    ) {
    }

    public function execute(Project $project): void
    {
        $user = $project->getUser();

        $this->em->getConnection()->transactional(function () use ($project, $user): void {
            foreach ($this->projectRepository->findDependents($project) as $dependent) {
                $dependent->removeDependency($project);
            }

            // The resale it would have funded is gone with it; what was set aside for it is sold to nobody.
            foreach ($this->projectRepository->findResaleSourcesOf($project) as $source) {
                $source->getProject()->removeFundingSource($source);
            }
            foreach ($this->projectRepository->findInvestmentsTargeting($project) as $investment) {
                $resale = $investment->getResale();
                $investment->planResale($resale['month'] ?? null, $resale['netAmountCents'] ?? null, null);
            }

            $this->em->remove($project);
            $this->em->flush();

            $this->positions->renumber($this->projectRepository->findByUser($user));
        });
    }
}
