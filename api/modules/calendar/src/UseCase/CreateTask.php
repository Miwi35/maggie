<?php

namespace Maggie\Calendar\UseCase;

use Maggie\Calendar\Entity\Task;
use Doctrine\ORM\EntityManagerInterface;

class CreateTask
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Task $task): Task
    {
        $this->em->persist($task);
        $this->em->flush();

        return $task;
    }
}
