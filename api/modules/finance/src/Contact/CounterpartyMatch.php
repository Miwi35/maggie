<?php

declare(strict_types=1);

namespace Maggie\Finance\Contact;

use Maggie\Finance\Entity\Contact;
use Maggie\Finance\Entity\ContactCounterparty;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Import\MerchantExtractor;

/**
 * Which contact a transaction belongs to, from the counterparties the owner
 * linked (MAG-450).
 *
 * When the bank named the counterparty, its key must equal the counterparty's;
 * otherwise the label's key must contain it, word for word — the bank names
 * the payee in 5 lines out of 370 (MAG-437), the label is what is left.
 *
 * Never a movement between the owner's own accounts nor a rejection: a
 * transfer to my savings that says « TOM MARTIN » is not Tom's.
 */
final class CounterpartyMatch
{
    /**
     * Words that make a line a transfer between people rather than a purchase:
     * the bank's wording of a transfer, and the apps people pay each other with.
     */
    private const TRANSFER_WORDS = ['vir', 'virement', 'virt', 'sepa', 'paypal', 'lydia', 'wero', 'paylib'];

    /** `MerchantExtractor::key`, spaces collapsed so a removed dash leaves no gap. */
    public static function keyOf(string $label): string
    {
        return trim(preg_replace('/\s+/', ' ', MerchantExtractor::key($label)) ?? '');
    }

    public static function matches(Transaction $transaction, string $matchKey): bool
    {
        if ('' === $matchKey || TransferKind::None !== $transaction->getTransferKind()) {
            return false;
        }

        $counterpartyKey = $transaction->getCounterpartyKey();
        if (null !== $counterpartyKey) {
            return self::keyOf($counterpartyKey) === $matchKey;
        }

        return str_contains(' '.self::keyOf($transaction->getLabel()).' ', ' '.$matchKey.' ');
    }

    /**
     * The contact of the longest counterparty that recognises the transaction:
     * « VIR DE TOM MARTIN » is more specific than « TOM MARTIN ».
     *
     * @param iterable<ContactCounterparty> $counterparties the owner's
     */
    public static function contactFor(Transaction $transaction, iterable $counterparties): ?Contact
    {
        $best = null;
        foreach ($counterparties as $counterparty) {
            if (!self::matches($transaction, $counterparty->getMatchKey())) {
                continue;
            }
            if (null === $best || mb_strlen($counterparty->getMatchKey()) > mb_strlen($best->getMatchKey())) {
                $best = $counterparty;
            }
        }

        return $best?->getContact();
    }

    /**
     * Whether « Associer à un contact ? » is worth asking (MAG-450): a transfer
     * no one claims yet — the bank named its counterparty or the label reads
     * like a transfer —, never a movement between the owner's own accounts.
     */
    public static function asksForContact(Transaction $transaction): bool
    {
        if (null !== $transaction->getContact() || TransferKind::None !== $transaction->getTransferKind()) {
            return false;
        }
        if (null !== $transaction->getCounterpartyKey()) {
            return true;
        }

        return [] !== array_intersect(self::TRANSFER_WORDS, explode(' ', self::keyOf($transaction->getLabel())));
    }

    /**
     * The label to link for a transaction: the counterparty the bank named, or
     * the label without the references and dates that change every month.
     */
    public static function labelFor(Transaction $transaction): string
    {
        return $transaction->getCounterpartyName() ?? MerchantExtractor::withoutReferences($transaction->getLabel());
    }
}
