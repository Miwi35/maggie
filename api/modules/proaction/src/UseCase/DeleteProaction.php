<?php

declare(strict_types=1);

namespace Maggie\Proaction\UseCase;

use Maggie\Proaction\Entity\Proaction;
use Doctrine\ORM\EntityManagerInterface;

class DeleteProaction
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Proaction $proaction): void
    {
        $this->em->remove($proaction);
        $this->em->flush();
    }
}
