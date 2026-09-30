<?php

namespace Maggie\Calendar\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Task;

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
