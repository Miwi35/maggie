<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;

/**
 * Reads, from one line alone, whether it is a leg of a movement between the
 * owner's own accounts — when the other leg is not there to be paired with
 * (an account that is not synced, a bank that gives a partial statement).
 *
 * Two things give it away. The holder: the label or the counterparty names the
 * owner (« VIR INST de Meven Cadare », in any case and either order). Or the
 * bank: a transfer worded after the bank of another of his accounts
 * (« VIREMENT RECU REVOLUT BANK UAB »). A debit labelled with the holder's
 * name and nothing else is his own too (MAG-642). Everything else is somebody else —
 * « MR CADARE ALEX » shares a surname, not a person — and stays an income.
 */
class OwnLegRecognizer
{
    public const string HOLDER = 'holder';
    public const string BANK = 'bank';

    /** A transfer says so; a card payment in a shop of the same name does not. */
    private const array TRANSFER_WORDS = ['vir', 'virement', 'virements', 'transfer'];

    private const int MIN_CUT_WORD = 2;

    private const int MIN_BANK_LENGTH = 4;

    /**
     * @param Account[] $accounts every account of the line's owner
     *
     * @return self::HOLDER|self::BANK|null
     */
    public function reasonFor(Transaction $transaction, array $accounts): ?string
    {
        $label = self::tokens($transaction->getLabel());
        $counterparty = null === $transaction->getCounterpartyName() ? [] : self::tokens($transaction->getCounterpartyName());
        $holder = self::tokens($transaction->getUser()->getName());

        // One first name is shared by too many payees to be taken for the owner.
        if (\count($holder) >= 2 && (
            self::namesTheHolder($counterparty, $holder, true, false)
            || (self::hasTransferWord($label) && self::namesTheHolder($label, $holder, false, self::isCut($transaction->getLabel())))
        )) {
            return self::HOLDER;
        }

        if (self::isUnqualifiedDebitNamedAfterTheHolder($transaction, $label, $holder)) {
            return self::HOLDER;
        }

        if (self::hasTransferWord($label) && self::namesAnotherBank($transaction, [$label, $counterparty], $accounts)) {
            return self::BANK;
        }

        return null;
    }

    /**
     * Some banks (N26) word a transfer to another account of the holder with
     * the holder's name alone, no transfer word, no counterparty (one that names
     * someone else is a payment whose reference is the owner's name). A debit only:
     * the same bank words credits that way too, and a line the owner or a rule
     * already qualified is never taken back from them.
     *
     * @param list<string> $label
     * @param list<string> $holder
     */
    private static function isUnqualifiedDebitNamedAfterTheHolder(Transaction $transaction, array $label, array $holder): bool
    {
        return $transaction->getAmountCents() < 0
            && \count($holder) >= 2
            && null === $transaction->getCategory()
            && CategorySource::None === $transaction->getCategorySource()
            && self::namesTheHolder($label, $holder, true, false)
            && (null === $transaction->getCounterpartyName() || self::namesTheHolder(self::tokens($transaction->getCounterpartyName()), $holder, true, false));
    }

    /**
     * Every word of the holder's name is in the text, whatever the order; when
     * the bank cut the label (`$cut`), its last word counts for the name it
     * starts. With `$exactly`, the text is the name and nothing else.
     *
     * @param list<string> $text
     * @param list<string> $holder
     */
    private static function namesTheHolder(array $text, array $holder, bool $exactly, bool $cut): bool
    {
        if ([] === $text || ($exactly && \count($text) !== \count($holder))) {
            return false;
        }

        $last = array_key_last($text);
        foreach ($holder as $word) {
            $found = false;
            foreach ($text as $position => $candidate) {
                if ($candidate === $word || ($cut && $position === $last && self::isCutFrom($candidate, $word))) {
                    $found = true;

                    break;
                }
            }
            if (!$found) {
                return false;
            }
        }

        return true;
    }

    /**
     * @param list<list<string>> $texts
     * @param Account[]          $accounts
     */
    private static function namesAnotherBank(Transaction $transaction, array $texts, array $accounts): bool
    {
        $own = self::tokens($transaction->getAccount()->getBank() ?? '');

        foreach ($accounts as $account) {
            $bank = self::tokens($account->getBank() ?? '');
            if ([] === $bank || $bank === $own || $account->getId()->equals($transaction->getAccount()->getId())
                || \strlen(implode('', $bank)) < self::MIN_BANK_LENGTH) {
                continue;
            }

            foreach ($texts as $text) {
                if (self::contains($text, $bank)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * @param list<string> $text
     * @param list<string> $words consecutive, in this order
     */
    private static function contains(array $text, array $words): bool
    {
        for ($start = 0; $start + \count($words) <= \count($text); ++$start) {
            if (\array_slice($text, $start, \count($words)) === $words) {
                return true;
            }
        }

        return false;
    }

    /** A bank cuts a long label short with an ellipsis, often in the middle of a word. */
    private static function isCut(string $label): bool
    {
        return 1 === preg_match('/(…|\.{2,})\s*$/u', $label);
    }

    /** @param list<string> $text */
    private static function hasTransferWord(array $text): bool
    {
        return [] !== array_intersect($text, self::TRANSFER_WORDS);
    }

    private static function isCutFrom(string $candidate, string $word): bool
    {
        return \strlen($candidate) >= self::MIN_CUT_WORD && $candidate !== $word && str_starts_with($word, $candidate);
    }

    /**
     * The words of a text, folded to lower case without accents.
     *
     * @return list<string>
     */
    private static function tokens(string $text): array
    {
        $folded = strtr(mb_strtolower($text), [
            'à' => 'a', 'â' => 'a', 'ä' => 'a', 'é' => 'e', 'è' => 'e', 'ê' => 'e', 'ë' => 'e',
            'î' => 'i', 'ï' => 'i', 'ô' => 'o', 'ö' => 'o', 'ù' => 'u', 'û' => 'u', 'ü' => 'u', 'ç' => 'c',
        ]);

        return preg_split('/[^a-z0-9]+/', $folded, -1, \PREG_SPLIT_NO_EMPTY) ?: [];
    }
}
