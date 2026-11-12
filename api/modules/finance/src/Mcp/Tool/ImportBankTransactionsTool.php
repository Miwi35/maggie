<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Bank\EnableBanking\BankRefusedException;
use Maggie\Finance\Import\StatementRow;
use Maggie\Finance\UseCase\ImportStatement;
use Maggie\Finance\UseCase\ReadBankStatement;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'import_bank_transactions', description: 'Import into Maggie ONLY the bank movements the user chose, after read_bank_statement showed them and the user agreed — never a whole period, never without their go-ahead. accountId, from and to are the ones given to read_bank_statement (the bank is read again over the same period); refs[] are the ref values of the chosen entries (200 at most). A movement already in Maggie is skipped, so running it again imports nothing. Answers {imported, skipped, unknownRefs (asked for, but not found in the bank\'s answer), transactionIds}; or status history_unavailable / rate_limited with the bank\'s message, and nothing is imported. The movements arrive without a category, like those of the regular sync: qualify them afterwards with manage_transactions (or manage_peer_debts for a transfer with a person). The regular sync is untouched.')]
class ImportBankTransactionsTool
{
    private const MAX_REFS = 200;

    public function __construct(
        private readonly ReadBankStatement $readBankStatement,
        private readonly ImportStatement $importStatement,
        private readonly McpUserContext $userContext,
    ) {
    }

    /** @param list<string>|null $refs */
    public function __invoke(
        string $accountId,
        string $from,
        ?string $to = null,
        ?array $refs = null,
    ): string {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        $wanted = array_values(array_unique(array_filter($refs ?? [], static fn (string $ref) => '' !== trim($ref))));
        if ([] === $wanted) {
            return json_encode(['error' => 'refs must list at least one ref returned by read_bank_statement.'], JSON_THROW_ON_ERROR);
        }
        if (\count($wanted) > self::MAX_REFS) {
            return json_encode(['error' => sprintf('refs holds %d entries at most: import the movements in several calls.', self::MAX_REFS)], JSON_THROW_ON_ERROR);
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

        $chosen = array_values(array_filter(
            $read['rows'],
            static fn (StatementRow $row) => \in_array(ReadBankStatement::refOf($row), $wanted, true),
        ));
        $found = array_map(static fn (StatementRow $row) => ReadBankStatement::refOf($row), $chosen);

        $result = $this->importStatement->execute($read['account'], $chosen);

        return json_encode([
            'imported' => $result['imported'],
            'skipped' => $result['skipped'],
            'unknownRefs' => array_values(array_diff($wanted, $found)),
            'transactionIds' => $result['transactionIds'],
        ], JSON_THROW_ON_ERROR);
    }
}
