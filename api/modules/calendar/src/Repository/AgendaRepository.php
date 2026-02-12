<?php

namespace Maggie\Calendar\Repository;

use Maggie\Calendar\Entity\Agenda;
use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;

/**
 * @extends ServiceEntityRepository<Agenda>
 */
class AgendaRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Agenda::class);
    }

    public function findDefault(): ?Agenda
    {
        return $this->findOneBy(['isDefault' => true]);
    }
}
