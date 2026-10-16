<?php

declare(strict_types=1);

namespace Maggie\Grocery\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Grocery\Entity\Errand;
use Maggie\Grocery\Entity\PurchasedItem;
use Maggie\Grocery\Repository\ErrandRepository;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'list_past_errands', description: 'Past errands (« courses passées »), most recent first: what the user bought, where and when — e.g. « qu\'est-ce que j\'ai acheté la dernière fois chez Carrefour ? » is store="Carrefour", limit=1. Each errand is one store (storeName, null when the lines had none), endedAt (ISO 8601) and its items: label, quantity, unit, product (IRI, null for a free-text line) and the packaging the product had then (packagingUnit, packagingSize, packagingSizeUnit, null when unknown). store matches the store name in part, case-insensitively; limit is 1 to 20 (5 by default). An errand is archived by end_errand.')]
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
