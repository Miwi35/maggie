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

#[McpTool(name: 'read_bank_statement', description: 'Look at the movements of one bank account over a period, straight from the bank, WITHOUT importing anything: nothing is written in Maggie. Use it for movements the user asks about that Maggie does not hold (older than its history, or never imported), then say what you found and offer to import the relevant ones with import_bank_transactions. accountId is an account of Maggie linked to a bank connection; from and to are YYYY-MM-DD (to defaults to today). Banks often refuse a period older than 90 days without a new strong authentication: the answer is then status history_unavailable with the bank\'s message and nothing else; status rate_limited says the bank\'s daily ceiling is reached, try later. Every call costs one bank fetch per page, so keep the filters tight: search (text looked for in the label and the counterparty, ignoring case and accents), direction (credit or debit), minAmountCents (the amount\'s absolute value is at least that). Answers status ok, entries[] ({ref, bookedOn, amountCents signed — negative is a debit —, label, counterparty, inMaggie: already imported}), firstDay, lastDay, count (the movements matching) and truncated (true when entries[] holds only the first 200 or the bank has more pages: narrow the period or the filters). ref is what import_bank_transactions takes.')]
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
