<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Enum\AmountDirection;
use Maggie\Finance\Enum\MatchType;
use Maggie\Finance\Repository\CategorizationRuleRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\Service\OwnedReferenceResolver;
use Maggie\Finance\Specification\TransactionMatchesRule;
use Symfony\Component\Uid\Ulid;

/**
 * What a rule would catch, before it is saved: the transactions its criteria
 * recognise, and among them the ones applying it to the history would change.
 * Nothing is written.
 */
class PreviewCategorizationRule
{
    public const MAX_MATCHES = 50;

    public function __construct(
        private readonly TransactionRepository $transactionRepository,
        private readonly CategorizationRuleRepository $ruleRepository,
        private readonly OwnedReferenceResolver $references,
        private readonly TransactionMatchesRule $matchesRule,
        private readonly CategorizeTransaction $categorizeTransaction,
    ) {
    }

    /**
     * @param ?string $categoryId where the rule would file; without it, lines are listed but none would change
     * @param ?string $ruleId     the saved rule being edited, which these criteria replace
     *
     * @return array{total: int, changeCount: int, matches: list<array{
     *     transactionId: string, label: string, amountCents: int, bookedAt: string,
     *     currentCategoryId: ?string, wouldChange: bool
     * }>}
     *
     * @throws \DomainException when the criteria are not those of a valid rule
     */
    public function execute(
        User $user,
        string $labelPattern,
        string $matchType = 'contains',
        string $direction = 'any',
        ?int $minAmountCents = null,
        ?int $maxAmountCents = null,
        ?string $categoryId = null,
        int $priority = 0,
        bool $isActive = true,
        ?string $ruleId = null,
    ): array {
        $draft = $this->draft($user, $labelPattern, $matchType, $direction, $minAmountCents, $maxAmountCents, $categoryId, $priority, $isActive);
        $category = null === $categoryId || '' === $categoryId ? null : $draft->getCategory();
        $rules = null === $category ? [] : $this->rulesWith($draft, $user, $ruleId);

        $matches = [];
        $total = 0;
        $changeCount = 0;

        foreach ($this->transactionRepository->findByUser($user) as $transaction) {
            if (!$this->matchesRule->matches($draft->matchCriteria(), $category, $transaction)) {
                continue;
            }

            $wouldChange = $draft->isActive()
                && $this->categorizeTransaction->awaitsCategory($transaction)
                && $this->categorizeTransaction->winner($transaction, $rules) === $draft;

            ++$total;
            $changeCount += $wouldChange ? 1 : 0;

            if (\count($matches) < self::MAX_MATCHES) {
                $matches[] = [
                    'transactionId' => (string) $transaction->getId(),
                    'label' => $transaction->getLabel(),
                    'amountCents' => $transaction->getAmountCents(),
                    'bookedAt' => $transaction->getBookedAt()->format(\DateTimeInterface::ATOM),
                    'currentCategoryId' => null === $transaction->getCategory() ? null : (string) $transaction->getCategory()->getId(),
                    'wouldChange' => $wouldChange,
                ];
            }
        }

        return ['total' => $total, 'changeCount' => $changeCount, 'matches' => $matches];
    }

    /** The rule as it would be saved, but never persisted. */
    private function draft(User $user, string $labelPattern, string $matchType, string $direction, ?int $min, ?int $max, ?string $categoryId, int $priority, bool $isActive): CategorizationRule
    {
        if ('' === $labelPattern) {
            throw new \DomainException('A rule needs a label pattern to match on.');
        }

        $type = MatchType::tryFrom($matchType) ?? throw new \DomainException("Unknown match type: {$matchType}.");
        $directionEnum = AmountDirection::tryFrom($direction) ?? throw new \DomainException("Unknown direction: {$direction}.");

        if ((null !== $min && $min < 0) || (null !== $max && $max < 0)) {
            throw new \DomainException('Amount bounds must be zero or positive.');
        }

        if (null !== $min && null !== $max && $min > $max) {
            throw new \DomainException('The minimum amount must not exceed the maximum amount.');
        }

        $draft = (new CategorizationRule())
            ->setUser($user)
            ->setLabelPattern($labelPattern)
            ->setMatchType($type)
            ->setDirection($directionEnum)
            ->setMinAmountCents($min)
            ->setMaxAmountCents($max)
            ->setPriority($priority)
            ->setIsActive($isActive);

        if (null !== $categoryId && '' !== $categoryId) {
            if (!Ulid::isValid($categoryId)) {
                throw new \DomainException("Category not found: {$categoryId}");
            }
            $draft->setCategory($this->references->category($categoryId, $user));
        }

        return $draft;
    }

    /**
     * The user's other active rules and the draft, in the order they get their
     * say. On a tie a new draft comes last, as a rule created now would; one being
     * edited keeps the place its id gives it.
     *
     * @return list<CategorizationRule>
     */
    private function rulesWith(CategorizationRule $draft, User $user, ?string $ruleId): array
    {
        $others = array_values(array_filter(
            $this->ruleRepository->findActiveForUser($user),
            static fn (CategorizationRule $rule) => (string) $rule->getId() !== $ruleId,
        ));

        // A rule being edited keeps the place its id gives it among equals; a new one comes last.
        $edited = null === $ruleId || !Ulid::isValid($ruleId) ? null : $this->ruleRepository->find($ruleId);
        $rank = $edited instanceof CategorizationRule && $edited->getUser()->getId()->equals($user->getId())
            ? (string) $edited->getId()
            : null;

        $before = array_filter($others, static fn (CategorizationRule $rule) => $rule->getPriority() > $draft->getPriority()
            || ($rule->getPriority() === $draft->getPriority() && (null === $rank || strcmp((string) $rule->getId(), $rank) < 0)));
        $after = array_filter($others, static fn (CategorizationRule $rule) => !\in_array($rule, $before, true));

        return [...$before, $draft, ...$after];
    }
}
