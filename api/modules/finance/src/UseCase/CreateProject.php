<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Repository\ProjectRepository;

class CreateProject
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly ProjectRepository $projectRepository,
    ) {
    }

    /** A new project comes last in the user's priority order. */
    public function execute(Project $project): Project
    {
        $project->setPosition($this->projectRepository->nextPosition($project->getUser()));

        $this->em->persist($project);
        $this->em->flush();

        return $project;
    }
}
