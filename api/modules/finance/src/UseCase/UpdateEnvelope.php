<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Envelope;

class UpdateEnvelope
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Envelope $envelope): Envelope
    {
        $this->em->flush();

        return $envelope;
    }
}
