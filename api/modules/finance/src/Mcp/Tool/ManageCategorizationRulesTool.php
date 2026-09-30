<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Message\CreateCategorizationRuleCommand;
use Maggie\Finance\Message\DeleteCategorizationRuleCommand;
use Maggie\Finance\Message\UpdateCategorizationRuleCommand;
use Maggie\Finance\Message\UpdateTransactionCommand;
use Maggie\Finance\Repository\CategorizationRuleRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\UseCase\ApplyCategorizationRules;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'manage_categorization_rules', description: 'List, create, update or delete the rules that file transactions under a category automatically, apply them to the uncategorized history, or learn a new rule from a transaction. A rule matches a label (matchType: contains, starts_with, equals — case-insensitive) and may narrow by amount: minAmountCents/maxAmountCents are ABSOLUTE cents (a 15,99 € expense matches 1000..2000) and direction is any, debit (expense) or credit (income). The highest priority rule that matches wins. A category set by hand is never overwritten. On update, only provided fields change; to remove an amount bound, list minAmountCents or maxAmountCents in clear.')]
class ManageCategorizationRulesTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly CategorizationRuleRepository $ruleRepository,
        private readonly TransactionRepository $transactionRepository,
        private readonly ApplyCategorizationRules $applyCategorizationRules,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(
        string $action,
        ?string $categorizationRuleId = null,
        ?string $labelPattern = null,
        ?string $categoryId = null,
        ?string $matchType = null,
        ?string $direction = null,
        ?int $minAmountCents = null,
        ?int $maxAmountCents = null,
        ?int $priority = null,
        ?bool $isActive = null,
        ?string $transactionId = null,
        ?array $clear = null,
    ): string {
        try {
            return match ($action) {
                'list' => $this->list(),
                'create' => $this->create($labelPattern, $categoryId, $matchType, $direction, $minAmountCents, $maxAmountCents, $priority, $isActive),
                'update' => $this->update($categorizationRuleId, $labelPattern, $categoryId, $matchType, $direction, $minAmountCents, $maxAmountCents, $priority, $isActive, $clear),
                'delete' => $this->delete($categorizationRuleId),
                'apply' => $this->apply(),
                'learn' => $this->learn($transactionId, $categoryId, $labelPattern, $matchType, $priority),
                default => json_encode(['error' => "Unknown action: {$action}. Use list, create, update, delete, apply, or learn."], JSON_THROW_ON_ERROR),
            };
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        } catch (\ValueError $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }
    }

    private function list(): string
    {
        $user = $this->userContext->requireUser();

        return json_encode([
            'rules' => array_map(
                fn (CategorizationRule $r) => $this->serialize($r),
                $this->ruleRepository->findByUser($user),
            ),
        ], JSON_THROW_ON_ERROR);
    }

    private function create(?string $labelPattern, ?string $categoryId, ?string $matchType, ?string $direction, ?int $minAmountCents, ?int $maxAmountCents, ?int $priority, ?bool $isActive): string
    {
        if ($labelPattern === null || $categoryId === null) {
            return json_encode(['error' => 'labelPattern and categoryId are required for create.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();

        $stamped = $this->bus->dispatch(new CreateCategorizationRuleCommand(
            userId: (string) $user->getId(),
            labelPattern: $labelPattern,
            categoryId: $categoryId,
            matchType: $matchType ?? 'contains',
            direction: $direction ?? 'any',
            minAmountCents: $minAmountCents,
            maxAmountCents: $maxAmountCents,
            priority: $priority ?? 0,
            isActive: $isActive ?? true,
        ));

        /** @var CategorizationRule $rule */
        $rule = $stamped->last(HandledStamp::class)->getResult();

        return json_encode([
            'success' => true,
            'rule' => $this->serialize($rule),
        ], JSON_THROW_ON_ERROR);
    }

    private function update(?string $categorizationRuleId, ?string $labelPattern, ?string $categoryId, ?string $matchType, ?string $direction, ?int $minAmountCents, ?int $maxAmountCents, ?int $priority, ?bool $isActive, ?array $clear): string
    {
        if ($categorizationRuleId === null) {
            return json_encode(['error' => 'categorizationRuleId is required for update.'], JSON_THROW_ON_ERROR);
        }

        $stamped = $this->bus->dispatch(new UpdateCategorizationRuleCommand(
            categorizationRuleId: $categorizationRuleId,
            labelPattern: $labelPattern,
            categoryId: $categoryId,
            matchType: $matchType,
            direction: $direction,
            minAmountCents: $minAmountCents,
            maxAmountCents: $maxAmountCents,
            priority: $priority,
            isActive: $isActive,
            clearFields: array_values(array_intersect($clear ?? [], ['minAmountCents', 'maxAmountCents'])),
        ));

        /** @var CategorizationRule $rule */
        $rule = $stamped->last(HandledStamp::class)->getResult();

        return json_encode([
            'success' => true,
            'rule' => $this->serialize($rule),
        ], JSON_THROW_ON_ERROR);
    }

    private function delete(?string $categorizationRuleId): string
    {
        if ($categorizationRuleId === null) {
            return json_encode(['error' => 'categorizationRuleId is required for delete.'], JSON_THROW_ON_ERROR);
        }

        $this->bus->dispatch(new DeleteCategorizationRuleCommand(categorizationRuleId: $categorizationRuleId));

        return json_encode(['success' => true], JSON_THROW_ON_ERROR);
    }

    private function apply(): string
    {
        $user = $this->userContext->requireUser();

        return json_encode([
            'success' => true,
        ] + $this->applyCategorizationRules->execute($user), JSON_THROW_ON_ERROR);
    }

    /**
     * Turn a transaction into a rule: file that transaction under the category
     * by hand, and remember the label so the next one files itself.
     */
    private function learn(?string $transactionId, ?string $categoryId, ?string $labelPattern, ?string $matchType, ?int $priority): string
    {
        if ($transactionId === null || $categoryId === null) {
            return json_encode(['error' => 'transactionId and categoryId are required for learn.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();

        $transaction = $this->transactionRepository->find($transactionId);
        if ($transaction === null) {
            return json_encode(['error' => "Transaction not found: {$transactionId}"], JSON_THROW_ON_ERROR);
        }

        $pattern = $labelPattern ?? self::patternFromLabel($transaction->getLabel());
        if ($pattern === '') {
            return json_encode(['error' => 'Could not derive a pattern from that transaction label; pass labelPattern explicitly.'], JSON_THROW_ON_ERROR);
        }

        $stamped = $this->bus->dispatch(new CreateCategorizationRuleCommand(
            userId: (string) $user->getId(),
            labelPattern: $pattern,
            categoryId: $categoryId,
            matchType: $matchType ?? 'contains',
            priority: $priority ?? 0,
        ));

        /** @var CategorizationRule $rule */
        $rule = $stamped->last(HandledStamp::class)->getResult();

        $this->bus->dispatch(new UpdateTransactionCommand(
            transactionId: $transactionId,
            categoryId: $categoryId,
            categorySource: CategorySource::Manual->value,
        ));

        return json_encode([
            'success' => true,
            'rule' => $this->serialize($rule),
        ], JSON_THROW_ON_ERROR);
    }

    /**
     * Bank labels carry noise — dates, card numbers, receipt ids. Keep the
     * leading words, which is what identifies the merchant.
     */
    public static function patternFromLabel(string $label): string
    {
        $normalized = trim(preg_replace('/\s+/u', ' ', $label) ?? '');
        if ($normalized === '') {
            return '';
        }

        $words = [];
        foreach (explode(' ', $normalized) as $word) {
            // Stop at the first token that looks like a number, date or reference.
            if (preg_match('/\d/u', $word) === 1) {
                break;
            }
            $words[] = $word;
            if (\count($words) === 3) {
                break;
            }
        }

        return $words === [] ? $normalized : implode(' ', $words);
    }

    /** @return array<string, mixed> */
    private function serialize(CategorizationRule $rule): array
    {
        return [
            'id' => (string) $rule->getId(),
            'labelPattern' => $rule->getLabelPattern(),
            'matchType' => $rule->getMatchType()->value,
            'direction' => $rule->getDirection()->value,
            'minAmountCents' => $rule->getMinAmountCents(),
            'maxAmountCents' => $rule->getMaxAmountCents(),
            'priority' => $rule->getPriority(),
            'isActive' => $rule->isActive(),
            'categoryId' => (string) $rule->getCategory()->getId(),
            'categoryName' => $rule->getCategory()->getName(),
        ];
    }
}
