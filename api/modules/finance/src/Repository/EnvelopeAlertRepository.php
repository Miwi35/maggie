<?php

declare(strict_types=1);

namespace Maggie\Finance\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Entity\EnvelopeAlert;

/** @extends ServiceEntityRepository<EnvelopeAlert> */
class EnvelopeAlertRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, EnvelopeAlert::class);
    }

    public function wasSent(Envelope $envelope, string $period, int $thresholdPercent): bool
    {
        return null !== $this->findOneBy([
            'envelope' => $envelope,
            'period' => $period,
            'thresholdPercent' => $thresholdPercent,
        ]);
    }
}
