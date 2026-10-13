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

#[McpTool(name: 'manage_treasury_settings', description: 'Read or change the treasury settings: salaryExpectedDay, the day of the month the salary is expected on (1 to 31; on a shorter month, its last day), and overdraftLimitCents, the overdraft the bank allows in positive integer cents (0, the default, means any negative balance is an overdraft; get_treasury_outlook only announces an overdraft below minus this limit). Action get returns the settings and salaryDelay; action update sets salaryExpectedDay and/or overdraftLimitCents — only what is given changes — or forgets the day with clearSalaryExpectedDay true. Setting the day does not look back by itself: offer to run detect_internal_transfers afterwards, which marks as advances the transfers from savings already made during a delay. salaryDelay says whether the salary is late, for the previous month and the current one: isConfigured is false when no expected day or no reference salary is known, and then nothing is late — say so instead of saying the salary is on time. The salary is late from the day after the expected day until the credits in the « Salaire » category reach 90 % of the reference salary; a bonus closes nothing. isLate, delayDays (days since the expected day of the oldest month still open) and shortfallCents (what is still missing, integer cents) sum it up; months[] gives month, expectedOn, receivedCents, referenceCents, isReceived, receivedOn, isDue, delayDays and shortfallCents.')]
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
