<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Message\UpdateTreasurySettingsCommand;
use Maggie\Finance\UseCase\GetSalaryDelay;
use Maggie\Finance\UseCase\GetTreasurySettings;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

#[McpTool(name: 'manage_treasury_settings', description: 'Actions: get, update. Settings: salaryExpectedDay (1 to 31, last day on shorter months) and overdraftLimitCents (positive integer cents, default 0). get returns the settings and salaryDelay. update changes only the given fields; clearSalaryExpectedDay true forgets the day. salaryDelay covers the previous and current month: isConfigured (false without expected day or reference salary, then nothing is late), isLate, delayDays, shortfallCents, months[] {month, expectedOn, receivedCents, isReceived}. The salary is late from the day after the expected day until credits recognised as salary reach 90 % of the reference salary.')]
class ManageTreasurySettingsTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly GetTreasurySettings $getTreasurySettings,
        private readonly GetSalaryDelay $getSalaryDelay,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(string $action, ?int $salaryExpectedDay = null, ?bool $clearSalaryExpectedDay = null, ?int $overdraftLimitCents = null): string
    {
        try {
            return match ($action) {
                'get' => $this->get(),
                'update' => $this->update($salaryExpectedDay, $clearSalaryExpectedDay ?? false, $overdraftLimitCents),
                default => json_encode(['error' => "Unknown action: {$action}. Use get or update."], JSON_THROW_ON_ERROR),
            };
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            return json_encode(['error' => ($e->getPrevious() ?? $e)->getMessage()], JSON_THROW_ON_ERROR);
        }
    }

    private function get(): string
    {
        $user = $this->userContext->requireUser();

        return json_encode(
            GetTreasurySettings::describe($this->getTreasurySettings->forUser($user))
                + ['salaryDelay' => $this->getSalaryDelay->execute($user)],
            JSON_THROW_ON_ERROR,
        );
    }

    private function update(?int $salaryExpectedDay, bool $clear, ?int $overdraftLimitCents): string
    {
        $changesDay = null !== $salaryExpectedDay || $clear;
        if (!$changesDay && null === $overdraftLimitCents) {
            return json_encode(['error' => 'update needs salaryExpectedDay (1 to 31), clearSalaryExpectedDay true to forget it, or overdraftLimitCents (0 or more cents).'], JSON_THROW_ON_ERROR);
        }
        if (null !== $salaryExpectedDay && ($salaryExpectedDay < 1 || $salaryExpectedDay > 31)) {
            return json_encode(['error' => 'salaryExpectedDay is a day of the month, from 1 to 31.'], JSON_THROW_ON_ERROR);
        }
        if (null !== $overdraftLimitCents && $overdraftLimitCents < 0) {
            return json_encode(['error' => 'overdraftLimitCents is the overdraft the bank allows, 0 or more cents.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();
        $settings = $this->getTreasurySettings->forUser($user);

        $this->bus->dispatch(new UpdateTreasurySettingsCommand(
            userId: (string) $user->getId(),
            treasurySettingsId: (string) $settings->getId(),
            salaryExpectedDay: $clear ? null : $salaryExpectedDay,
            changesSalaryExpectedDay: $changesDay,
            overdraftLimitCents: $overdraftLimitCents,
        ));

        return json_encode(
            ['success' => true]
                + GetTreasurySettings::describe($this->getTreasurySettings->forUser($user))
                + ['salaryDelay' => $this->getSalaryDelay->execute($user)],
            JSON_THROW_ON_ERROR,
        );
    }
}
