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

#[McpTool(name: 'import_bank_transactions', description: 'Only the movements the owner chose, read first with read_bank_statement; never a whole history unasked. Imports chosen bank movements into Maggie. accountId, from, to: as given to read_bank_statement (the bank is read again); refs[]: ref values of chosen entries, 200 at most. Movements already in Maggie are skipped, so a rerun imports nothing. Returns {imported, skipped, unknownRefs (absent from the bank\'s answer), transactionIds}; or status history_unavailable / rate_limited with the bank\'s message, nothing imported. Error: refs empty or above 200. Imported movements have no category, like the regular sync (qualify with manage_transactions, manage_peer_debts for a transfer with a person).')]
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
