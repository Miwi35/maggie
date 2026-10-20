<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Projection\WorkCollector;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Repository\ProjectRepository;

class UpdateProject
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly ProjectRepository $projectRepository,
        private readonly WorkCollector $projection,
    ) {
    }

    public function execute(Project $project): Project
    {
        // Read before the flush, which makes the new date the original one.
        $original = $this->em->getUnitOfWork()->getOriginalEntityData($project)['targetOn'] ?? null;
        $dateMoved = ($original instanceof \DateTimeInterface ? $original->format('Y-m-d') : null)
            !== $project->getTargetOn()?->format('Y-m-d');

        $this->em->flush();

        // The projects waiting for this one compare their date to its date:
        // their unmet dependencies move with it, though no column of theirs did.
        if ($dateMoved) {
            foreach ($this->projectRepository->findDependents($project) as $dependent) {
                $this->projection->touched($dependent, ['unmetDependencies']);
            }
        }

        return $project;
    }
}
