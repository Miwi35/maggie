<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\UseCase\ApplyCategorizationRules;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

final class ApplyCategorizationRulesController
{
    public function __construct(
        private readonly Security $security,
        private readonly ApplyCategorizationRules $applyCategorizationRules,
    ) {
    }

    #[Route('/api/finance/apply-categorization-rules', name: 'api_finance_apply_categorization_rules', methods: ['POST'])]
    public function __invoke(): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        return new JsonResponse(['success' => true] + $this->applyCategorizationRules->execute($user));
    }
}
