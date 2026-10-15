<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Elasticsearch\Message\IndexDocumentCommand;
use Maggie\Core\Entity\User;
use Maggie\Finance\Category\MerchantDictionary;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Enum\AmountDirection;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Enum\MatchType;
use Maggie\Finance\Event\OperationSaved;
use Maggie\Finance\Import\MerchantExtractor;
use Maggie\Finance\Repository\CategoryRepository;
use Maggie\Finance\Repository\RecurringOperationRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Uid\Ulid;

/**
 * Reads the statement back and proposes the rules it implies: operations
 * without recurrence, each with the body that creates it.
 *
 * Writing rules from a blank page means remembering how your bank spells each
 * shop — nobody does that. The history already holds the answer: the merchants
 * that come back, how often, and for how much.
 *
 * Nothing is created here. A suggestion is an offer with a guessed heading at
 * best; the user accepts what they recognise.
 */
class SuggestCategorizationRules
{
    /** A merchant seen once may never return; two is the sign of a habit. */
    private const MIN_OCCURRENCES = 2;

    public function __construct(
        private readonly TransactionRepository $transactionRepository,
        private readonly RecurringOperationRepository $operationRepository,
        private readonly CategoryRepository $categoryRepository,
        private readonly EntityManagerInterface $em,
        private readonly MessageBusInterface $bus,
        #[Autowire(service: 'event.bus')]
        private readonly MessageBusInterface $eventBus,
    ) {
    }

    /**
     * @return list<array{
     *     pattern: string, occurrences: int, totalCents: int, direction: string,
     *     categoryId: ?string, categoryName: ?string, samples: list<string>,
     *     operation: array{labelPattern: string, matchType: string, direction: string, priority: int, categoryId: ?string}
     * }>
     */
    public function suggest(User $user, int $minOccurrences = self::MIN_OCCURRENCES): array
    {
        $categoriesByName = [];
        foreach ($this->categoryRepository->findByUser($user) as $category) {
            $categoriesByName[mb_strtolower($category->getName())] = $category;
        }

        $covered = [];
        foreach ($this->operationRepository->findActiveWithoutRecurrenceForUser($user) as $rule) {
            $covered[MerchantExtractor::key($rule->getLabelPattern() ?? '')] = true;
        }

        $groups = [];
        /** @var array<string, array<string, array{category: Category, lines: int}>> $filed */
        $filed = [];

        foreach ($this->transactionRepository->findByUser($user) as $transaction) {
            // Money moved between one's own accounts is neither spent nor
            // earned: a heading for it would count it twice.
            if ($transaction->isInternalTransfer()) {
                continue;
            }

            $merchant = MerchantExtractor::extract($transaction->getLabel());
            if (null === $merchant) {
                continue;
            }

            $key = MerchantExtractor::key($merchant);
            if ('' === $key) {
                continue;
            }

            // A line already filed — by hand, or by a broader rule — needs no
            // rule of its own; it only tells where the merchant's next lines go.
            $category = $transaction->getCategory();
            if (null !== $category) {
                $id = (string) $category->getId();
                $filed[$key][$id] ??= ['category' => $category, 'lines' => 0];
                ++$filed[$key][$id]['lines'];

                continue;
            }

            // A category removed by hand is an answer too: leave it alone.
            if (CategorySource::Manual === $transaction->getCategorySource() || isset($covered[$key])) {
                continue;
            }

            $group = $groups[$key] ?? [
                'pattern' => $merchant,
                'occurrences' => 0,
                'totalCents' => 0,
                'debits' => 0,
                'credits' => 0,
                'samples' => [],
            ];

            ++$group['occurrences'];
            $group['totalCents'] += $transaction->getAmountCents();
            $transaction->getAmountCents() < 0 ? ++$group['debits'] : ++$group['credits'];

            if (\count($group['samples']) < 3) {
                $group['samples'][] = $transaction->getLabel();
            }

            $groups[$key] = $group;
        }

        $suggestions = [];

        foreach ($groups as $key => $group) {
            if ($group['occurrences'] < $minOccurrences) {
                continue;
            }

            $direction = $this->directionOf($group['debits'], $group['credits']);
            $category = $this->headingAlreadyGiven($filed[$key] ?? []);
            if (null === $category) {
                $guess = MerchantDictionary::categoryFor(
                    $group['pattern'],
                    AmountDirection::Credit === $direction,
                );
                $category = null === $guess ? null : ($categoriesByName[mb_strtolower($guess)] ?? null);
            }

            $suggestions[] = [
                'pattern' => $group['pattern'],
                'occurrences' => $group['occurrences'],
                'totalCents' => $group['totalCents'],
                'direction' => $direction->value,
                'categoryId' => null === $category ? null : (string) $category->getId(),
                'categoryName' => $category?->getName(),
                'samples' => $group['samples'],
                // The operation without recurrence accepting it creates, as
                // `POST /api/operations` and `manage_operations` take it.
                'operation' => [
                    'labelPattern' => $group['pattern'],
                    'matchType' => MatchType::Contains->value,
                    'direction' => $direction->value,
                    'priority' => self::priorityOf($group['pattern']),
                    'categoryId' => null === $category ? null : (string) $category->getId(),
                ],
            ];
        }

        // What weighs most, first: that is where a rule earns its keep.
        usort($suggestions, static fn (array $a, array $b) => abs($b['totalCents']) <=> abs($a['totalCents']));

        return $suggestions;
    }

