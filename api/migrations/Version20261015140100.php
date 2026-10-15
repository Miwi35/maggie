<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\ArrayParameterType;
use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The qualification taken from the category's flag (MAG-476): what the flag
 * said of every line of a category, each transaction, series and rule now
 * says of itself.
 *
 * - The flag gives the qualification: mandatory → mandatory; optional and
 *   income → nothing; saving → kind saving; investment → investment; debt →
 *   repayment, mandatory. The owner's « Remboursement avance » and
 *   « Remboursement comble découvert » are repayments, whatever their flag.
 * - Who qualified a transaction is who categorised it: the series it is
 *   attached to; the rule that files it — the rule of its category that
 *   recognises it, the highest priority first, else the only rule of that
 *   category; by hand stays by hand.
 *
 * Data only. Every statement is computed from the categories, the series and
 * the rules, never from what it writes, so a second run writes the same
 * values again; the flag is left in place (MAG-479 removes it). The
 * Elasticsearch documents are rebuilt by the reindex the deploy runs after the
 * migrations.
 */
final class Version20261015140100 extends AbstractMigration
{
    /** The owner's categories of repayments, which no flag told apart (ticket MAG-476). */
    private const REPAYMENT_CATEGORIES = ['Remboursement avance', 'Remboursement comble découvert'];

    private const MANDATORY = "c.obligation IN ('mandatory', 'debt')";

    private const KIND = "CASE WHEN c.name IN (:repayments) THEN 'repayment' WHEN c.obligation = 'debt' THEN 'repayment' WHEN c.obligation IN ('saving', 'investment') THEN c.obligation ELSE 'none' END";

    public function getDescription(): string
    {
        return 'Qualify every transaction, recurring operation and categorization rule from the flag of its category, and record who qualified each transaction';
    }

    public function up(Schema $schema): void
    {
        $this->report();

        foreach (['transaction', 'recurring_operation', 'categorization_rule'] as $table) {
            $this->addSql(
                sprintf('UPDATE %s t SET qualification_mandatory = %s, qualification_kind = %s FROM category c WHERE c.id = t.category_id', $table, self::MANDATORY, self::KIND),
                ['repayments' => self::REPAYMENT_CATEGORIES],
                ['repayments' => ArrayParameterType::STRING],
            );
        }

        $this->addSql("UPDATE transaction SET qualified_by_series_id = recurring_operation_id WHERE category_source = 'series'");

        $byRule = $this->rulesThatFiled();
        foreach ($byRule as $ruleId => $transactionIds) {
            $this->addSql(
                'UPDATE transaction SET qualified_by_rule_id = :rule WHERE id IN (:ids)',
                ['rule' => $ruleId, 'ids' => $transactionIds],
                ['ids' => ArrayParameterType::STRING],
            );
        }

        $this->write(sprintf('MAG-476: %d transaction(s) qualified by %d rule(s).', array_sum(array_map('count', $byRule)), \count($byRule)));
    }

    public function down(Schema $schema): void
    {
        // The schema migration before this one drops the columns.
    }

    /** What the flags say today, by (mandatory, kind): the totals the migrated transactions must keep. */
    private function report(): void
    {
        $rows = $this->connection->fetchAllAssociative(
            sprintf('SELECT %s AS mandatory, %s AS kind, COUNT(t.id) AS lines, SUM(t.amount_cents) AS cents FROM transaction t JOIN category c ON c.id = t.category_id GROUP BY 1, 2 ORDER BY 1, 2', self::MANDATORY, self::KIND),
            ['repayments' => self::REPAYMENT_CATEGORIES],
            ['repayments' => ArrayParameterType::STRING],
        );

        $this->write('MAG-476: transactions by qualification, from the flag of their category:');
        foreach ($rows as $row) {
            $this->write(sprintf('  mandatory=%s kind=%s: %d line(s), %d cents', $row['mandatory'] ? 'true' : 'false', $row['kind'], $row['lines'], $row['cents']));
        }
    }

    /**
     * The rule behind each transaction a rule categorised: among the rules of
     * its user and its category, the first — by priority, then age — that
     * recognises it as the engine does (label, direction, absolute bounds),
     * else the only one there is.
     *
     * @return array<string, list<string>> rule id => transaction ids
     */
    private function rulesThatFiled(): array
    {
        $rules = [];
        foreach ($this->connection->fetchAllAssociative('SELECT id, user_id, category_id, label_pattern, match_type, direction, min_amount_cents, max_amount_cents FROM categorization_rule ORDER BY priority DESC, id ASC') as $rule) {
            $rules[$rule['user_id'].'|'.$rule['category_id']][] = $rule;
        }

        $byRule = [];
        foreach ($this->connection->fetchAllAssociative("SELECT id, user_id, category_id, label, amount_cents FROM transaction WHERE category_source = 'rule' AND category_id IS NOT NULL ORDER BY id") as $transaction) {
            $candidates = $rules[$transaction['user_id'].'|'.$transaction['category_id']] ?? [];
            $matching = array_values(array_filter($candidates, static fn (array $rule) => self::recognises($rule, $transaction['label'], (int) $transaction['amount_cents'])));
            $rule = $matching[0] ?? (1 === \count($candidates) ? $candidates[0] : null);

            if (null !== $rule) {
                $byRule[$rule['id']][] = $transaction['id'];
            }
        }

        return $byRule;
    }

    /**
     * `TransactionMatcher` as it stood, for a rule: frozen here so the
     * migration means tomorrow what it meant the day it ran.
     *
     * @param array<string, mixed> $rule
     */
    private static function recognises(array $rule, string $label, int $amountCents): bool
    {
        $haystack = mb_strtolower($label);
        $needle = mb_strtolower((string) $rule['label_pattern']);

        $labelMatches = match ($rule['match_type']) {
            'starts_with' => '' !== $needle && str_starts_with($haystack, $needle),
            'equals' => $haystack === $needle,
            default => '' !== $needle && str_contains($haystack, $needle),
        };
        if (!$labelMatches
            || ('debit' === $rule['direction'] && $amountCents >= 0)
            || ('credit' === $rule['direction'] && $amountCents <= 0)) {
            return false;
        }

        $absolute = abs($amountCents);

        return (null === $rule['min_amount_cents'] || $absolute >= (int) $rule['min_amount_cents'])
            && (null === $rule['max_amount_cents'] || $absolute <= (int) $rule['max_amount_cents']);
    }
}
