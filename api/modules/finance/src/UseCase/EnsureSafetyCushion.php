<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\SafetyCushion;
use Maggie\Finance\Repository\SafetyCushionRepository;

/** The user's safety cushion, laid down with its defaults the first time something needs to write it. */
class EnsureSafetyCushion
{
    public function __construct(
        private readonly SafetyCushionRepository $cushionRepository,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(User $user): SafetyCushion
    {
        $cushion = $this->cushionRepository->findOneByUser($user);
        if (null !== $cushion) {
            return $cushion;
        }

        $cushion = (new SafetyCushion())->setUser($user);
        $this->em->persist($cushion);
        $this->em->flush();

        return $cushion;
    }
}
