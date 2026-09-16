<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Envelope;

class DeleteEnvelope
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Envelope $envelope): void
    {
        $this->em->remove($envelope);
        $this->em->flush();
    }
}