    /**
     * Turns accepted suggestions into operations without recurrence and files
     * the history under them:
     * a rule the user cannot see working is a rule they do not trust.
     *
     * @param list<array<string, mixed>> $accepted straight from the request:
     *                                             every field is checked here
     *
     * @return array{created: int, patterns: list<string>, categorized: int}
     */
    public function accept(User $user, array $accepted): array
    {
        $created = [];
        $uncategorizedBefore = $this->transactionRepository->countUncategorizedForUser($user);

        foreach ($accepted as $entry) {
            $pattern = \is_string($entry['pattern'] ?? null) ? trim($entry['pattern']) : '';
            $categoryId = $entry['categoryId'] ?? null;

            if ('' === $pattern || !\is_string($categoryId) || !Ulid::isValid($categoryId)) {
                continue;
            }

            $category = $this->categoryRepository->find(Ulid::fromString($categoryId));
            if (!$category instanceof Category || !$category->getUser()->getId()->equals($user->getId())) {
                continue;
            }

            $rule = new RecurringOperation();
            $rule->setUser($user);
            $rule->setLabelPattern($pattern);
            $rule->setMatchType(MatchType::Contains);
            $rule->setCategory($category);
            $direction = \is_string($entry['direction'] ?? null) ? $entry['direction'] : '';
            $rule->setDirection(AmountDirection::tryFrom($direction) ?? AmountDirection::Any);
            $rule->setPriority(self::priorityOf($pattern));

            $this->em->persist($rule);
            $created[] = $rule;
        }

        $this->em->flush();

        foreach ($created as $rule) {
            $this->bus->dispatch(new IndexDocumentCommand(
                entityClass: RecurringOperation::class,
                entityId: (string) $rule->getId(),
            ));
        }

        // Once all are stored: each rule is applied knowing the ones that outrank it.
        foreach ($created as $rule) {
            $this->eventBus->dispatch(new OperationSaved((string) $rule->getId(), true));
        }

        return [
            'created' => \count($created),
            'patterns' => array_map(
                static fn (RecurringOperation $rule) => (string) $rule->getLabelPattern(),
                $created,
            ),
            'categorized' => $uncategorizedBefore - $this->transactionRepository->countUncategorizedForUser($user),
        ];
    }

    /**
     * The heading the user's history already gives this merchant, the most
     * used one when it has several. It beats any dictionary: it is their answer.
     *
     * @param array<string, array{category: Category, lines: int}> $headings
     */
    private function headingAlreadyGiven(array $headings): ?Category
    {
        $best = null;

        foreach ($headings as $heading) {
            if (null === $best || $heading['lines'] > $best['lines']) {
                $best = $heading;
            }
        }

        return $best['category'] ?? null;
    }

    /**
     * Longer patterns are the specific ones: "INTERMARCHE ESSENCE" has to be
     * read before "INTERMARCHE".
     */
    private static function priorityOf(string $pattern): int
    {
        return mb_strlen(trim($pattern));
    }

    /** A merchant only ever paid is a debit rule; one that also refunds is not. */
    private function directionOf(int $debits, int $credits): AmountDirection
    {
        if (0 === $credits) {
            return AmountDirection::Debit;
        }

        if (0 === $debits) {
            return AmountDirection::Credit;
        }

        return AmountDirection::Any;
    }
}
