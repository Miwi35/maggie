<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp;

use Maggie\Finance\Entity\Qualification;
use Maggie\Finance\Enum\QualificationKind;
use Maggie\Finance\Exception\InvalidQualificationException;

/**
 * The qualification as the MCP tools take it: one argument per parameter of
 * `Qualification`, named as its fields, given all together or not at all —
 * a qualification is copied whole, never completed field by field.
 */
final class QualificationArguments
{
    /** What every tool that qualifies says about it, after its own description. */
    public const string DESCRIPTION = ' mandatory (true or false: an obligation the user cannot cut) and kind (none, saving, investment, loan or repayment: the direction is the sign of the amount, a repayment is « I pay back » on a debit and « I am paid back » on a credit) qualify the lines, with the category: give them together, or neither — one without the other is refused with the current qualification. Without them, a new categoryId brings the qualification of the category.';

    /**
     * @param array<string, mixed> $given   each parameter of `Qualification` by name, null when omitted
     * @param ?Qualification       $current what the object carries now, quoted back on a refusal
     *
     * @return array<string, mixed>|null the whole qualification, or null when none was given
     *
     * @throws InvalidQualificationException when only some parameters are given, or a kind is unknown
     */
    public static function whole(array $given, ?Qualification $current = null): ?array
    {
        $fields = Qualification::fields();
        $missing = array_values(array_filter($fields, static fn (string $field) => null === ($given[$field] ?? null)));

        if (\count($missing) === \count($fields)) {
            return null;
        }
        if ([] !== $missing) {
            throw new InvalidQualificationException(sprintf('%s go together: the qualification is copied whole. Missing: %s.', implode(' and ', $fields), implode(', ', $missing)), null === $current ? null : self::describe($current));
        }
        if (null === QualificationKind::tryFrom((string) $given['kind'])) {
            throw new InvalidQualificationException('kind is one of: '.implode(', ', array_column(QualificationKind::cases(), 'value')).'.', null === $current ? null : self::describe($current));
        }

        return array_intersect_key($given, array_flip($fields));
    }

    /**
     * The qualification as the tools hand it out: its parameters, flat, and
     * the category's id.
     *
     * @return array<string, mixed>
     */
    public static function describe(Qualification $qualification): array
    {
        return [
            'categoryId' => null !== $qualification->getCategory() ? (string) $qualification->getCategory()->getId() : null,
            ...$qualification->toArray(),
        ];
    }
}
