<?php

declare(strict_types=1);

namespace Maggie\Calendar\Controller;

use Maggie\Calendar\Entity\Reward;
use Maggie\Calendar\Repository\RewardRepository;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/**
 * The reward of a thing done (MAG-508), read by the « Bravo » after a tick:
 * laid down by the tick itself, so it is there once the save has answered.
 * Another user's is not found.
 */
final class RewardController
{
    public function __construct(
        private readonly Security $security,
        private readonly RewardRepository $repository,
    ) {
    }

    #[Route('/api/rewards/{subjectType}/{subjectId}', name: 'api_reward_read', methods: ['GET'])]
    public function read(string $subjectType, string $subjectId): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        if (!\in_array($subjectType, Reward::SUBJECTS, true)) {
            return new JsonResponse(['error' => 'subjectType must be one of '.implode(', ', Reward::SUBJECTS).'.'], Response::HTTP_BAD_REQUEST);
        }

        $reward = $this->repository->findOneBySubject($user, $subjectType, $subjectId);
        if (null === $reward) {
            return new JsonResponse(['error' => 'No reward for this '.$subjectType.'.'], Response::HTTP_NOT_FOUND);
        }

        return new JsonResponse($reward->describe());
    }
}
