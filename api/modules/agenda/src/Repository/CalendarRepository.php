<?php

namespace Maggie\Agenda\Repository;

use Maggie\Agenda\Entity\Calendar;
use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;

/**
 * @extends ServiceEntityRepository<Calendar>
 */
class CalendarRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Calendar::class);
    }

    public function findDefault(): ?Calendar
    {
        return $this->findOneBy(['isDefault' => true]);
    }
}
