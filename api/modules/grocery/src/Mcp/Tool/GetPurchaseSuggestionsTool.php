<?php

declare(strict_types=1);

namespace Maggie\Grocery\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Grocery\Service\PurchaseSuggestions;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_purchase_suggestions', description: 'Products bought 3+ times in past errands and due again: everyDays (median gap), lastBoughtOn, dueOn (YYYY-MM-DD), usualQuantity, usualUnit (null when unknown). Products already listed, recurring or dismissed are left out.')]
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
