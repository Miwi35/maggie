<?php

declare(strict_types=1);

namespace Maggie\Grocery\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Grocery\Service\PurchaseSuggestions;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_purchase_suggestions', description: 'Products the user buys often and that are due again (« tu en prends souvent »), computed from the past errands: each product bought at least 3 times, with everyDays (median gap between purchases, in days), lastBoughtOn and dueOn (YYYY-MM-DD), usualQuantity and usualUnit (median quantity, null when unknown). Use it for « il me manque quelque chose ? ». Products already on the grocery list, on a recurring grocery item, or dismissed (« Pas besoin ») are left out. To add one, call add_grocery_item with its name and usual quantity.')]
class GetPurchaseSuggestionsTool
{
    public function __construct(
        private readonly PurchaseSuggestions $suggestions,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(): string
    {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        $suggestions = $this->suggestions->suggest($user);

        return json_encode(['suggestions' => $suggestions, 'count' => \count($suggestions)], JSON_THROW_ON_ERROR);
    }
}
