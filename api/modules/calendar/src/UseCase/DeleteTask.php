<?php

namespace Maggie\Calendar\UseCase;

use Maggie\Calendar\Entity\Task;
use Doctrine\ORM\EntityManagerInterface;

class DeleteTask
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Task $task): void
    {
        $this->em->remove($task);
        $this->em->flush();
    }
}
