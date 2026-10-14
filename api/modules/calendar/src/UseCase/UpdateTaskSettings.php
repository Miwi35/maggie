<?php

declare(strict_types=1);

namespace Maggie\Calendar\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\TaskSettings;

class UpdateTaskSettings
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(TaskSettings $settings): TaskSettings
    {
        $this->em->flush();

        return $settings;
    }
}
