<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Cookbook\Entity\CiqualNutrient;

/** @extends ServiceEntityRepository<CiqualNutrient> */
class CiqualNutrientRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, CiqualNutrient::class);
    }

    public function findByConstCode(string $constCode): ?CiqualNutrient
    {
        return $this->findOneBy(['constCode' => $constCode]);
    }
}
