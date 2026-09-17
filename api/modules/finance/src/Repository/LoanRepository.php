<?php

declare(strict_types=1);

namespace Maggie\Finance\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Loan;

/** @extends ServiceEntityRepository<Loan> */
class LoanRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Loan::class);
    }

    /** @return Loan[] */
    public function findByUser(User $user): array
    {
        return $this->findBy(['user' => $user], ['priority' => 'DESC', 'id' => 'ASC']);
    }
}
