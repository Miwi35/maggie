<?php

namespace Maggie\Core\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProviderInterface;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Entity\UserPreference;
use Maggie\Core\Repository\UserPreferenceRepository;
use Symfony\Bundle\SecurityBundle\Security;

/** @implements ProviderInterface<UserPreference> */
class UserPreferenceItemProvider implements ProviderInterface
{
    public function __construct(
        private readonly Security $security,
        private readonly UserPreferenceRepository $repository,
        private readonly EntityManagerInterface $entityManager,
    ) {
    }

    public function provide(Operation $operation, array $uriVariables = [], array $context = []): ?UserPreference
    {
        $user = $this->security->getUser();

        if (!$user instanceof User) {
            return null;
        }

        $pref = $this->repository->findOneByUser($user);

        if ($pref === null) {
            $pref = (new UserPreference())->setUser($user);
            $this->entityManager->persist($pref);
            $this->entityManager->flush();
        }

        return $pref;
    }
}
