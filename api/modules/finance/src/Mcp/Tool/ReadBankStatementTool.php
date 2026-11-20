<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Bank\EnableBanking\BankRefusedException;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Import\StatementRow;
use Maggie\Finance\UseCase\ImportStatement;
use Maggie\Finance\UseCase\ReadBankStatement;
use Mcp\Capability\Attribute\McpTool;
use Mcp\Capability\Attribute\Schema;
use Symfony\Component\String\UnicodeString;

#[McpTool(name: 'read_bank_statement', description: 'Reads one bank account\'s movements over a period straight from the bank; nothing is written. accountId: a bank-linked Maggie account; from, to: YYYY-MM-DD (to default today). Filters: search (label or counterparty, ignoring case and accents), direction (credit or debit), minAmountCents (absolute value). Returns status ok, entries[] ({ref, bookedOn, amountCents signed, label, counterparty, inMaggie}), firstDay, lastDay, count, truncated (true when entries[] holds the first 200 or the bank has more pages). ref feeds import_bank_transactions. Refusals: status history_unavailable (period over about 90 days needs a new strong authentication) with the bank\'s message; status rate_limited (daily ceiling).')]
class ReadBankStatementTool
{
    private const MAX_ENTRIES = 200;

    public function __construct(
        private readonly ReadBankStatement $readBankStatement,
        private readonly ImportStatement $importStatement,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(
        string $accountId,
        string $from,
        ?string $to = null,
        ?string $search = null,
        #[Schema(enum: ['credit', 'debit'])]
        ?string $direction = null,
        ?int $minAmountCents = null,
    ): string {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        if (null !== $direction && !\in_array($direction, ['credit', 'debit'], true)) {
            return json_encode(['error' => 'direction must be credit or debit.'], JSON_THROW_ON_ERROR);
        }

        try {
            [$start, $end] = ReadBankStatement::parsePeriod($from, $to);
            $read = $this->readBankStatement->read($user, $accountId, $start, $end);
        } catch (\InvalidArgumentException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (BankRefusedException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        if ('ok' !== $read['status']) {
            return json_encode(['status' => $read['status'], 'message' => $read['message']], JSON_THROW_ON_ERROR);
        }

        $matching = array_values(array_filter(
            $read['rows'],
            fn (StatementRow $row) => $this->matches($row, $search, $direction, $minAmountCents),
        ));

        $entries = $this->entries($read['account'], \array_slice($matching, 0, self::MAX_ENTRIES));
        $days = array_map(static fn (StatementRow $row) => $row->bookedAt->format('Y-m-d'), $matching);
        sort($days);

        return json_encode([
            'status' => 'ok',
            'entries' => $entries,
            'firstDay' => $days[0] ?? null,
            'lastDay' => [] === $days ? null : end($days),
            'count' => \count($matching),
            'truncated' => $read['truncated'] || \count($matching) > self::MAX_ENTRIES,
        ], JSON_THROW_ON_ERROR);
    }

    /**
     * @param list<StatementRow> $rows
     *
     * @return list<array<string, mixed>>
     */
    private function entries(Account $account, array $rows): array
    {
        $seen = [];
        $entries = [];
        foreach ($rows as $row) {
            $entries[] = [
                'ref' => ReadBankStatement::refOf($row),
                'bookedOn' => $row->bookedAt->format('Y-m-d'),
                'amountCents' => $row->amountCents,
                'label' => $row->label,
                'counterparty' => $row->counterpartyName,
                'inMaggie' => $this->importStatement->isKnown($account, $row, $seen),
            ];
        }

        return $entries;
    }

    private function matches(StatementRow $row, ?string $search, ?string $direction, ?int $minAmountCents): bool
    {
        if ('credit' === $direction && $row->amountCents <= 0) {
            return false;
        }
        if ('debit' === $direction && $row->amountCents >= 0) {
            return false;
        }
        if (null !== $minAmountCents && abs($row->amountCents) < $minAmountCents) {
            return false;
        }
        if (null === $search || '' === trim($search)) {
            return true;
        }

        $needle = $this->fold($search);

        return str_contains($this->fold($row->label), $needle)
            || str_contains($this->fold($row->counterpartyName ?? ''), $needle);
    }

    private function fold(string $text): string
    {
        return (new UnicodeString($text))->ascii()->lower()->toString();
    }
}
