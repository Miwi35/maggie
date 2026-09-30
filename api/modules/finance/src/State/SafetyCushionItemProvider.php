<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProviderInterface;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\SafetyCushion;
use Maggie\Finance\Repository\SafetyCushionRepository;
use Symfony\Bundle\SecurityBundle\Security;

/** @implements ProviderInterface<SafetyCushion> */
class SafetyCushionItemProvider implements ProviderInterface
{
    public function __construct(
        private readonly Security $security,
        private readonly SafetyCushionRepository $repository,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function provide(Operation $operation, array $uriVariables = [], array $context = []): ?SafetyCushion
    {
        $user = $this->security->getUser();

        if (!$user instanceof User) {
            return null;
        }

        $cushion = $this->repository->findOneByUser($user);

        if (null === $cushion) {
            $cushion = (new SafetyCushion())->setUser($user);
            $this->em->persist($cushion);
            $this->em->flush();
        }

        return $cushion;
    }
}
