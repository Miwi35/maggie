<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\SuggestRecurringOperations;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;

#[McpTool(name: 'suggest_recurring_operations', description: 'Find the recurring operations the bank history implies (a subscription, the rent, a salary: the same payee coming back every week, month, quarter or year for a steady amount) and create the ones the user accepts. Action list reads and writes nothing: each suggestion has label, counterpartyName / counterpartyKey (who the bank names) or labelPattern (read in the label when there is no counterparty), accountId, accountName, period (weekly, monthly, quarterly, yearly), anchorOn (the first occurrence seen), dayRule (fixed_day or last_day_of_month), referenceAmountCents (signed integer cents, the median: negative is an expense; divide by 100 to speak it, say "13,49 € par mois", never the raw cents), amountSpreadPercent, occurrences, categoryId / categoryName (null when no heading is known: ask the user for one before accepting) and up to three samples. minOccurrences (default 3, at least 2) narrows the list. Present the suggestions in plain words ("Flixo revient le 12 de chaque mois, 13,49 €") and NEVER accept one without the user\'s explicit yes. Action accept takes suggestions: the chosen entries of the list, sent back as they were (categoryId filled in when null); an entry without a category, with a category or account that is not the user\'s, or whose payee already has a series is ignored. It creates the series and attaches the history they recognise, and answers created, labels, attached, proposed. Never suggest cancelling a subscription. Loan instalments and transfers between own accounts are never suggested. To change or delete a series afterwards use manage_recurring_operations.')]
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
