<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Envelope;

class CreateEnvelope
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Envelope $envelope): Envelope
    {
        $this->em->persist($envelope);
        $this->em->flush();

        return $envelope;
    }
}
