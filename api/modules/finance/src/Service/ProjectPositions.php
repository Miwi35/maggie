<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Project;

/**
 * Renumbers a user's projects 0..n-1 under the unique (user, position) index.
 *
 * One flush cannot do it: Doctrine sends the updates in an order of its own,
 * and swapping two positions collides on the index halfway. So the projects
 * that move go to free negative positions first, then to their final ones —
 * the caller wraps both flushes in one transaction. A project already in
 * place is not touched, so nothing is published for it.
 */
class ProjectPositions
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    /** @param list<Project> $ordered the user's every project, first one first */
    public function renumber(array $ordered): void
    {
        $moving = [];
        foreach ($ordered as $position => $project) {
            if ($project->getPosition() !== $position) {
                $moving[$position] = $project;
            }
        }

        if ([] === $moving) {
            return;
        }

        foreach ($moving as $position => $project) {
            $project->setPosition(-1 - $position);
        }
        $this->em->flush();

        foreach ($moving as $position => $project) {
            $project->setPosition($position);
        }
        $this->em->flush();
    }
}
