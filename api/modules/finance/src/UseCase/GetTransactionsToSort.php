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
     * `totalCents` is what the whole pile weighs (the amounts without their
     * sign), for the « À qualifier · n · montant » pill; `judgeable` says
     * whether the line is a spend the owner can give a satisfaction to.
     *
     * @return array{
     *     total: int,
     *     totalCents: int,
     *     transactions: list<array{
     *         id: string, label: string, counterpartyName: ?string, amountCents: int, currency: string,
     *         bookedAt: string, accountId: string, accountName: string, judgeable: bool, retrospect: string,
     *         suggestion: array{categoryId: string, categoryPath: string, source: string, reason: string}|null
     *     }>
     * }
     */
    public function execute(User $user, int $limit = self::DEFAULT_LIMIT, bool $oldestFirst = false): array
    {
        $page = $this->transactionRepository->findToSortForUser($user, max(1, min($limit, self::MAX_LIMIT)), $oldestFirst);
        $context = $this->suggestionContext($user);

        return [
            'total' => $page['total'],
            'totalCents' => $this->transactionRepository->sumToSortForUser($user),
            'transactions' => array_map(
                fn (Transaction $transaction): array => $this->row($transaction, $context['rules'], $context['categoriesByName']),
                $page['transactions'],
            ),
        ];
    }

    /**
     * What the suggestions are drawn from: the owner's rules and the
     * categories by name. Read once for a whole page.
     *
     * @return array{rules: CategorizationRule[], categoriesByName: array<string, Category>}
     */
    public function suggestionContext(User $user): array
    {
        $categoriesByName = [];
        foreach ($this->categoryRepository->findByUser($user) as $category) {
            $categoriesByName[mb_strtolower($category->getName())] ??= $category;
        }

        return ['rules' => $this->ruleRepository->findActiveForUser($user), 'categoriesByName' => $categoriesByName];
    }

    /**
     * One line of the pile, with the suggestion it would get.
     *
     * @param CategorizationRule[]    $rules
     * @param array<string, Category> $categoriesByName
     *
     * @return array<string, mixed>
     */
    public function row(Transaction $transaction, array $rules, array $categoriesByName): array
    {
        return [
            'id' => (string) $transaction->getId(),
            'label' => $transaction->getLabel(),
            'counterpartyName' => $transaction->getCounterpartyName(),
            'amountCents' => $transaction->getAmountCents(),
            'currency' => $transaction->getCurrency(),
            'bookedAt' => $transaction->getBookedAt()->format('Y-m-d'),
            'accountId' => (string) $transaction->getAccount()->getId(),
            'accountName' => $transaction->getAccount()->getName(),
            'judgeable' => $transaction->canBeJudged(),
            'retrospect' => $transaction->getRetrospect()->value,
            'suggestion' => $this->suggest($transaction, $rules, $categoriesByName),
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
