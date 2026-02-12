<?php

namespace Maggie\Calendar\UseCase;

use Maggie\Calendar\Entity\Task;
use Doctrine\ORM\EntityManagerInterface;

class UpdateTask
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Task $task): Task
    {
        $this->em->flush();

        return $task;
    }
}
