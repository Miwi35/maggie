<?php

declare(strict_types=1);

namespace Maggie\Finance\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Contact;
use Maggie\Finance\Entity\ContactCounterparty;

/** @extends ServiceEntityRepository<ContactCounterparty> */
class ContactCounterpartyRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, ContactCounterparty::class);
    }

    /** @return ContactCounterparty[] */
    public function findByUser(User $user): array
    {
        return $this->findBy(['user' => $user], ['id' => 'ASC']);
    }

    /** @return ContactCounterparty[] */
    public function findByContact(Contact $contact): array
    {
        return $this->findBy(['contact' => $contact], ['id' => 'ASC']);
    }

    public function findOneByKey(User $user, string $matchKey): ?ContactCounterparty
    {
        return $this->findOneBy(['user' => $user, 'matchKey' => $matchKey]);
    }

    public function findOneOwned(string $id, User $user): ?ContactCounterparty
    {
        return $this->findOneBy(['id' => $id, 'user' => $user]);
    }
}
