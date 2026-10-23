<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Project;

/**
 * The project stays, with its funding (its loan included) and its price: it is
 * no longer rented, nor resold, and the resale it set aside is taken back.
 */
class RemoveProjectInvestment
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly SetProjectInvestment $setProjectInvestment,
    ) {
    }

    public function execute(Project $project): Project
    {
        $this->em->getConnection()->transactional(function () use ($project): void {
            $this->setProjectInvestment->unsetResaleSources($project);
            $project->setInvestment(null);
            $this->em->flush();
        });

        return $project;
    }
}
