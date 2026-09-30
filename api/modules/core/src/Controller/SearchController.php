<?php

declare(strict_types=1);

namespace Maggie\Core\Controller;

use Maggie\Core\Elasticsearch\SearchService;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

final class SearchController
{
    public function __construct(
        private readonly SearchService $searchService,
        private readonly Security $security,
    ) {
    }

    #[Route('/api/search', name: 'api_search', methods: ['GET'])]
    public function __invoke(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $query = $request->query->getString('q');
        if ('' === $query) {
            return new JsonResponse(['error' => 'Missing required parameter: q'], Response::HTTP_BAD_REQUEST);
        }

        $page = max(1, $request->query->getInt('page', 1));
        $limit = min(100, max(1, $request->query->getInt('limit', 10)));
        $from = ($page - 1) * $limit;

        $typesParam = $request->query->getString('types');
        $types = '' !== $typesParam
            ? array_map('trim', explode(',', $typesParam))
            : null;

        $results = $this->searchService->search(
            $query,
            (string) $user->getId(),
            $types,
            $from,
            $limit,
        );

        return new JsonResponse([
            'total' => $results['total'],
            'page' => $page,
            'limit' => $limit,
            'results' => $results['results'],
        ]);
    }
}
