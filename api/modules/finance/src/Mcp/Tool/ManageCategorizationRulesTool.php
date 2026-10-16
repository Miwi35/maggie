<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Category\RulePattern;
use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Enum\AmountDirection;
use Maggie\Finance\Exception\InvalidQualificationException;
use Maggie\Finance\Mcp\QualificationArguments;
use Maggie\Finance\Message\CreateCategorizationRuleCommand;
use Maggie\Finance\Message\DeleteCategorizationRuleCommand;
use Maggie\Finance\Message\UpdateCategorizationRuleCommand;
use Maggie\Finance\Message\UpdateTransactionCommand;
use Maggie\Finance\Repository\CategorizationRuleRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\Service\MatchCriteria;
use Maggie\Finance\UseCase\ApplyCategorizationRules;
use Maggie\Finance\UseCase\PreviewCategorizationRule;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'manage_categorization_rules', description: 'List, create, update or delete the rules that file transactions under a category automatically, apply them to the uncategorized history, preview what a rule would catch, or learn a new rule from a transaction. A rule matches a label (matchType: contains, starts_with, equals — case-insensitive) and may narrow by amount; the label is optional when direction is debit or credit AND an amount bound is given — then the rule recognises a transaction on its sign and amount alone, whatever its label or counterparty (« an income of 4 750 € ± 10 % is the salary »: direction credit, minAmountCents 427500, maxAmountCents 522500; compute the bounds yourself). A rule with no label and no directed amount bound is refused with the current state, nothing is deduced. Amounts: minAmountCents/maxAmountCents are ABSOLUTE cents (a 15,99 € expense matches 1000..2000) and direction is any, debit (expense) or credit (income). The highest priority rule that matches wins and gives the line its whole qualification (categoryId, mandatory, kind); a line qualified by hand is never overwritten. Updating the categoryId, mandatory or kind of a rule reaches every line it qualified, except those qualified by hand since. On update, only provided fields change; to remove the label or an amount bound, list labelPattern, minAmountCents or maxAmountCents in clear. BEFORE creating a rule, call action preview with the same criteria (labelPattern if any, matchType, direction, minAmountCents, maxAmountCents, categoryId): it writes nothing and returns total (transactions the rule recognises), changeCount (those it would re-file) and the latest matches. Show the user what it found, then ask whether to apply the rule to the existing transactions; create and update take applyToExisting (default false: only future transactions are filed). When editing a saved rule, pass its categorizationRuleId to preview so the preview replaces it.'.QualificationArguments::DESCRIPTION)]
class ManageCategorizationRulesTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly CategorizationRuleRepository $ruleRepository,
        private readonly TransactionRepository $transactionRepository,
        private readonly ApplyCategorizationRules $applyCategorizationRules,
        private readonly PreviewCategorizationRule $previewCategorizationRule,
        private readonly McpUserContext $userContext,
    ) {
    }

    /** @param list<string>|null $clear */
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
        ?bool $applyToExisting = null,
        ?bool $mandatory = null,
        ?string $kind = null,
    ): string {
        try {
            return match ($action) {
                'list' => $this->list(),
                'create' => $this->create($labelPattern, $categoryId, $matchType, $direction, $minAmountCents, $maxAmountCents, $priority, $isActive, $applyToExisting ?? false, ['mandatory' => $mandatory, 'kind' => $kind]),
                'update' => $this->update($categorizationRuleId, $labelPattern, $categoryId, $matchType, $direction, $minAmountCents, $maxAmountCents, $priority, $isActive, $clear, $applyToExisting ?? false, ['mandatory' => $mandatory, 'kind' => $kind]),
                'delete' => $this->delete($categorizationRuleId),
                'apply' => $this->apply(),
                'preview' => $this->preview($categorizationRuleId, $labelPattern, $categoryId, $matchType, $direction, $minAmountCents, $maxAmountCents, $priority, $isActive),
                'learn' => $this->learn($transactionId, $categoryId, $labelPattern, $matchType, $priority),
                default => json_encode(['error' => "Unknown action: {$action}. Use list, create, update, delete, apply, preview, or learn."], JSON_THROW_ON_ERROR),
            };
        } catch (InvalidQualificationException $e) {
            return json_encode($e->toResponse(), JSON_THROW_ON_ERROR);
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

    /** @param array<string, mixed> $qualification */
    private function create(?string $labelPattern, ?string $categoryId, ?string $matchType, ?string $direction, ?int $minAmountCents, ?int $maxAmountCents, ?int $priority, ?bool $isActive, bool $applyToExisting, array $qualification): string
    {
        if (null === $categoryId) {
            return json_encode(['error' => 'categoryId is required for create.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();

        $criteria = new MatchCriteria(
            labelPattern: $labelPattern,
            direction: AmountDirection::tryFrom($direction ?? 'any') ?? AmountDirection::Any,
            minAmountCents: $minAmountCents,
            maxAmountCents: $maxAmountCents,
        );
        if (!$criteria->isRecognisable()) {
            return json_encode([
                'error' => CategorizationRule::UNRECOGNISABLE,
                'current' => [
                    'labelPattern' => $labelPattern,
                    'direction' => $direction ?? 'any',
                    'minAmountCents' => $minAmountCents,
                    'maxAmountCents' => $maxAmountCents,
                ],
            ], JSON_THROW_ON_ERROR);
        }

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
            applyToExisting: $applyToExisting,
            qualification: QualificationArguments::whole($qualification),
        ));

        /** @var CategorizationRule $rule */
        $rule = $stamped->last(HandledStamp::class)->getResult();

        return json_encode([
            'success' => true,
            'rule' => $this->serialize($rule),
        ], JSON_THROW_ON_ERROR);
    }

    /**
     * @param list<string>|null    $clear
     * @param array<string, mixed> $qualification
     */
    private function update(?string $categorizationRuleId, ?string $labelPattern, ?string $categoryId, ?string $matchType, ?string $direction, ?int $minAmountCents, ?int $maxAmountCents, ?int $priority, ?bool $isActive, ?array $clear, bool $applyToExisting, array $qualification): string
    {
        if (null === $categorizationRuleId) {
            return json_encode(['error' => 'categorizationRuleId is required for update.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();
        $before = $this->ruleRepository->findOneBy(['id' => $categorizationRuleId, 'user' => $user]);
        $current = null === $before ? null : $this->serialize($before);

        try {
            $stamped = $this->bus->dispatch(new UpdateCategorizationRuleCommand(
                userId: (string) $user->getId(),
                categorizationRuleId: $categorizationRuleId,
                labelPattern: $labelPattern,
                categoryId: $categoryId,
                matchType: $matchType,
                direction: $direction,
                minAmountCents: $minAmountCents,
                maxAmountCents: $maxAmountCents,
                priority: $priority,
                isActive: $isActive,
                clearFields: array_values(array_intersect($clear ?? [], ['labelPattern', 'minAmountCents', 'maxAmountCents'])),
                applyToExisting: $applyToExisting,
                qualification: QualificationArguments::whole($qualification, $before?->getQualification()),
            ));
        } catch (HandlerFailedException $e) {
            if (null !== $current && CategorizationRule::UNRECOGNISABLE === $e->getPrevious()?->getMessage()) {
                return json_encode(['error' => CategorizationRule::UNRECOGNISABLE, 'current' => $current], JSON_THROW_ON_ERROR);
            }

            throw $e;
        }

        /** @var CategorizationRule $rule */
        $rule = $stamped->last(HandledStamp::class)->getResult();

        return json_encode([
            'success' => true,
            'rule' => $this->serialize($rule),
        ], JSON_THROW_ON_ERROR);
    }

    private function delete(?string $categorizationRuleId): string
    {
        if (null === $categorizationRuleId) {
            return json_encode(['error' => 'categorizationRuleId is required for delete.'], JSON_THROW_ON_ERROR);
        }

        $this->bus->dispatch(new DeleteCategorizationRuleCommand(
            userId: (string) $this->userContext->requireUser()->getId(),
            categorizationRuleId: $categorizationRuleId,
        ));

        return json_encode(['success' => true], JSON_THROW_ON_ERROR);
    }

    private function preview(?string $categorizationRuleId, ?string $labelPattern, ?string $categoryId, ?string $matchType, ?string $direction, ?int $minAmountCents, ?int $maxAmountCents, ?int $priority, ?bool $isActive): string
    {
        try {
            $preview = $this->previewCategorizationRule->execute(
                user: $this->userContext->requireUser(),
                labelPattern: $labelPattern,
                matchType: $matchType ?? 'contains',
                direction: $direction ?? 'any',
                minAmountCents: $minAmountCents,
                maxAmountCents: $maxAmountCents,
                categoryId: $categoryId,
                priority: $priority ?? 0,
                isActive: $isActive ?? true,
                ruleId: $categorizationRuleId,
            );
        } catch (\DomainException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        return json_encode(['success' => true] + $preview, JSON_THROW_ON_ERROR);
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
        if (null === $transactionId || null === $categoryId) {
            return json_encode(['error' => 'transactionId and categoryId are required for learn.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();

        $transaction = $this->transactionRepository->find($transactionId);
        if (null === $transaction || !$transaction->getUser()->getId()->equals($user->getId())) {
            return json_encode(['error' => "Transaction not found: {$transactionId}"], JSON_THROW_ON_ERROR);
        }

        $pattern = $labelPattern ?? self::patternFromLabel($transaction->getLabel());
        if ('' === $pattern) {
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
            userId: (string) $user->getId(),
            transactionId: $transactionId,
            categoryId: $categoryId,
        ));

        return json_encode([
            'success' => true,
            'rule' => $this->serialize($rule),
        ], JSON_THROW_ON_ERROR);
    }

    public static function patternFromLabel(string $label): string
    {
        return RulePattern::fromLabel($label);
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
            ...$rule->getQualification()->toArray(),
        ];
    }
}
