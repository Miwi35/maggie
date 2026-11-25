<?php

declare(strict_types=1);

namespace Maggie\Grocery\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Grocery\Entity\Errand;
use Maggie\Grocery\Entity\PurchasedItem;
use Maggie\Grocery\Repository\ErrandRepository;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'list_past_errands', description: 'What was bought, errand by errand; the current list is get_grocery_list. Past errands, most recent first: one store each (storeName, null when none), endedAt (ISO 8601), items (label, quantity, unit, product IRI or null, packaging fields). store matches the name in part, case-insensitively; limit 1-20 (default 5).')]
class ListPastErrandsTool
{
    private const MAX_LIMIT = 20;

    public function __construct(
        private readonly ErrandRepository $errandRepository,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(?string $store = null, int $limit = 5): string
    {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        if ($limit < 1 || $limit > self::MAX_LIMIT) {
            return json_encode(['error' => sprintf('limit must be between 1 and %d.', self::MAX_LIMIT)], JSON_THROW_ON_ERROR);
        }

        $errands = $this->errandRepository->findLatestForUser($user, $store, $limit);

        return json_encode([
            'errands' => array_map(static fn (Errand $errand) => [
                'id' => (string) $errand->getId(),
                'storeName' => $errand->getStoreName(),
                'endedAt' => $errand->getEndedAt()->format(\DateTimeInterface::ATOM),
                'itemCount' => $errand->getItemCount(),
                'items' => array_values($errand->getItems()->map(static fn (PurchasedItem $item) => $item->toPayload())->toArray()),
            ], $errands),
            'count' => \count($errands),
        ], JSON_THROW_ON_ERROR);
    }
}
