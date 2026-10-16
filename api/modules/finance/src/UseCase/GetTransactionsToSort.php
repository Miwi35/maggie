<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Category\MerchantDictionary;
use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\MatchType;
use Maggie\Finance\Import\MerchantExtractor;
use Maggie\Finance\Repository\CategorizationRuleRepository;
use Maggie\Finance\Repository\CategoryRepository;
use Maggie\Finance\Repository\TransactionRepository;

/**
 * The pile of lines to sort by hand, newest first, each with the heading the
 * app would suggest and — always — why. A suggestion without its reason is
 * a guess the owner has to take on trust; with it they can tell in a glance
 * whether the rule or the merchant guess is right.
 *
 * The rules speak first (they are the owner's own), the merchant dictionary
 * only when no rule claims the line.
 */
class GetTransactionsToSort
{
    public const DEFAULT_LIMIT = 50;
    public const MAX_LIMIT = 200;

    public function __construct(
        private readonly TransactionRepository $transactionRepository,
        private readonly CategorizationRuleRepository $ruleRepository,
        private readonly CategoryRepository $categoryRepository,
        private readonly CategorizeTransaction $categorizeTransaction,
    ) {
    }

    /**
     * @return array{
     *     total: int,
     *     transactions: list<array{
     *         id: string, label: string, counterpartyName: ?string, amountCents: int, currency: string,
     *         bookedAt: string, accountId: string, accountName: string,
     *         suggestion: array{categoryId: string, categoryPath: string, source: string, reason: string}|null
     *     }>
     * }
     */
    public function execute(User $user, int $limit = self::DEFAULT_LIMIT): array
    {
        $page = $this->transactionRepository->findToSortForUser($user, max(1, min($limit, self::MAX_LIMIT)));

        $rules = $this->ruleRepository->findActiveForUser($user);
        $categoriesByName = [];
        foreach ($this->categoryRepository->findByUser($user) as $category) {
            $categoriesByName[mb_strtolower($category->getName())] ??= $category;
        }

        return [
            'total' => $page['total'],
            'transactions' => array_map(
                fn (Transaction $transaction): array => [
                    'id' => (string) $transaction->getId(),
                    'label' => $transaction->getLabel(),
                    'counterpartyName' => $transaction->getCounterpartyName(),
                    'amountCents' => $transaction->getAmountCents(),
                    'currency' => $transaction->getCurrency(),
                    'bookedAt' => $transaction->getBookedAt()->format('Y-m-d'),
                    'accountId' => (string) $transaction->getAccount()->getId(),
                    'accountName' => $transaction->getAccount()->getName(),
                    'suggestion' => $this->suggest($transaction, $rules, $categoriesByName),
                ],
                $page['transactions'],
            ),
        ];
    }

    /**
     * @param CategorizationRule[]    $rules
     * @param array<string, Category> $categoriesByName
     *
     * @return array{categoryId: string, categoryPath: string, source: string, reason: string}|null
     */
    private function suggest(Transaction $transaction, array $rules, array $categoriesByName): ?array
    {
        $rule = $this->categorizeTransaction->winner($transaction, $rules);
        if (null !== $rule) {
            return $this->suggestion($rule->getCategory(), 'rule', self::ruleReason($rule));
        }

        $merchant = MerchantExtractor::extract($transaction->getLabel());
        $guess = MerchantDictionary::categoryFor($merchant ?? $transaction->getLabel(), $transaction->getAmountCents() > 0);
        $category = null === $guess ? null : ($categoriesByName[mb_strtolower($guess)] ?? null);
        if (null === $category) {
            return null;
        }

        return $this->suggestion(
            $category,
            'merchant',
            'Marchand connu : '.mb_convert_case($merchant ?? $transaction->getLabel(), MB_CASE_TITLE),
        );
    }

    /** @return array{categoryId: string, categoryPath: string, source: string, reason: string} */
    private function suggestion(Category $category, string $source, string $reason): array
    {
        return [
            'categoryId' => (string) $category->getId(),
            'categoryPath' => $category->getPath(),
            'source' => $source,
            'reason' => $reason,
        ];
    }

    private static function ruleReason(CategorizationRule $rule): string
    {
        $pattern = $rule->getLabelPattern();
        if (null === $pattern) {
            return 'Règle sur le montant';
        }

        return match ($rule->getMatchType()) {
            MatchType::Contains => "Règle « contient {$pattern} »",
            MatchType::StartsWith => "Règle « commence par {$pattern} »",
            MatchType::Equals => "Règle « égal à {$pattern} »",
        };
    }
}
