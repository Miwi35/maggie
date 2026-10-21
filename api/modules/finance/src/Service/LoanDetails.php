<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Enum\LoanPeriodicity;
use Maggie\Finance\Enum\LoanType;
use Maggie\Finance\Enum\PartialRepaymentEffect;
use Maggie\Finance\Enum\PrepaymentPenaltyMode;

/**
 * The type and conditions of a loan (MAG-522) as the commands carry them:
 * field name → scalar, a null emptying an optional field. One place reads
 * them for the API and the MCP tool alike, so both refuse the same values.
 */
final class LoanDetails
{
    public const INT_FIELDS = [
        'initialAmountCents', 'aprBasisPoints', 'durationMonths', 'insuranceRateBasisPoints',
        'insuranceMonthlyCents', 'feesCents', 'marginBasisPoints', 'rateCapBasisPoints',
        'rateFloorBasisPoints', 'deferralMonths', 'prepaymentPenaltyFixedCents',
        'minPartialRepaymentCents', 'noticeDays',
    ];

    public const FIELDS = [
        'type', 'projected', 'firstPaymentOn', 'periodicity', 'referenceIndex', 'steps',
        'earlyRepaymentAllowed', 'prepaymentPenaltyMode', 'partialRepaymentEffect',
        ...self::INT_FIELDS,
    ];

    /**
     * The loan's details, for a processor that sends its whole state.
     *
     * @return array<string, mixed>
     */
    public static function of(Loan $loan): array
    {
        return array_intersect_key($loan->toArray(), array_flip(self::FIELDS));
    }

    /**
     * @param array<string, mixed> $details
     *
     * @throws \DomainException on an unknown field or a value of the wrong kind
     */
    public static function apply(Loan $loan, array $details): void
    {
        foreach ($details as $field => $value) {
            match ($field) {
                'type' => null === $value ?: $loan->setType(self::enum(LoanType::class, $field, $value)),
                'periodicity' => null === $value ?: $loan->setPeriodicity(self::enum(LoanPeriodicity::class, $field, $value)),
                'prepaymentPenaltyMode' => null === $value ?: $loan->setPrepaymentPenaltyMode(self::enum(PrepaymentPenaltyMode::class, $field, $value)),
                'partialRepaymentEffect' => $loan->setPartialRepaymentEffect(null === $value ? null : self::enum(PartialRepaymentEffect::class, $field, $value)),
                'projected' => null === $value ?: $loan->setProjected(self::bool($field, $value)),
                'earlyRepaymentAllowed' => null === $value ?: $loan->setEarlyRepaymentAllowed(self::bool($field, $value)),
                'firstPaymentOn' => $loan->setFirstPaymentOn(null === $value ? null : self::day($value)),
                'referenceIndex' => $loan->setReferenceIndex(null === $value || '' === $value ? null : self::string($field, $value)),
                'steps' => $loan->setSteps(null === $value ? null : self::steps($value)),
                default => \in_array($field, self::INT_FIELDS, true)
                    ? $loan->{'set'.ucfirst($field)}(null === $value ? null : self::int($field, $value))
                    : throw new \DomainException("Unknown loan field: {$field}."),
            };
        }
    }

    /**
     * @template T of \BackedEnum
     *
     * @param class-string<T> $enum
     *
     * @return T
     */
    private static function enum(string $enum, string $field, mixed $value): \BackedEnum
    {
        $case = \is_string($value) ? $enum::tryFrom($value) : null;
        if (null === $case) {
            $allowed = implode(', ', array_map(static fn (\BackedEnum $case) => $case->value, $enum::cases()));

            throw new \DomainException(\sprintf('%s must be one of: %s.', $field, $allowed));
        }

        return $case;
    }

    private static function int(string $field, mixed $value): int
    {
        if (!\is_int($value)) {
            throw new \DomainException("{$field} must be an integer.");
        }

        return $value;
    }

    private static function bool(string $field, mixed $value): bool
    {
        if (!\is_bool($value)) {
            throw new \DomainException("{$field} must be true or false.");
        }

        return $value;
    }

    private static function string(string $field, mixed $value): string
    {
        if (!\is_string($value)) {
            throw new \DomainException("{$field} must be a string.");
        }

        return $value;
    }

    private static function day(mixed $value): \DateTimeImmutable
    {
        $parsed = \is_string($value)
            ? \DateTimeImmutable::createFromFormat('!Y-m-d', $value, new \DateTimeZone('UTC'))
            : false;

        if (false === $parsed || $parsed->format('Y-m-d') !== $value) {
            throw new \DomainException('firstPaymentOn must be a day, YYYY-MM-DD.');
        }

        return $parsed;
    }

    /** @return list<array{fromMonth: int, monthlyPaymentCents: int}> */
    private static function steps(mixed $value): array
    {
        if (!\is_array($value) || !array_is_list($value)) {
            throw new \DomainException('steps must be a list of {fromMonth, monthlyPaymentCents}.');
        }

        $steps = [];
        foreach ($value as $step) {
            if (!\is_array($step) || !\is_int($step['fromMonth'] ?? null) || !\is_int($step['monthlyPaymentCents'] ?? null)) {
                throw new \DomainException('Each step is {fromMonth: an integer, monthlyPaymentCents: an integer}.');
            }
            $steps[] = ['fromMonth' => $step['fromMonth'], 'monthlyPaymentCents' => $step['monthlyPaymentCents']];
        }

        usort($steps, static fn (array $a, array $b) => $a['fromMonth'] <=> $b['fromMonth']);

        return $steps;
    }
}
