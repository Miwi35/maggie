<?php

namespace Maggie\Calendar\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Repository\TaskRepository;

class DeleteTask
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly TaskRepository $taskRepository,
    ) {
    }

    /**
     * The subtasks go with their parent, as on Google — removed one by one
     * rather than left to the database's cascade, so each leaves the index
     * and the screens.
     */
    public function execute(Task $task): void
    {
        foreach ($this->taskRepository->findBy(['parent' => $task]) as $subtask) {
            $this->em->remove($subtask);
        }
        $this->em->remove($task);
        $this->em->flush();
    }
}
