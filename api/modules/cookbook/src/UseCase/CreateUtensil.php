<?php

declare(strict_types=1);

namespace Maggie\Cookbook\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Utensil;
use Maggie\Cookbook\Repository\UtensilRepository;
use Maggie\Core\Entity\User;

class CreateUtensil
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly UtensilRepository $utensilRepository,
    ) {
    }

    /**
     * The user's utensil of that name: the one already in the catalogue,
     * whatever its case, or a new one. Not flushed: the caller does.
     */
    public function findOrCreate(User $user, string $name): Utensil
    {
        $existing = $this->utensilRepository->findByNameAndUser($name, $user);
        if (null !== $existing) {
            return $existing;
        }

        $utensil = new Utensil();
        $utensil->setUser($user);
        $utensil->setName($name);
        $this->em->persist($utensil);

        return $utensil;
    }

    public function execute(User $user, string $name): Utensil
    {
        $utensil = $this->findOrCreate($user, $name);
        $this->em->flush();

        return $utensil;
    }
}
