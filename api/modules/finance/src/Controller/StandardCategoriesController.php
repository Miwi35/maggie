<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\UseCase\InstallStandardCategories;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/** Lays down the categories a budget starts from, for whoever is asking. */
final class StandardCategoriesController
{
    public function __construct(
        private readonly Security $security,
        private readonly InstallStandardCategories $installStandardCategories,
    ) {
    }

    #[Route('/api/finance/categories/standard', name: 'api_finance_standard_categories', methods: ['POST'])]
    public function __invoke(): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        return new JsonResponse(['success' => true] + $this->installStandardCategories->execute($user));
    }
}
