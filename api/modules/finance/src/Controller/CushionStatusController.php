<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\UseCase\GetCushionStatus;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

final class CushionStatusController
{
    public function __construct(
        private readonly Security $security,
        private readonly GetCushionStatus $getCushionStatus,
    ) {
    }

    #[Route('/api/finance/cushion-status', name: 'api_finance_cushion_status', methods: ['GET'])]
    public function __invoke(): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        return new JsonResponse($this->getCushionStatus->execute($user));
    }
}
