<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\SuggestRecurringOperations;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;

#[McpTool(name: 'suggest_recurring_operations', description: 'Detects recurring operations in the bank history (same payee, steady amount, each week to year) and creates accepted ones. Action list writes nothing; minOccurrences (default 3, min 2). Each suggestion: label, counterpartyKey or labelPattern, accountId, period, anchorOn, dayRule, referenceAmountCents (signed cents, negative = expense), occurrences, categoryId. Action accept takes suggestions: list entries sent back unchanged; entries without a category, with a foreign category or account, or whose payee already has a series are ignored. Returns created, attached, proposed; error if none usable.')]
class SuggestRecurringOperationsTool
{
    public function __construct(
        private readonly SuggestRecurringOperations $suggestRecurringOperations,
        private readonly McpUserContext $userContext,
    ) {
    }

    /** @param list<array<string, mixed>>|null $suggestions */
    public function __invoke(
        string $action,
        ?int $minOccurrences = null,
        ?array $suggestions = null,
    ): string {
        try {
            return match ($action) {
                'list' => $this->list($minOccurrences),
                'accept' => $this->accept($suggestions),
                default => json_encode(['error' => "Unknown action: {$action}. Use list or accept."], JSON_THROW_ON_ERROR),
            };
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            return json_encode(['error' => ($e->getPrevious() ?? $e)->getMessage()], JSON_THROW_ON_ERROR);
        }
    }

    private function list(?int $minOccurrences): string
    {
        $user = $this->userContext->requireUser();

        if (null !== $minOccurrences && $minOccurrences < 2) {
            return json_encode(['error' => 'minOccurrences must be at least 2.'], JSON_THROW_ON_ERROR);
        }

        return json_encode([
            'suggestions' => $this->suggestRecurringOperations->suggest(
                $user,
                $minOccurrences ?? SuggestRecurringOperations::DEFAULT_MIN_OCCURRENCES,
            ),
        ], JSON_THROW_ON_ERROR);
    }

    /** @param list<array<string, mixed>>|null $suggestions */
    private function accept(?array $suggestions): string
    {
        $user = $this->userContext->requireUser();

        if (null === $suggestions || [] === $suggestions) {
            return json_encode(['error' => 'suggestions is required for accept.'], JSON_THROW_ON_ERROR);
        }

        $result = $this->suggestRecurringOperations->accept($user, array_values(array_filter($suggestions, \is_array(...))));

        if (0 === $result['created']) {
            return json_encode(['error' => 'No usable suggestion in the request.'], JSON_THROW_ON_ERROR);
        }

        return json_encode(['success' => true] + $result, JSON_THROW_ON_ERROR);
    }
}
