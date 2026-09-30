<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Message\CreateAccountCommand;
use Maggie\Finance\Message\DeleteAccountCommand;
use Maggie\Finance\Message\UpdateAccountCommand;
use Maggie\Finance\Repository\AccountRepository;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'manage_accounts', description: 'List, create, update, or delete bank accounts. An account has a name, bank, type (checking, savings, investment, cash), currency (ISO 4217), a balance in cents, and an optional cushion flag (emergency-fund account). Amounts are always in integer cents. On update, only provided fields change; to empty the optional bank, list it in clear.')]
class ManageAccountsTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly AccountRepository $accountRepository,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(
        string $action,
        ?string $accountId = null,
        ?string $name = null,
        ?string $type = null,
        ?string $bank = null,
        ?string $currency = null,
        ?int $balanceCents = null,
        ?bool $isCushion = null,
        ?array $clear = null,
    ): string {
        try {
            return match ($action) {
                'list' => $this->list(),
                'create' => $this->create($name, $type, $bank, $currency, $balanceCents, $isCushion),
                'update' => $this->update($accountId, $name, $type, $bank, $currency, $balanceCents, $isCushion, $clear),
                'delete' => $this->delete($accountId),
                default => json_encode(['error' => "Unknown action: {$action}. Use list, create, update, or delete."], JSON_THROW_ON_ERROR),
            };
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }

    private function list(): string
    {
        $user = $this->userContext->requireUser();

        $accounts = $this->accountRepository->findByUser($user);

        return json_encode([
            'accounts' => array_map(fn (Account $a) => $this->serialize($a), $accounts),
        ], JSON_THROW_ON_ERROR);
    }

    private function create(?string $name, ?string $type, ?string $bank, ?string $currency, ?int $balanceCents, ?bool $isCushion): string
    {
        if ($name === null) {
            return json_encode(['error' => 'Name is required for create.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();

        $envelope = $this->bus->dispatch(new CreateAccountCommand(
            userId: (string) $user->getId(),
            name: $name,
            type: $type ?? 'checking',
            bank: $bank,
            currency: $currency ?? 'EUR',
            balanceCents: $balanceCents ?? 0,
            isCushion: $isCushion ?? false,
        ));

        /** @var Account $account */
        $account = $envelope->last(HandledStamp::class)->getResult();

        return json_encode([
            'success' => true,
            'account' => $this->serialize($account),
        ], JSON_THROW_ON_ERROR);
    }

    private function update(?string $accountId, ?string $name, ?string $type, ?string $bank, ?string $currency, ?int $balanceCents, ?bool $isCushion, ?array $clear): string
    {
        if ($accountId === null) {
            return json_encode(['error' => 'accountId is required for update.'], JSON_THROW_ON_ERROR);
        }

        $envelope = $this->bus->dispatch(new UpdateAccountCommand(
            accountId: $accountId,
            name: $name,
            type: $type,
            bank: $bank,
            currency: $currency,
            balanceCents: $balanceCents,
            isCushion: $isCushion,
            clearFields: array_values(array_intersect($clear ?? [], ['bank'])),
        ));

        /** @var Account $account */
        $account = $envelope->last(HandledStamp::class)->getResult();

        return json_encode([
            'success' => true,
            'account' => $this->serialize($account),
        ], JSON_THROW_ON_ERROR);
    }

    private function delete(?string $accountId): string
    {
        if ($accountId === null) {
            return json_encode(['error' => 'accountId is required for delete.'], JSON_THROW_ON_ERROR);
        }

        $this->bus->dispatch(new DeleteAccountCommand(accountId: $accountId));

        return json_encode(['success' => true], JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function serialize(Account $account): array
    {
        return [
            'id' => (string) $account->getId(),
            'name' => $account->getName(),
            'bank' => $account->getBank(),
            'type' => $account->getType()->value,
            'currency' => $account->getCurrency(),
            'balanceCents' => $account->getBalanceCents(),
            'isCushion' => $account->isCushion(),
        ];
    }
}
