<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Service\ProjectPositions;

class ReorderProjects
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly ProjectPositions $positions,
    ) {
    }

    /**
     * @param list<Project> $ordered the user's every project, first one first
     *
     * @return list<Project>
     */
    public function execute(array $ordered): array
    {
        $this->em->getConnection()->transactional(fn () => $this->positions->renumber($ordered));

        return $ordered;
    }
}
