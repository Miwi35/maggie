<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Finance\Contact\CounterpartyMatch;
use Maggie\Finance\Entity\Contact;
use Maggie\Finance\Repository\ContactCounterpartyRepository;
use Maggie\Finance\Repository\TransactionRepository;

/**
 * « Peut-être aussi Tom » (MAG-450): the labels of the lines no one claims
 * whose words start with a word of the contact's name — « Tom » finds
 * « PAYPAL *TOMMARTIN » —, the most frequent first. Neutral movements and
 * lines already attached are left out; the owner confirms each one.
 */
class SuggestContactCounterparties
{
    public const int LIMIT = 10;

    /** Shorter name words (« de », « Le ») would find half the statement. */
    private const int MIN_WORD_LENGTH = 3;

    public function __construct(
        private readonly TransactionRepository $transactionRepository,
        private readonly ContactCounterpartyRepository $counterpartyRepository,
    ) {
    }

    /** @return list<array{label: string, transactionCount: int, lastSeenOn: string}> */
    public function execute(Contact $contact): array
    {
        $words = array_values(array_filter(
            explode(' ', CounterpartyMatch::keyOf($contact->getName())),
            static fn (string $word): bool => mb_strlen($word) >= self::MIN_WORD_LENGTH,
        ));
        if ([] === $words) {
            return [];
        }

        $linked = [];
        foreach ($this->counterpartyRepository->findByUser($contact->getUser()) as $counterparty) {
            $linked[$counterparty->getMatchKey()] = true;
        }

        /** @var array<string, array{label: string, transactionCount: int, lastSeenOn: string}> $groups */
        $groups = [];
        // Newest first: the first spelling met is the one kept.
        foreach ($this->transactionRepository->findWithoutContact($contact->getUser()) as $transaction) {
            $label = CounterpartyMatch::labelFor($transaction);
            $key = CounterpartyMatch::keyOf($label);
            if ('' === $key || isset($linked[$key]) || !self::namesOneOf($key, $words)) {
                continue;
            }

            if (!isset($groups[$key])) {
                $groups[$key] = ['label' => $label, 'transactionCount' => 0, 'lastSeenOn' => $transaction->getBookedAt()->format('Y-m-d')];
            }
            ++$groups[$key]['transactionCount'];
        }

        $suggestions = array_values($groups);
        usort($suggestions, static fn (array $a, array $b): int => [$b['transactionCount'], $b['lastSeenOn']] <=> [$a['transactionCount'], $a['lastSeenOn']]);

        return \array_slice($suggestions, 0, self::LIMIT);
    }

    /** @param list<string> $words */
    private static function namesOneOf(string $key, array $words): bool
    {
        foreach (explode(' ', $key) as $labelWord) {
            foreach ($words as $word) {
                if (str_starts_with($labelWord, $word)) {
                    return true;
                }
            }
        }

        return false;
    }
}
